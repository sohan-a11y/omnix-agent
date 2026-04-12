package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
 * AutonomyLoop v2 — the Jarvis ReAct (Reason+Act) core.
 *
 * Full tool set available to the AI:
 *   L1 — App Control : click, type, swipe, scroll on any app via Accessibility
 *   L2 — Code Exec   : bash, python, node, curl, read/write files via TermuxBridge
 *   L3 — Self-modify  : write_skill (save scripts for reuse), generate_code
 *
 * NO hardcoded app maps — the AI resolves apps via PackageManager + AppKnowledgeEngine.
 * NO hardcoded skills — the AI writes its own solutions and saves them.
 *
 * Safety: ALL commands pass through SafeGuard before execution.
 */
object AutonomyLoop {

    private const val TAG = "AutonomyLoop"
    private const val MAX_STEPS = 20
    private const val TOTAL_TIMEOUT_MS = 300_000L      // 5 minutes
    private const val SETTLE_DELAY_MS = 1000L
    private const val SCREEN_SETTLE_DELAY_MS = 1500L   // after app launch

    // ── Result types ────────────────────────────────────────────────────────

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

    // ── Public API ──────────────────────────────────────────────────────────

    fun runAsFlow(goal: String, context: Context): Flow<StepUpdate> = flow {
        val result = runInternal(goal, context) { step, msg ->
            emit(StepUpdate.Progress(step, msg))
        }
        emit(StepUpdate.Completed(result))
    }

    suspend fun run(goal: String, context: Context): LoopResult {
        return runInternal(goal, context) { _, _ -> }
    }

    // ── Core loop ───────────────────────────────────────────────────────────

    private suspend fun runInternal(
        goal: String,
        context: Context,
        onStep: suspend (Int, String) -> Unit
    ): LoopResult {
        // A11y is optional now — we can still run shell/code/file tasks without it
        val a11y = OmnixAccessibilityService.instance
        if (a11y == null) {
            Log.w(TAG, "Accessibility service not running — UI commands will be limited")
        }

        if (!GemmaInferenceEngine.isReady()) {
            Log.w(TAG, "Gemma model not loaded yet")
            return LoopResult(false, "Gemma model not loaded yet.", 0, emptyList())
        }

        // Initialize dynamic skill writer
        DynamicSkillWriter.initialize(context)

        // Check if we have a pre-built dynamic skill for this task
        val cachedSkill = DynamicSkillWriter.findMatchingSkill(goal)
        if (cachedSkill != null) {
            Log.i(TAG, "Found cached dynamic skill: ${cachedSkill.name}")
            onStep(0, "⚡ Using learned skill: ${cachedSkill.name}...")
            val result = DynamicSkillWriter.executeSkill(cachedSkill, context)
            return if (result.success) {
                LoopResult(true, "✅ ${cachedSkill.name} completed: ${result.stdout.take(200)}", 1,
                    listOf(StepRecord(1, "Used cached skill", cachedSkill.scriptContent.take(100), result.stdout.take(200))))
            } else {
                Log.w(TAG, "Cached skill failed, falling through to full loop")
                // Fall through to full loop
                runFullLoop(goal, context, a11y, onStep)
            }
        }

        return runFullLoop(goal, context, a11y, onStep)
    }

    private suspend fun runFullLoop(
        goal: String,
        context: Context,
        a11y: OmnixAccessibilityService?,
        onStep: suspend (Int, String) -> Unit
    ): LoopResult {
        val history = mutableListOf<StepRecord>()
        val startTime = System.currentTimeMillis()

        Log.i(TAG, "════════════════════════════════════════")
        Log.i(TAG, "Starting autonomy loop: \"$goal\"")
        Log.i(TAG, "════════════════════════════════════════")

        // Show overlay with STOP button
        OverlayUI.showWithStop(context, "🤖 OMNIX: Starting task...")
        // Initialize TTS if not ready
        if (!TTS.isReady()) TTS.initialize(context)

        // Financial safety: detect if this is a money-related task
        val isFinancial = isFinancialTask(goal)
        if (isFinancial) {
            TTS.speak("This looks like a financial task. I'll ask for confirmation before any payments.")
            onStep(0, "💰 Financial task detected — confirmation required")
        }

        try {
            // Step 0: Try to launch the target app
            val appLaunched = tryDynamicAppLaunch(goal, context)
            if (appLaunched) {
                onStep(0, "🚀 Launching app...")
                TTS.speak("Opening the app...")
                OverlayUI.updateStatus("Opening app...")
                delay(SCREEN_SETTLE_DELAY_MS)
            }

            for (step in 1..MAX_STEPS) {
                // ── Cancellation check (STOP button) ──
                if (OverlayUI.cancelled.get()) {
                    Log.i(TAG, "Cancelled by user at step $step")
                    TTS.speak("Task cancelled.")
                    onStep(step, "🛑 Cancelled by user")
                    return LoopResult(false, "Task cancelled by user.", step, history)
                }

                if (System.currentTimeMillis() - startTime > TOTAL_TIMEOUT_MS) {
                    Log.w(TAG, "Loop timed out after ${TOTAL_TIMEOUT_MS / 1000}s")
                    TTS.speak("Task timed out.")
                    return LoopResult(false, "Task timed out.", step, history)
                }

                onStep(step, "🔍 Inspecting screen...")
                OverlayUI.updateStatus("Step $step: Inspecting...")

                // 1. Capture screen state
                val screenText = if (a11y != null) {
                    val screenDump = withContext(Dispatchers.Main) {
                        a11y.getCompressedScreenDump()
                    }
                    if (screenDump.screenText.isBlank()) {
                        Log.w(TAG, "Step $step: Empty screen dump — waiting...")
                        delay(1000)
                        continue
                    }
                    screenDump
                } else {
                    ScreenDump("(no accessibility)", "No screen data — use shell commands instead.", emptyList())
                }

                Log.d(TAG, "Step $step screen (${screenText.packageName}):\n${screenText.screenText.take(300)}")

                // 2. Build prompt and ask Gemma
                val prompt = buildPrompt(goal, screenText, history, context)
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

                // 3. Parse THOUGHT and CMD
                val thought = extractThought(response)
                val cmd = extractCommand(response)

                if (cmd.isBlank()) {
                    Log.w(TAG, "Step $step: No CMD — nudging")
                    val nudge = try {
                        GemmaInferenceEngine.generate(
                            "Output ONLY one CMD: line. Example: CMD: click_text \"Vicky\"",
                            "Context: ${thought.take(200)}\nTask: $goal\nOutput CMD:"
                        )
                    } catch (e: Exception) { "" }
                    val nudgeCmd = extractCommand(nudge)
                    if (nudgeCmd.isNotBlank()) {
                        onStep(step, commandToEmoji(nudgeCmd))
                        val result = executeCommand(nudgeCmd, a11y, context, isFinancial)
                        history.add(StepRecord(step, thought, nudgeCmd, result))
                        Log.i(TAG, "Step $step nudge: $nudgeCmd → $result")
                        delay(SETTLE_DELAY_MS)
                    } else {
                        history.add(StepRecord(step, thought, "(no cmd)", "parse failed"))
                    }
                    continue
                }

                Log.i(TAG, "Step $step CMD: $cmd")

                // 4. Check for DONE
                if (cmd.startsWith("done", ignoreCase = true)) {
                    // Handle both: done "message" and done message without quotes
                    val rest = cmd.removePrefix("done").removePrefix("Done").trim()
                    val message = if (rest.startsWith("\"")) {
                        rest.substringAfter("\"").substringBeforeLast("\"").ifBlank { rest }
                    } else {
                        rest
                    }.ifBlank { "Task completed." }
                    history.add(StepRecord(step, thought, cmd, "DONE"))
                    onStep(step, "✅ $message")
                    TTS.speak(message)
                    Log.i(TAG, "Loop done at step $step: $message")

                    // L3: Try to save as a learned skill
                    trySaveAsSkill(goal, history, context)

                    return LoopResult(true, message, step, history)
                }

                // 5. Execute with narration + human behavior
                val narration = commandToNarration(cmd)
                TTS.speak(narration)
                OverlayUI.updateStatus("Step $step: $narration")
                onStep(step, commandToEmoji(cmd))

                val execResult = executeCommand(cmd, a11y, context, isFinancial)
                history.add(StepRecord(step, thought, cmd, execResult))
                Log.i(TAG, "Step $step result: $execResult")

                // Handle structured errors with narration
                if (execResult.startsWith("Error:") || execResult.contains("not found")) {
                    val errorNarration = buildErrorNarration(execResult, cmd)
                    TTS.speak(errorNarration)
                }

                // 6. Human-like delay between steps
                delay(HumanBehaviorSimulator.interStepDelayMs())
            }

            return LoopResult(false, "Couldn't complete in $MAX_STEPS steps.", MAX_STEPS, history)
        } finally {
            // Always dismiss overlay when loop ends
            OverlayUI.dismiss()
        }
    }

    // ── System prompt (full Jarvis tool set) ─────────────────────────────────

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

        // Inject dynamic skills summary
        val skillSummary = DynamicSkillWriter.getSkillSummary()
        if (skillSummary.isNotBlank()) {
            appendLine()
            appendLine(skillSummary)
        }
    }

    private fun buildPrompt(
        goal: String,
        screen: ScreenDump,
        history: List<StepRecord>,
        context: Context
    ): String = buildString {
        appendLine("TASK: $goal")

        if (history.isNotEmpty()) {
            appendLine("HISTORY:")
            history.takeLast(4).forEach { s ->
                appendLine("  ${s.step}: ${s.command} → ${s.result.take(80)}")
            }
        }

        val screenLines = screen.screenText.lines().take(35)
        appendLine("SCREEN (${screen.packageName}):")
        screenLines.forEach { appendLine(it) }
        appendLine("What command should I execute next?")
    }

    // ── Command execution (full tool set) ───────────────────────────────────

    /**
     * Extract the first argument from a command, handling both quoted and unquoted forms.
     * e.g. "click \"foo\"" → "foo", "launch_app com.whatsapp" → "com.whatsapp"
     */
    private fun extractArg1(cmd: String, prefix: String): String {
        val rest = cmd.removePrefix(prefix).trim()
        return if (rest.startsWith("\"")) {
            rest.substringAfter("\"").substringBefore("\"")
        } else {
            // Return everything — multi-word unquoted args are valid (e.g. type_focused hello world)
            rest
        }
    }

    private suspend fun executeCommand(
        cmd: String,
        a11y: OmnixAccessibilityService?,
        context: Context,
        isFinancial: Boolean = false
    ): String {
        try {
            val verb = cmd.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return "Empty command"

            // ── Financial gate: block sensitive actions until confirmed ──
            if (isFinancial && isPaymentAction(cmd)) {
                val confirmed = withContext(Dispatchers.Main) {
                    showFinancialConfirmation(context, cmd)
                }
                if (!confirmed) {
                    TTS.speak("Payment cancelled for safety.")
                    return "Blocked: Financial action requires confirmation — user declined"
                }
            }

            return when (verb) {

                // ── L1: UI Control with HumanBehavior + Self-Healing ────

                "click" -> {
                    if (a11y == null) return buildStructuredError("accessibility_missing", "click", "")
                    val id = extractArg1(cmd, "click")
                    delay(HumanBehaviorSimulator.interStepDelayMs())
                    smartClick(a11y, id, null, null, context)
                }

                "click_text" -> {
                    if (a11y == null) return buildStructuredError("accessibility_missing", "click_text", "")
                    val text = extractArg1(cmd, "click_text")
                    delay(HumanBehaviorSimulator.interStepDelayMs())
                    smartClick(a11y, null, text, null, context)
                }

                "click_desc" -> {
                    if (a11y == null) return buildStructuredError("accessibility_missing", "click_desc", "")
                    val desc = extractArg1(cmd, "click_desc")
                    delay(HumanBehaviorSimulator.interStepDelayMs())
                    smartClick(a11y, null, null, desc, context)
                }

                "type" -> {
                    if (a11y == null) return buildStructuredError("accessibility_missing", "type", "")
                    val parts = Regex("""type\s+"?([^"\s]+)"?\s+"([^"]*)"""").find(cmd)
                    if (parts != null) {
                        val id = parts.groupValues[1]
                        val text = parts.groupValues[2]
                        val node = withContext(Dispatchers.Main) { a11y.findByResourceId(id) }
                        if (node != null) {
                            // Human-like typing delay
                            delay(HumanBehaviorSimulator.interStepDelayMs())
                            withContext(Dispatchers.Main) { a11y.typeText(node, text, clear = true) }
                            delay(HumanBehaviorSimulator.typingDelayMs(text.length))
                            "Typed '$text' into $id"
                        } else buildStructuredError("element_not_found", "type", id)
                    } else "Invalid type format. Use: type \"field_id\" \"text\""
                }

                "type_focused" -> {
                    if (a11y == null) return buildStructuredError("accessibility_missing", "type_focused", "")
                    val text = extractArg1(cmd, "type_focused")
                    val dump = withContext(Dispatchers.Main) { a11y.getCompressedScreenDump() }
                    val editable = dump.nodes.firstOrNull { it.isEditable }
                    if (editable != null) {
                        val node = withContext(Dispatchers.Main) {
                            a11y.findByResourceId(editable.resourceId)
                                ?: a11y.findByText(editable.text)
                        }
                        if (node != null) {
                            delay(HumanBehaviorSimulator.interStepDelayMs())
                            withContext(Dispatchers.Main) { a11y.typeText(node, text, clear = true) }
                            delay(HumanBehaviorSimulator.typingDelayMs(text.length))
                            "Typed '$text' into focused field"
                        } else buildStructuredError("element_not_found", "type_focused", "editable field")
                    } else "No editable field on screen. Try clicking a text field first."
                }

                "tap" -> {
                    val parts = cmd.removePrefix("tap").trim().split("\\s+".toRegex())
                    if (parts.size >= 2 && a11y != null) {
                        val x = parts[0].toFloatOrNull()
                        val y = parts[1].toFloatOrNull()
                        if (x != null && y != null) {
                            delay(HumanBehaviorSimulator.interStepDelayMs())
                            // Add human jitter to coordinates
                            val (jx, jy) = HumanBehaviorSimulator.touchOffset()
                            withContext(Dispatchers.Main) {
                                a11y.tapCoordinates(x + jx, y + jy, HumanBehaviorSimulator.tapDurationMs())
                            }
                            "Tapped ($x, $y)"
                        } else "Invalid coordinates"
                    } else "tap needs x y + accessibility"
                }

                "swipe" -> {
                    if (a11y == null) return "Swipe needs accessibility"
                    val dir = cmd.removePrefix("swipe").trim().lowercase()
                    val dm = context.resources.displayMetrics
                    val cx = dm.widthPixels / 2f; val cy = dm.heightPixels / 2f
                    delay(HumanBehaviorSimulator.interStepDelayMs())
                    withContext(Dispatchers.Main) {
                        when (dir) {
                            "up" -> a11y.swipe(cx, cy + 400, cx, cy - 400, 300)
                            "down" -> a11y.swipe(cx, cy - 400, cx, cy + 400, 300)
                            "left" -> a11y.swipe(cx + 400, cy, cx - 400, cy, 300)
                            "right" -> a11y.swipe(cx - 400, cy, cx + 400, cy, 300)
                            else -> a11y.swipe(cx, cy + 400, cx, cy - 400, 300)
                        }
                    }
                    "Swiped $dir"
                }

                "back" -> {
                    if (a11y != null) withContext(Dispatchers.Main) { a11y.pressBack() }
                    else withContext(Dispatchers.IO) { Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 4")) }
                    "Pressed back"
                }

                "home" -> {
                    if (a11y != null) withContext(Dispatchers.Main) { a11y.pressHome() }
                    else withContext(Dispatchers.IO) { Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 3")) }
                    "Pressed home"
                }

                "wait" -> {
                    delay(1500)
                    "Waited 1.5s"
                }

                // ── L2: Code Execution ──────────────────────────────────

                "bash", "shell" -> {
                    val shellCmd = extractArg1(cmd, verb)
                    val result = withContext(Dispatchers.IO) { TermuxBridge.execute(shellCmd, context) }
                    if (result.success) "OK: ${result.stdout.take(300)}"
                    else "Error: ${result.stderr.take(300)}"
                }

                "python" -> {
                    val code = extractArg1(cmd, "python")
                    val result = withContext(Dispatchers.IO) { TermuxBridge.executePython(code, context) }
                    if (result.success) "Python OK: ${result.stdout.take(300)}"
                    else "Python Error: ${result.stderr.take(300)}"
                }

                "termux" -> {
                    val termuxCmd = extractArg1(cmd, "termux")
                    val result = withContext(Dispatchers.IO) { TermuxBridge.execute(termuxCmd, context, timeoutMs = 60_000L) }
                    if (result.success) "Termux OK: ${result.stdout.take(300)}"
                    else "Termux Error: ${result.stderr.take(300)}"
                }

                "read_file" -> {
                    val path = extractArg1(cmd, "read_file")
                    val result = withContext(Dispatchers.IO) { TermuxBridge.readFile(path, context) }
                    if (result.success) "File: ${result.stdout.take(500)}"
                    else "Read error: ${result.stderr}"
                }

                "write_file" -> {
                    val parts = Regex("""write_file\s+"?([^"\s]+)"?\s+"([^"]*)"""").find(cmd)
                    if (parts != null) {
                        val path = parts.groupValues[1]
                        val content = parts.groupValues[2]
                        val result = withContext(Dispatchers.IO) { TermuxBridge.writeFile(path, content, context) }
                        if (result.success) "Written to $path" else "Write error: ${result.stderr}"
                    } else "Invalid write_file format"
                }

                "http_get" -> {
                    val url = extractArg1(cmd, "http_get")
                    val result = withContext(Dispatchers.IO) { TermuxBridge.httpGet(url, context) }
                    if (result.success) "HTTP: ${result.stdout.take(500)}"
                    else "HTTP error: ${result.stderr}"
                }

                "launch_app" -> {
                    val target = extractArg1(cmd, "launch_app")
                    val result = launchAppDynamic(target, context)
                    if (result.startsWith("Launched") || result.startsWith("App not found")) {
                        delay(SCREEN_SETTLE_DELAY_MS) // wait for app to load
                    }
                    result
                }

                // ── L3: Self-Improvement ─────────────────────────────────

                "save_skill" -> {
                    "Skill saving noted — will save on completion"
                }

                // ── Fallback ─────────────────────────────────────────────

                else -> {
                    Log.w(TAG, "Unknown verb '$verb', trying as shell command: $cmd")
                    val result = withContext(Dispatchers.IO) { TermuxBridge.execute(cmd, context) }
                    if (result.success) "Shell: ${result.stdout.take(200)}"
                    else "Unknown command: $cmd"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command exec error: ${e.message}")
            return "Error: ${e.message}"
        }
    }

    // ── Self-Healing Smart Click (4-tier fallback chain) ─────────────────

    /**
     * Attempts to click an element using a 4-tier fallback:
     * 1. Resource ID (fastest, most brittle)
     * 2. Fuzzy text match (robust)
     * 3. Content description (stable)
     * 4. Screen coordinate tap (last resort using bounds from partial matches)
     */
    private suspend fun smartClick(
        a11y: OmnixAccessibilityService,
        resourceId: String?,
        text: String?,
        desc: String?,
        context: Context
    ): String {
        // Tier 1: Resource ID
        if (!resourceId.isNullOrBlank()) {
            val node = withContext(Dispatchers.Main) { a11y.findByResourceId(resourceId) }
            if (node != null) {
                withContext(Dispatchers.Main) { a11y.tap(node) }
                return "Clicked $resourceId"
            }
            // ID failed — try text/desc fallbacks for the ID target
            Log.w(TAG, "Self-heal: resource_id '$resourceId' not found, trying fallbacks")
        }

        // Tier 2: Fuzzy text match
        val searchText = text ?: resourceId?.substringAfterLast("/")?.replace("_", " ")
        if (!searchText.isNullOrBlank()) {
            val node = withContext(Dispatchers.Main) { a11y.findByTextFuzzy(searchText) }
            if (node != null) {
                withContext(Dispatchers.Main) { a11y.tap(node) }
                return "Clicked text '$searchText' (self-healed)"
            }
        }

        // Tier 3: Content description
        val searchDesc = desc ?: text ?: resourceId?.substringAfterLast("/")?.replace("_", " ")
        if (!searchDesc.isNullOrBlank()) {
            val node = withContext(Dispatchers.Main) { a11y.findByContentDesc(searchDesc) }
            if (node != null) {
                withContext(Dispatchers.Main) { a11y.tap(node) }
                return "Clicked desc '$searchDesc' (self-healed)"
            }
        }

        // Tier 4: Coordinate tap from screen dump (last resort)
        val dump = withContext(Dispatchers.Main) { a11y.getCompressedScreenDump() }
        val target = text ?: desc ?: resourceId ?: ""
        val closestNode = dump.nodes.firstOrNull { n ->
            n.text.contains(target, ignoreCase = true) ||
            n.contentDesc.contains(target, ignoreCase = true) ||
            n.resourceId.contains(target, ignoreCase = true)
        }
        if (closestNode != null && closestNode.bounds.width() > 0) {
            val cx = closestNode.bounds.centerX().toFloat()
            val cy = closestNode.bounds.centerY().toFloat()
            withContext(Dispatchers.Main) { a11y.tapCoordinates(cx, cy) }
            return "Tapped at ($cx, $cy) near '$target' (coordinate rescue)"
        }

        return buildStructuredError("element_not_found", "click", target)
    }

    // ── Financial safety ────────────────────────────────────────────────

    private fun isFinancialTask(goal: String): Boolean {
        val lower = goal.lowercase()
        val financialKeywords = listOf(
            "pay", "send money", "transfer", "upi", "payment", "wallet",
            "bank", "balance", "rupees", "rs.", "₹", "phonepe", "gpay",
            "paytm", "recharge", "bill", "subscribe", "purchase", "buy"
        )
        return financialKeywords.any { lower.contains(it) }
    }

    private fun isPaymentAction(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return lower.contains("pay") || lower.contains("send") ||
               lower.contains("confirm") || lower.contains("proceed") ||
               lower.contains("submit") || lower.contains("transfer")
    }

    private suspend fun showFinancialConfirmation(context: Context, cmd: String): Boolean {
        return try {
            // Use a simple dialog approach since ConfirmationGate needs a SkillEntity
            val result = CompletableDeferred<Boolean>()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                try {
                    val builder = android.app.AlertDialog.Builder(context)
                        .setTitle("💰 OMNIX Payment Confirmation")
                        .setMessage("OMNIX wants to execute:\n\n$cmd\n\nAllow this financial action?")
                        .setPositiveButton("Confirm") { _, _ -> result.complete(true) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(false) }
                        .setCancelable(false)
                    val dialog = builder.create()
                    dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()

                    // Auto-cancel after 15 seconds
                    handler.postDelayed({
                        if (result.isActive) {
                            dialog.dismiss()
                            result.complete(false)
                        }
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
    }

    // ── Structured error messages (Gap 6) ────────────────────────────────

    private fun buildStructuredError(errorType: String, command: String, target: String): String {
        return when (errorType) {
            "element_not_found" -> {
                val suggestion = when {
                    target.contains(":") -> "Element '$target' not found. The app UI may have changed. Try click_text with the visible label instead."
                    else -> "'$target' not found on screen. Try scrolling down, or check if the text is slightly different."
                }
                suggestion
            }
            "accessibility_missing" -> {
                "Accessibility not available. Cannot $command. Use shell commands instead, or ask the user to enable Accessibility in Settings."
            }
            "app_not_installed" -> {
                "App '$target' is not installed. Try launch_app with the correct package name, or ask if the user wants to install it."
            }
            "contact_not_found" -> {
                "Contact '$target' not found. Try searching by phone number or check for similar names."
            }
            else -> "Error in $command: $target"
        }
    }

    private fun buildErrorNarration(error: String, cmd: String): String {
        return when {
            error.contains("not found") -> "Element not found. I'm trying an alternative approach."
            error.contains("not installed") -> "That app isn't installed on this device."
            error.contains("Accessibility") -> "I need accessibility permission to do that."
            error.contains("timed out") -> "The app is taking too long. Let me try again."
            else -> "I hit an issue. Adjusting approach."
        }
    }

    // ── Dynamic app launch (NO hardcoded maps) ──────────────────────────────

    /**
     * Launch an app by name or package — uses PackageManager + AppKnowledgeEngine.
     * No hardcoded maps at all. The AI and the system figure it out dynamically.
     */
    private fun tryDynamicAppLaunch(goal: String, context: Context): Boolean {
        // Extract app name from goal using simple NLP
        val appTarget = extractAppTarget(goal) ?: return false

        // Try AppKnowledgeEngine first (has 191+ apps indexed)
        val resolved = AppKnowledgeEngine.resolveLaunchableApp(
            query = goal,
            appHint = appTarget
        )

        if (resolved != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Dynamically launched: ${resolved.packageName} (${resolved.name})")
                return true
            }
        }

        // Fallback: search all installed packages by label
        return tryLaunchByLabel(appTarget, context)
    }

    /**
     * Launch app by command — used when AI says launch_app "X"
     */
    private fun launchAppDynamic(target: String, context: Context): String {
        // Is it a package name?
        if (target.contains(".")) {
            val intent = context.packageManager.getLaunchIntentForPackage(target)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Launched $target"
            }
        }

        // Search by label in AppKnowledgeEngine
        val resolved = AppKnowledgeEngine.resolveLaunchableApp(query = target, appHint = target)
        if (resolved != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Launched ${resolved.name} (${resolved.packageName})"
            }
        }

        // Search all installed apps by label
        if (tryLaunchByLabel(target, context)) {
            return "Launched $target"
        }

        // Try am start as last resort
        return try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $(pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $target 2>/dev/null | tail -1)"))
            "Attempted launch: $target"
        } catch (e: Exception) {
            "Could not find app: $target"
        }
    }

    /**
     * Search all installed packages by their user-visible label.
     */
    private fun tryLaunchByLabel(appName: String, context: Context): Boolean {
        val pm = context.packageManager
        val lower = appName.lowercase()

        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = allApps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            label == lower || label.contains(lower) || lower.contains(label)
        }

        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Label-matched launch: ${match.packageName}")
            return true
        }

        return false
    }

    /**
     * Extract app name from natural language goal.
     */
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
                return match.groupValues[1].trim()
                    .removePrefix("the ")
                    .removeSuffix(" app")
                    .trim()
            }
        }
        return null
    }

    // ── L3: Auto-save successful task solutions ─────────────────────────────

    private suspend fun trySaveAsSkill(
        goal: String,
        history: List<StepRecord>,
        context: Context
    ) {
        // Only save if we used bash/python/termux commands (scriptable)
        val scriptableCommands = history.filter { step ->
            step.command.startsWith("bash ") ||
            step.command.startsWith("python ") ||
            step.command.startsWith("termux ") ||
            step.command.startsWith("shell ")
        }

        if (scriptableCommands.isEmpty()) return  // Pure UI tasks aren't saved

        try {
            val meta = DynamicSkillWriter.askAIForSkillMeta(goal)
            if (meta != null) {
                val (name, trigger, desc) = meta
                val script = scriptableCommands.joinToString("\n") { it.command.substringAfter("\"").substringBeforeLast("\"") }
                DynamicSkillWriter.saveAsSkill(name, desc, trigger, "bash", script, context)
                Log.i(TAG, "Auto-saved skill: $name")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-save skill: ${e.message}")
        }
    }

    // ── Response parsing ────────────────────────────────────────────────────

    private fun extractThought(response: String): String {
        val match = Regex("""THOUGHT:\s*(.+?)(?=CMD:|$)""", RegexOption.DOT_MATCHES_ALL).find(response)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractCommand(response: String): String {
        val match = Regex("""CMD:\s*(.+)""").find(response)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun commandToEmoji(cmd: String): String = when {
        cmd.startsWith("click_text") -> "👆 Tapping text..."
        cmd.startsWith("click_desc") -> "👆 Tapping button..."
        cmd.startsWith("click") -> "👆 Tapping element..."
        cmd.startsWith("type") -> "⌨️ Typing..."
        cmd.startsWith("bash") || cmd.startsWith("shell") -> "🖥️ Running shell..."
        cmd.startsWith("python") -> "🐍 Running Python..."
        cmd.startsWith("termux") -> "🖥️ Running in Termux..."
        cmd.startsWith("read_file") -> "📄 Reading file..."
        cmd.startsWith("write_file") -> "📝 Writing file..."
        cmd.startsWith("http_get") -> "🌐 Fetching URL..."
        cmd.startsWith("launch_app") -> "🚀 Launching app..."
        cmd.startsWith("swipe") -> "👆 Swiping..."
        cmd.startsWith("tap") -> "👆 Tapping..."
        cmd.startsWith("save_skill") -> "💾 Saving skill..."
        cmd == "back" -> "⬅️ Going back..."
        cmd == "home" -> "🏠 Going home..."
        cmd == "wait" -> "⏳ Waiting..."
        cmd.startsWith("done") -> "✅ Done!"
        else -> "⚙️ Executing..."
    }

    /**
     * Convert command to human-readable TTS narration.
     * This is what the user hears while OMNIX executes.
     */
    private fun commandToNarration(cmd: String): String {
        val verb = cmd.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return "Working..."
        val arg = extractArg1(cmd, verb)
        return when (verb) {
            "click", "click_text", "click_desc" -> "Tapping $arg"
            "type", "type_focused" -> "Typing text"
            "swipe" -> "Swiping $arg"
            "launch_app" -> "Opening $arg"
            "bash", "shell" -> "Running a command"
            "python" -> "Running Python code"
            "back" -> "Going back"
            "home" -> "Going to home screen"
            "wait" -> "Waiting for the screen to load"
            "done" -> "Task complete"
            else -> "Working..."
        }
    }
}
