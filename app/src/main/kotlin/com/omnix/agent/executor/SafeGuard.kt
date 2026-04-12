package com.omnix.agent.executor

import android.util.Log

/**
 * SafeGuard — the "don't break" layer.
 *
 * Every command the AI wants to run passes through here first.
 * Philosophy: allow EVERYTHING except things that would brick/break the device.
 * The AI can delete files, install packages, run python — but it can NOT:
 *   - Wipe /system, /data, /vendor, /boot
 *   - Flash firmware or bootloader
 *   - Uninstall OMNIX itself
 *   - Fork-bomb or OOM the system
 *   - Disable security services
 */
object SafeGuard {

    private const val TAG = "SafeGuard"

    // ── Shell command safety ────────────────────────────────────────────────

    /**
     * Things that would BREAK the device irreversibly.
     * Everything else is allowed — the AI is powerful but not destructive.
     */
    private val FATAL_PATTERNS = listOf(
        // Wipe partitions / destroy filesystem
        "rm -rf /",
        "rm -rf /*",
        "rm -rf /system",
        "rm -rf /data",
        "rm -rf /vendor",
        "rm -rf /boot",
        "rm -rf /sdcard",
        "mkfs.",       // format filesystem
        "dd if=",      // raw disk write
        "flash",       // firmware flash
        "fastboot",
        "odin",

        // Reboot into dangerous modes
        "reboot bootloader",
        "reboot recovery",
        "reboot edl",

        // Fork-bomb / resource exhaustion
        ":(){ :|:& };:",
        "fork()",
        "while true; do",
        "yes |",

        // Disable critical system services
        "pm disable android",
        "pm uninstall com.android",
        "pm uninstall com.samsung",
        "pm clear com.android",
        "pm clear com.samsung",

        // Don't let AI uninstall itself
        "pm uninstall com.omnix",
        "pm clear com.omnix",

        // SELinux / root escalation (don't break security)
        "setenforce 0",
        "mount -o remount,rw /system",

        // Crypto-locker patterns
        "openssl enc",
        "gpg --symmetric"
    )

    /**
     * Patterns that need extra caution — allowed but logged prominently.
     */
    private val CAUTION_PATTERNS = listOf(
        "rm ",             // deleting files (non-recursive is OK)
        "kill ",
        "killall",
        "su ",
        "chmod",
        "chown",
        "mount",
        "reboot",          // soft reboot is OK, just flagged
        "pm uninstall",    // uninstalling user apps is OK (not system ones)
        "pm clear",        // clearing user app data is OK
        "settings put",
        "> /sdcard",       // redirecting output to storage
        "curl ",           // network access
        "wget ",
        "pip install",
        "npm install",
        "apt install"
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val cautionLevel: CautionLevel = CautionLevel.SAFE
    )

    enum class CautionLevel { SAFE, CAUTION, BLOCKED }

    /**
     * Check if a shell command is safe to execute.
     * Returns Verdict with allowed=true for most things.
     * Only returns allowed=false for device-breaking commands.
     */
    fun checkShellCommand(command: String): Verdict {
        val lower = command.lowercase().trim()

        // Check fatal patterns
        for (pattern in FATAL_PATTERNS) {
            if (lower.contains(pattern)) {
                Log.w(TAG, "🛑 BLOCKED fatal command: $command")
                return Verdict(
                    allowed = false,
                    reason = "Blocked: '$pattern' could damage the device irreversibly.",
                    cautionLevel = CautionLevel.BLOCKED
                )
            }
        }

        // Check caution patterns
        for (pattern in CAUTION_PATTERNS) {
            if (lower.contains(pattern)) {
                Log.i(TAG, "⚠️ Caution command: $command (matched: $pattern)")
                return Verdict(
                    allowed = true,
                    reason = "Proceed with caution: matches '$pattern'",
                    cautionLevel = CautionLevel.CAUTION
                )
            }
        }

        return Verdict(allowed = true, reason = "OK", cautionLevel = CautionLevel.SAFE)
    }

    // ── File operation safety ────────────────────────────────────────────────

    /**
     * Check if writing to a path is safe.
     * Blocks writes to /system, /vendor, /boot, /proc, /sys.
     * Allows writes to /sdcard, /data/data/com.omnix*, Termux home, etc.
     */
    fun checkFileWrite(path: String): Verdict {
        val normalized = path.replace("\\", "/").lowercase()

        val forbiddenPaths = listOf(
            "/system/", "/vendor/", "/boot/", "/proc/", "/sys/",
            "/dev/", "/init.", "/fstab"
        )

        for (fp in forbiddenPaths) {
            if (normalized.startsWith(fp) || normalized.contains(fp)) {
                return Verdict(false, "Cannot write to system path: $fp", CautionLevel.BLOCKED)
            }
        }

        return Verdict(true, "OK", CautionLevel.SAFE)
    }

    // ── Code execution safety ────────────────────────────────────────────────

    /**
     * Check if AI-generated code is safe to execute.
     * Scans for destructive patterns in scripts.
     */
    fun checkCodeContent(code: String): Verdict {
        val lower = code.lowercase()

        val dangerousCodePatterns = listOf(
            "os.system(\"rm -rf",
            "shutil.rmtree(\"/\"",
            "shutil.rmtree('/system'",
            "subprocess.call([\"rm\"",
            "format c:",  // Windows remnant but just in case
            "import ctypes",  // native memory access
            "exec(base64",    // obfuscated code execution
        )

        for (pattern in dangerousCodePatterns) {
            if (lower.contains(pattern)) {
                return Verdict(false, "Code contains dangerous pattern: $pattern", CautionLevel.BLOCKED)
            }
        }

        return Verdict(true, "OK", CautionLevel.SAFE)
    }

    // ── Intent/Activity safety ──────────────────────────────────────────────

    /**
     * Check if launching an Android intent/activity is safe.
     */
    fun checkIntent(action: String, pkg: String): Verdict {
        // Block factory reset intents
        val dangerousActions = listOf(
            "android.intent.action.MASTER_CLEAR",
            "android.intent.action.FACTORY_RESET",
            "android.intent.action.MASTER_CLEAR_NOTIFICATION"
        )

        if (action in dangerousActions) {
            return Verdict(false, "Blocked factory reset intent", CautionLevel.BLOCKED)
        }

        return Verdict(true, "OK", CautionLevel.SAFE)
    }
}
