package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnix.agent.ai.AppKnowledgeEngine
import com.omnix.agent.executor.ResponseParser.extractArg1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes L2 code/shell commands and dynamic app-launch commands.
 *
 * Supports: bash, shell, python, termux, read_file, write_file, http_get, launch_app.
 *
 * Extracted from AutonomyLoop to keep the main loop focused on ReAct coordination.
 */
class CodeCommandExecutor(private val context: Context) {

    private val TAG = "CodeCommandExecutor"

    suspend fun execute(cmd: String): String {
        val verb = cmd.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return "Empty command"
        return when (verb) {
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
                } else "Invalid write_file format. Use: write_file \"path\" \"content\""
            }
            "http_get" -> {
                val url = extractArg1(cmd, "http_get")
                val result = withContext(Dispatchers.IO) { TermuxBridge.httpGet(url, context) }
                if (result.success) "HTTP: ${result.stdout.take(500)}"
                else "HTTP error: ${result.stderr}"
            }
            "launch_app" -> {
                val target = extractArg1(cmd, "launch_app")
                launchAppDynamic(target)
            }
            else -> "Unknown code command: $cmd"
        }
    }

    /**
     * Launch app by package name or display label.
     * Resolution order:
     *  1. Exact package name (contains ".")
     *  2. AppKnowledgeEngine fuzzy match
     *  3. All-installed-apps label scan
     *  4. `am start` via shell as last resort
     */
    private fun launchAppDynamic(target: String): String {
        val pm = context.packageManager

        // 1. Exact package
        if (target.contains(".")) {
            val intent = pm.getLaunchIntentForPackage(target)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Launched $target"
            }
        }

        // 2. AppKnowledgeEngine
        val resolved = AppKnowledgeEngine.resolveLaunchableApp(query = target, appHint = target)
        if (resolved != null) {
            val intent = pm.getLaunchIntentForPackage(resolved.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Launched ${resolved.name} (${resolved.packageName})"
            }
        }

        // 3. Label scan
        val lower = target.lowercase()
        val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        val match = allApps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            label == lower || label.contains(lower) || lower.contains(label)
        }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Label-matched launch: ${match.packageName}")
                return "Launched ${pm.getApplicationLabel(match)}"
            }
        }

        // 4. Shell fallback
        return try {
            Runtime.getRuntime().exec(
                arrayOf("sh", "-c",
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $target 2>/dev/null")
            )
            "Attempted launch: $target"
        } catch (e: Exception) {
            "Could not find app: $target"
        }
    }
}
