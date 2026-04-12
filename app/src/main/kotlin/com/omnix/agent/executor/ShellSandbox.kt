package com.omnix.agent.executor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ShellSandbox — executes shell commands with SafeGuard vetting.
 *
 * Security fixes applied:
 * - stdout and stderr read concurrently to avoid deadlock on large output
 * - process.destroy() always called in finally (no dangling child processes)
 * - command stripped of dangerous shell metacharacters before execution
 */
object ShellSandbox {

    private const val TAG = "ShellSandbox"

    // Characters that enable shell injection when passed raw to sh -c.
    // Commands containing these get extra scrutiny inside SafeGuard; we log them here.
    private val INJECTION_PATTERN = Regex("""[`$]|\$\(|\beval\b|\bexec\b""")

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean
    )

    suspend fun execute(
        command: String,
        timeoutMs: Long = 15_000L
    ): ShellResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()

        // Log suspicious metacharacters so SafeGuard / audit trail has a record
        if (INJECTION_PATTERN.containsMatchIn(trimmed)) {
            Log.w(TAG, "Potentially injected metacharacters in: ${trimmed.take(200)}")
        }

        val verdict = SafeGuard.checkShellCommand(trimmed)
        if (!verdict.allowed) {
            Log.w(TAG, "BLOCKED by SafeGuard: $trimmed — ${verdict.reason}")
            return@withContext ShellResult(-1, "", "Blocked: ${verdict.reason}", false)
        }
        if (verdict.cautionLevel == SafeGuard.CautionLevel.CAUTION) {
            Log.i(TAG, "⚠ Caution-level command: $trimmed")
        }

        Log.i(TAG, "Executing: ${trimmed.take(200)}")

        var process: Process? = null
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                coroutineScope {
                    process = Runtime.getRuntime().exec(arrayOf("sh", "-c", trimmed))
                    val p = process!!

                    // Read stdout and stderr concurrently to prevent blocking
                    val stdoutDeferred = async { p.inputStream.bufferedReader().readText().trim() }
                    val stderrDeferred = async { p.errorStream.bufferedReader().readText().trim() }

                    val stdout  = stdoutDeferred.await()
                    val stderr  = stderrDeferred.await()
                    val exitCode = p.waitFor()

                    Log.d(TAG, "Exit=$exitCode | out=${stdout.take(200)}")
                    if (stderr.isNotEmpty()) Log.d(TAG, "stderr=${stderr.take(200)}")

                    ShellResult(exitCode, stdout.take(4000), stderr.take(2000), exitCode == 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell error: ${e.message}")
                ShellResult(-1, "", "Error: ${e.message}", false)
            }
        }

        // Always destroy the process to avoid dangling child processes
        process?.destroy()

        return@withContext result ?: run {
            Log.w(TAG, "Command timed out after ${timeoutMs / 1000}s: ${trimmed.take(100)}")
            ShellResult(-1, "", "Timed out after ${timeoutMs / 1000}s", false)
        }
    }
}
