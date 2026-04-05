package com.omnix.agent.improvements

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.*

/**
 * Performance Profiling + Memory Optimization (Task 38)
 * Monitors OMNIX performance and optimizes resource usage.
 */
class PerformanceProfiler(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class PerformanceSnapshot(
        val heapUsedMb: Float,
        val heapMaxMb: Float,
        val nativeHeapMb: Float,
        val cpuPercent: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun startMonitoring() {
        scope.launch {
            while (isActive) {
                val snapshot = takeSnapshot()
                checkMemoryPressure(snapshot)
                delay(30_000L) // Sample every 30s
            }
        }
    }

    fun stopMonitoring() {
        scope.cancel()
    }

    private fun takeSnapshot(): PerformanceSnapshot {
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024f)
        val heapMax = runtime.maxMemory() / (1024 * 1024f)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024f)

        return PerformanceSnapshot(
            heapUsedMb = heapUsed,
            heapMaxMb = heapMax,
            nativeHeapMb = nativeHeap,
            cpuPercent = 0f // Requires /proc/stat parsing
        )
    }

    private suspend fun checkMemoryPressure(snapshot: PerformanceSnapshot) {
        val usageRatio = snapshot.heapUsedMb / snapshot.heapMaxMb

        if (usageRatio > 0.8f) {
            // High memory pressure - trim caches
            System.gc()
            // Clear non-essential caches in SkillLibraryManager
        }
    }

    fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info
    }
}
