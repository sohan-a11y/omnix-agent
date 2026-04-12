package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.omnix.agent.R
import com.omnix.agent.ai.AppKnowledgeEngine
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.core.ScreenDump
import com.omnix.agent.skills.HumanBehaviorSimulator
import com.omnix.agent.ui.OverlayUI
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AutonomyLoop — the OMNIX ReAct (Reason+Act) core.
 *
 * Coordinates a multi-step reasoning loop using Gemma as the planner.
 * Command dispatch is delegated to:
 *  - [UICommandExecutor]   — L1 UI control via Accessibility
 *  - [CodeCommandExecutor] — L2 shell / Python / Termux / HTTP
 *  - [DynamicSkillWriter]  — L3 self-modification (skill caching)
 *  - [ResponseParser]      — THOUGHT/CMD extraction and narration
 *
 * Safety: ALL commands pass through [SafeGuard] before execution.
 */
object AutonomyLoop {

    private const val TAG = "AutonomyLoop"
    private const val MAX_STEPS = 20
    private const val TOTAL_TIMEOUT_MS = 300_000L
    private const val SETTLE_DELAY_MS = 1000L
    private const val SCREEN_SETTLE_DELAY_MS = 1500L

    // ── Result types ──────────────────────────────────────────────────────────

    data class LoopResult(
        val success: Boolean,
        val message: String,
        val steps: Int,
        val history: List<StepRecord>
    )

    data class StepRecord(
        val step: Int,
        val thought: String,
        val command: String,
        val result: String
    )

    sealed class StepUpdate {
        data class Progress(val step: Int, val message: String) : StepUpdate()
        data class Completed(val result: LoopResult) : StepUpdate()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun runAsFlow(goal: String, context: Context): Flow<StepUpdate> = flow {
        val result = runInternal(goal, context) { step, msg -> emit(StepUpdate.Progress(step, msg)) }
        emit(StepUpdate.Completed(result))
    }

    suspend fun run(goal: String, context: Context): LoopResult =
        runInternal(goal, context) { _, _ -> }

    // ── Internal entry — checks for cached dynamic skill first ────────────────

    private suspend fun runInternal(
        goal: String,
        context: Context,
        onStep: suspend (Int, String) -> Unit
    ): LoopResult {
        val a11y = OmnixAccessibilityService.instance
        if (a11y == null) Log.w(TAG, "Accessibility not running — UI commands will be limited")

        if (!GemmaInferenceEngine.isReady()) {
            return LoopResult(false, "Gemma model not loaded yet.", 0, emptyList())
        }

        DynamicSkillWriter.initialize(context)
        val cachedSkill = DynamicSkillWriter.findMatchingSkill(goal)
        if (cachedSkill != null) {
            Log.i(TAG, "Using cached skill: ${cachedSkill.name}")
            onStep(0, "⚡ Using learned skill: ${cachedSkill.name}...")
            val result = DynamicSkillWriter.executeSkill(cachedSkill, context)
            if (result.success) {
                return LoopResult(
                    true, "✅ ${cachedSkill.name}: ${result.stdout.take(200)}", 1,
                    listOf(StepRecord(1, "Used cached skill", cachedSkill.scriptContent.take(100), result.stdout.take(200)))
                )
            }
            Log.w(TAG, "Cached skill failed — falling through to full loop")
        }

        return runFullLoop(goal, context, a11y, onStep)
    }

    // ── ReAct loop ────────────────────────────────────────────────────────────

    private suspend fun runFullLoop(
        goal: String,
        context: Context,
        a11y: OmnixAccessibilityService?,
        onStep: suspend (Int, String) -> Unit
    ): LoopResult {
        val history = mutableListOf<StepRecord>()
        val startTime = System.currentTimeMillis()
        val uiExec = UICommandExecutor(a11y, context)
        val codeExec = CodeCommandExecutor(context)

        Log.i(TAG, "═══ Starting autonomy loop: \"$goal\" ═══")
        OverlayUI.showWithStop(context, "🤖 OMNIX: Starting task...")
        if (!TTS.isReady()) TTS.initialize(context)

        val isFinancial = isFinancialTask(goal)
        if (isFinancial) {
            TTS.speak("This looks like a financial task. I'll ask for confirmation before any payments.")
            onStep(0, "💰 Financial task detected — confirmation required")
        }

        try {
            if (tryDynamicAppLaunch(goal, context)) {
                onStep(0, "🚀 Launching app...")
                TTS.speak("Opening the app...")
                OverlayUI.updateStatus("Opening app...")
                delay(SCREEN_SETTLE_DELAY_MS)
            }

            for (step in 1..MAX_STEPS) {
                if (OverlayUI.cancelled.get()) {
                    TTS.speak("Task cancelled.")
                    onStep(step, "🛑 Cancelled by user")
                    return LoopResult(false, "Task cancelled by user.", step, history)
                }
                if (System.currentTimeMillis() - startTime > TOTAL_TIMEOUT_MS) {
                    TTS.speak("Task timed out.")
                    return LoopResult(false, "Task timed out.", step, history)
                }

                onStep(step, "🔍 Inspecting screen...")
                OverlayUI.updateStatus("Step $step: Inspecting...")

                val screenDump = if (a11y != null) {
                    val dump = withContext(Dispatchers.Main) { a11y.getCompressedScreenDump() }
                    if (dump.screenText.isBlank()) { delay(1000); continue }
                    dump
                } else {
                    ScreenDump("(no accessibility)", "No screen data — use shell commands instead.", emptyList())
                }

                Log.d(TAG, "Step $step screen (${screenDump.packageName}):\n${screenDump.screenText.take(300)}")

                val prompt = buildPrompt(goal, screenDump, history, context)
                onStep(step, "🧠 Thinking...")
                OverlayUI.updateStatus("Step $step: Thinking...")

                val response = try {
                    GemmaInferenceEngine.generate(buildSystemPrompt(context, a11y != null), prompt)
                } catch (e: Exception) {
                    Log.e(TAG, "Gemma error at step $step: ${e.message}")
                    delay(500)
                    continue
                }

                Log.i(TAG, "Step $step response:\n${response.take(400)}")

                val thought = ResponseParser.extractThought(response)
                val cmd = ResponseParser.extractCommand(response)

                if (cmd.isBlank()) {
                    Log.w(TAG, "Step $step: No CMD — nudging")
                    val nudgeCmd = try {
                        ResponseParser.extractCommand(
                            GemmaInferenceEngine.generate(
                                "Output ONLY one CMD: line. Example: CMD: click_text \"Vicky\"",
                                "Context: ${thought.take(200)}\nTask: $goal\nOutput CMD:"
                            )
                        )
                    } catch (_: Exception) { "" }
                    if (nudgeCmd.isNotBlank()) {
                        onStep(step, ResponseParser.commandToEmoji(nudgeCmd))
                        val result = dispatchCommand(nudgeCmd, uiExec, codeExec, context, isFinancial)
                        history.add(StepRecord(step, thought, nudgeCmd, result))
                        delay(SETTLE_DELAY_MS)
                    } else {
                        history.add(StepRecord(step, thought, "(no cmd)", "parse failed"))
                    }
                    continue
                }

                // DONE?
                if (cmd.startsWith("done", ignoreCase = true)) {
                    val rest = cmd.removePrefix("done").removePrefix("Done").trim()
                    val message = if (rest.startsWith("\"")) {
                        rest.substringAfter("\"").substringBeforeLast("\"").ifBlank { rest }
                    } else rest
                    val finalMsg = message.ifBlank { "Task completed." }
                    history.add(StepRecord(step, thought, cmd, "DONE"))
                    onStep(step, "✅ $finalMsg")
                    TTS.speak(finalMsg)
                    Log.i(TAG, "Loop done at step $step: $finalMsg")
                    trySaveAsSkill(goal, history, context)
                    return LoopResult(true, finalMsg, step, history)
                }

                val narration = ResponseParser.commandToNarration(cmd)
                TTS.speak(narration)
                OverlayUI.updateStatus("Step $step: $narration")
                onStep(step, ResponseParser.commandToEmoji(cmd))

                val execResult = dispatchCommand(cmd, uiExec, codeExec, context, isFinancial)
                history.add(StepRecord(step, thought, cmd, execResult))
                Log.i(TAG, "Step $step result: $execResult")

                if (execResult.startsWith("Error:") || execResult.contains("not found")) {
                    TTS.speak(buildErrorNarration(execResult))
                }

                delay(HumanBehaviorSimulator.interStepDelayMs())
            }

            return LoopResult(false, "Couldn't complete in $MAX_STEPS steps.", MAX_STEPS, history)
        } finally {
            OverlayUI.dismiss()
        }
    }

    // ── Command dispatcher ────────────────────────────────────────────────────

    private suspend fun dispatchCommand(
        cmd: String,
        uiExec: UICommandExecutor,
        codeExec: CodeCommandExecutor,
        context: Context,
        isFinancial: Boolean
    ): String {
        return try {
            val verb = cmd.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return "Empty command"

            if (isFinancial && isPaymentAction(cmd)) {
                val confirmed = withContext(Dispatchers.Main) { showFinancialConfirmation(context, cmd) }
                if (!confirmed) {
                    TTS.speak("Payment cancelled for safety.")
                    return "Blocked: Financial action requires confirmation — user declined"
                }
            }

            when {
                verb == "save_skill" -> "Skill saving noted — will save on completion"
                verb in ResponseParser.uiVerbs -> uiExec.execute(cmd)
                verb in ResponseParser.codeVerbs -> codeExec.execute(cmd)
                else -> {
                    Log.w(TAG, "Unknown verb '$verb', trying as shell: $cmd")
                    codeExec.execute("bash \"$cmd\"")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command exec error: ${e.message}")
            "Error: ${e.message}"
        }
    }

    // ── Prompts ───────────────────────────────────────────────────────────────

    private fun buildSystemPrompt(context: Context, hasA11y: Boolean): String = buildString {
        appendLine("You are OMNIX, an autonomous Android AI agent (Jarvis). Output EXACTLY one command per response.")
        appendLine()
        appendLine("Format:")
        appendLine("THOUGHT: <brief reasoning>")
        appendLine("CMD: <command>")
        appendLine()
        appendLine("=== L1: UI CONTROL (requires accessibility) ===")
        if (hasA11y) {
            appendLine("click \"resource_id\" → tap element by resource ID")
            appendLine("click_text \"text\" → tap element by visible text (BEST for contacts/list items)")
            appendLine("click_desc \"desc\" → tap element by content description")
            appendLine("type \"resource_id\" \"text\" → type into field by ID")
            appendLine("type_focused \"text\" → type into currently focused field")
            appendLine("swipe up/down/left/right → swipe gesture")
            appendLine("back → press back button")
            appendLine("home → press home button")
            appendLine("tap x y → tap at screen coordinates")
            appendLine("wait → wait for screen to load")
        } else {
            appendLine("(Accessibility not available — use shell commands instead)")
        }
        appendLine()
        appendLine("=== L2: CODE EXECUTION ===")
        appendLine("bash \"command\" → run any shell command")
        appendLine("python \"code\" → run Python code (via Termux)")
        appendLine("termux \"command\" → run command in Termux environment")
        appendLine("read_file \"path\" → read file contents")
        appendLine("write_file \"path\" \"content\" → write content to file")
        appendLine("http_get \"url\" → make HTTP GET request")
        appendLine("launch_app \"package_or_name\" → launch app by package or name")
        appendLine()
        appendLine("=== L3: SELF-IMPROVEMENT ===")
        appendLine("save_skill \"name\" \"trigger_regex\" → save current solution as reusable skill")
        appendLine()
        appendLine("=== COMPLETION ===")
        appendLine("done \"message\" → task is complete")
        appendLine()
        appendLine("Rules:")
        appendLine("- For UI: Use click_text for contacts/buttons visible on screen")
        appendLine("- For code: bash is fastest, python for complex logic")
        appendLine("- ALWAYS output the CMD: line")
        appendLine("- No explanations outside THOUGHT/CMD format")
        val skillSummary = DynamicSkillWriter.getSkillSummary()
        if (skillSummary.isNotBlank()) { appendLine(); appendLine(skillSummary) }
    }

    private fun buildPrompt(goal: String, screen: ScreenDump, history: List<StepRecord>, context: Context) = buildString {
        appendLine("TASK: $goal")
        if (history.isNotEmpty()) {
            appendLine("HISTORY:")
            history.takeLast(4).forEach { s -> appendLine("  ${s.step}: ${s.command} → ${s.result.take(80)}") }
        }
        appendLine("SCREEN (${screen.packageName}):")
        screen.screenText.lines().take(35).forEach { appendLine(it) }
        appendLine("What command should I execute next?")
    }

    // ── Financial safety ──────────────────────────────────────────────────────

    private fun isFinancialTask(goal: String): Boolean {
        val lower = goal.lowercase()
        return listOf(
            "pay", "send money", "transfer", "upi", "payment", "wallet",
            "bank", "balance", "rupees", "rs.", "₹", "phonepe", "gpay",
            "paytm", "recharge", "bill", "subscribe", "purchase", "buy"
        ).any { lower.contains(it) }
    }

    private fun isPaymentAction(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return lower.contains("pay") || lower.contains("send") ||
               lower.contains("confirm") || lower.contains("proceed") ||
               lower.contains("submit") || lower.contains("transfer")
    }

    private suspend fun showFinancialConfirmation(context: Context, cmd: String): Boolean = try {
        val result = CompletableDeferred<Boolean>()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val dialog = android.app.AlertDialog.Builder(context)
                    .setTitle(R.string.payment_confirm_title)
                    .setMessage(context.getString(R.string.payment_confirm_message, cmd))
                    .setPositiveButton(context.getString(R.string.action_confirm)) { _, _ -> result.complete(true) }
                    .setNegativeButton(context.getString(R.string.action_cancel)) { _, _ -> result.complete(false) }
                    .setCancelable(false)
                    .create()
                dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
                handler.postDelayed({
                    if (result.isActive) { dialog.dismiss(); result.complete(false) }
                }, 15_000)
            } catch (e: Exception) {
                Log.w(TAG, "Financial dialog failed: ${e.message}")
                result.complete(false)
            }
        }
        result.await()
    } catch (e: Exception) {
        Log.w(TAG, "Financial confirmation error: ${e.message}")
        false
    }

    // ── Dynamic app launch (Step 0 pre-launch) ────────────────────────────────

    private fun tryDynamicAppLaunch(goal: String, context: Context): Boolean {
        val appTarget = extractAppTarget(goal) ?: return false
        val resolved = AppKnowledgeEngine.resolveLaunchableApp(query = goal, appHint = appTarget)
        if (resolved != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Pre-launched: ${resolved.packageName}")
                return true
            }
        }
        val pm = context.packageManager
        val lower = appTarget.lowercase()
        val match = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label == lower || label.contains(lower) || lower.contains(label)
            }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
        return false
    }

    private fun extractAppTarget(goal: String): String? {
        val lower = goal.lowercase()
        val patterns = listOf(
            Regex("""(?:open|launch|start)\s+(.+?)(?:\s+and\s+|\s+to\s+|\s+then\s+|$)"""),
            Regex("""(?:go to|take me to)\s+(.+?)(?:\s+and\s+|$)"""),
            Regex("""(?:in|on|using)\s+(.+?)(?:\s+and\s+|\s+send\s+|\s+search\s+|$)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                return match.groupValues[1].trim().removePrefix("the ").removeSuffix(" app").trim()
            }
        }
        return null
    }

    // ── L3: Auto-save successful solutions as skills ──────────────────────────

    private suspend fun trySaveAsSkill(goal: String, history: List<StepRecord>, context: Context) {
        val scriptable = history.filter { s ->
            s.command.startsWith("bash ") || s.command.startsWith("python ") ||
            s.command.startsWith("termux ") || s.command.startsWith("shell ")
        }
        if (scriptable.isEmpty()) return
        try {
            val meta = DynamicSkillWriter.askAIForSkillMeta(goal) ?: return
            val (name, trigger, desc) = meta
            val script = scriptable.joinToString("\n") {
                it.command.substringAfter("\"").substringBeforeLast("\"")
            }
            DynamicSkillWriter.saveAsSkill(name, desc, trigger, "bash", script, context)
            Log.i(TAG, "Auto-saved skill: $name")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-save skill: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildErrorNarration(error: String): String = when {
        error.contains("not found") -> "Element not found. I'm trying an alternative approach."
        error.contains("not installed") -> "That app isn't installed on this device."
        error.contains("Accessibility") -> "I need accessibility permission to do that."
        error.contains("timed out") -> "The app is taking too long. Let me try again."
        else -> "I hit an issue. Adjusting approach."
    }
}
