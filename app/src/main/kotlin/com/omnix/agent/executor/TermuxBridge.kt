package com.omnix.agent.executor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * TermuxBridge — L2 Code Execution Layer.
 *
 * Lets the AI write scripts, execute them in Termux, and read back results.
 * Works via two mechanisms:
 *   1. Termux:RUN_COMMAND intent (preferred — returns output via file)
 *   2. Fallback: write script to shared storage, launch Termux with the script
 *
 * The AI can:
 *   - Run bash/python/node/ruby/go commands
 *   - Install packages via pkg/pip/npm
 *   - Read/write files
 *   - Make HTTP requests
 *   - Compile and run code
 *   - Access Termux APIs (termux-notification, termux-toast, etc.)
 *
 * Safety: ALL commands pass through SafeGuard first.
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"
    private const val TERMUX_PKG = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_RESULT_DIR = "/data/data/com.termux/files/home/.omnix"
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean,
        val method: String  // "termux_intent", "direct_shell", "file_bridge"
    )

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Execute a shell command, preferring Termux if available.
     * Falls back to direct Runtime.exec if Termux is not installed.
     */
    suspend fun execute(
        command: String,
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        workingDir: String? = null
    ): ExecResult = withContext(Dispatchers.IO) {

        // Safety check
        val verdict = SafeGuard.checkShellCommand(command)
        if (!verdict.allowed) {
            Log.w(TAG, "SafeGuard blocked: ${verdict.reason}")
            return@withContext ExecResult(
                -1, "", "BLOCKED: ${verdict.reason}", false, "safeguard"
            )
        }

        if (verdict.cautionLevel == SafeGuard.CautionLevel.CAUTION) {
            Log.i(TAG, "Executing with caution: $command")
        }

        // Try Termux first
        if (isTermuxInstalled(context)) {
            return@withContext executeViaTermux(command, context, timeoutMs, workingDir)
        }

        // Fallback to direct shell (limited but works for basic commands)
        return@withContext executeDirectShell(command, timeoutMs)
    }

    /**
     * Execute a Python script via Termux.
     * The AI writes the script content, this method saves it and runs it.
     */
    suspend fun executePython(
        script: String,
        context: Context,
        args: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ExecResult = withContext(Dispatchers.IO) {

        // Safety check on code content
        val codeVerdict = SafeGuard.checkCodeContent(script)
        if (!codeVerdict.allowed) {
            return@withContext ExecResult(
                -1, "", "BLOCKED: ${codeVerdict.reason}", false, "safeguard"
            )
        }

        // Write script to file
        val scriptFile = getScriptFile(context, "omnix_script.py")
        scriptFile.writeText(script)

        val argsStr = args.joinToString(" ") { "\"$it\"" }
        val cmd = "python3 ${scriptFile.absolutePath} $argsStr"

        execute(cmd, context, timeoutMs)
    }

    /**
     * Execute a Node.js script via Termux.
     */
    suspend fun executeNode(
        script: String,
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ExecResult = withContext(Dispatchers.IO) {
        val codeVerdict = SafeGuard.checkCodeContent(script)
        if (!codeVerdict.allowed) {
            return@withContext ExecResult(
                -1, "", "BLOCKED: ${codeVerdict.reason}", false, "safeguard"
            )
        }

        val scriptFile = getScriptFile(context, "omnix_script.js")
        scriptFile.writeText(script)

        execute("node ${scriptFile.absolutePath}", context, timeoutMs)
    }

    /**
     * Write a file to Termux-accessible storage.
     */
    suspend fun writeFile(
        path: String,
        content: String,
        context: Context
    ): ExecResult = withContext(Dispatchers.IO) {

        val verdict = SafeGuard.checkFileWrite(path)
        if (!verdict.allowed) {
            return@withContext ExecResult(
                -1, "", "BLOCKED: ${verdict.reason}", false, "safeguard"
            )
        }

        try {
            File(path).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
            ExecResult(0, "Written ${content.length} bytes to $path", "", true, "direct_file")
        } catch (e: Exception) {
            // Try via shell
            val escaped = content.replace("'", "'\\''")
            execute("echo '$escaped' > '$path'", context)
        }
    }

    /**
     * Read a file from the filesystem.
     */
    suspend fun readFile(
        path: String,
        context: Context,
        maxChars: Int = 4000
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val f = File(path)
            if (!f.exists()) {
                return@withContext ExecResult(-1, "", "File not found: $path", false, "direct_file")
            }
            val content = f.readText().take(maxChars)
            ExecResult(0, content, "", true, "direct_file")
        } catch (e: Exception) {
            // Try via shell
            execute("head -c $maxChars '$path'", context)
        }
    }

    /**
     * Make an HTTP GET request via curl (through Termux or shell).
     */
    suspend fun httpGet(
        url: String,
        context: Context,
        timeoutMs: Long = 15_000L
    ): ExecResult {
        return execute("curl -s -m 10 '$url'", context, timeoutMs)
    }

    /**
     * Install a package in Termux.
     */
    suspend fun installPackage(
        packageName: String,
        manager: String = "pkg",  // "pkg", "pip", "npm"
        context: Context
    ): ExecResult {
        val cmd = when (manager) {
            "pip" -> "pip install $packageName"
            "npm" -> "npm install -g $packageName"
            else -> "pkg install -y $packageName"
        }
        return execute(cmd, context, timeoutMs = 60_000L)
    }

    /**
     * Check if Termux is installed and functional.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List available tools in Termux (python, node, etc.)
     */
    suspend fun listAvailableTools(context: Context): ExecResult {
        return execute(
            "echo 'bash:'\$(which bash 2>/dev/null && echo yes || echo no);" +
            "echo 'python:'\$(which python3 2>/dev/null && echo yes || echo no);" +
            "echo 'node:'\$(which node 2>/dev/null && echo yes || echo no);" +
            "echo 'curl:'\$(which curl 2>/dev/null && echo yes || echo no);" +
            "echo 'git:'\$(which git 2>/dev/null && echo yes || echo no);" +
            "echo 'gcc:'\$(which gcc 2>/dev/null && echo yes || echo no)",
            context
        )
    }

    // ── Internal execution methods ──────────────────────────────────────────

    private suspend fun executeViaTermux(
        command: String,
        context: Context,
        timeoutMs: Long,
        workingDir: String?
    ): ExecResult {
        // Use file-based IPC: write command, read result
        val resultFile = getResultFile(context)
        val errorFile = getErrorFile(context)
        val exitCodeFile = getExitCodeFile(context)

        // Clean up previous results
        resultFile.delete()
        errorFile.delete()
        exitCodeFile.delete()

        // Build the wrapper command that captures output
        val wrappedCmd = buildString {
            append("mkdir -p ${resultFile.parentFile?.absolutePath}; ")
            if (workingDir != null) append("cd '$workingDir'; ")
            append("($command) > '${resultFile.absolutePath}' 2> '${errorFile.absolutePath}'; ")
            append("echo \$? > '${exitCodeFile.absolutePath}'")
        }

        try {
            // Send command to Termux via intent
            val intent = Intent().apply {
                setClassName(TERMUX_PKG, TERMUX_RUN_COMMAND_SERVICE)
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrappedCmd))
                if (workingDir != null) {
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                }
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startForegroundService(intent)
            Log.i(TAG, "Sent command to Termux: ${command.take(100)}")
        } catch (e: Exception) {
            Log.w(TAG, "Termux intent failed: ${e.message}, falling back to direct shell")
            return executeDirectShell(command, timeoutMs)
        }

        // Poll for result file
        val result = withTimeoutOrNull(timeoutMs) {
            while (!exitCodeFile.exists()) {
                delay(200)
            }
            delay(100) // small delay for file writes to complete

            val stdout = if (resultFile.exists()) resultFile.readText().take(4000) else ""
            val stderr = if (errorFile.exists()) errorFile.readText().take(2000) else ""
            val exitCode = if (exitCodeFile.exists()) {
                exitCodeFile.readText().trim().toIntOrNull() ?: -1
            } else -1

            ExecResult(exitCode, stdout, stderr, exitCode == 0, "termux_intent")
        }

        return result ?: ExecResult(
            -1, "", "Termux command timed out after ${timeoutMs / 1000}s", false, "termux_timeout"
        )
    }

    private suspend fun executeDirectShell(
        command: String,
        timeoutMs: Long
    ): ExecResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val stdout = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()

                Log.d(TAG, "Direct shell exit=$exitCode stdout=${stdout.take(200)}")
                ExecResult(exitCode, stdout.take(4000), stderr.take(2000), exitCode == 0, "direct_shell")
            } catch (e: Exception) {
                Log.e(TAG, "Direct shell error: ${e.message}")
                ExecResult(-1, "", "Error: ${e.message}", false, "direct_shell")
            }
        } ?: ExecResult(-1, "", "Command timed out", false, "direct_shell_timeout")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun getScriptDir(context: Context): File {
        val dir = File(context.filesDir, "omnix_scripts")
        dir.mkdirs()
        return dir
    }

    private fun getScriptFile(context: Context, name: String): File {
        return File(getScriptDir(context), name)
    }

    private fun getResultFile(context: Context): File {
        return File(getScriptDir(context), ".omnix_result")
    }

    private fun getErrorFile(context: Context): File {
        return File(getScriptDir(context), ".omnix_error")
    }

    private fun getExitCodeFile(context: Context): File {
        return File(getScriptDir(context), ".omnix_exitcode")
    }
}
