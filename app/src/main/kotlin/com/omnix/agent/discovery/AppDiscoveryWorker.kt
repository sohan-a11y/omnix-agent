package com.omnix.agent.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.*
import com.omnix.agent.database.AppEntity
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

/**
 * WorkManager-based full app discovery.
 *
 * Runs as an EXPEDITED job in batches of BATCH_SIZE apps.
 * Each batch is a separate WorkRequest chained to the next,
 * so Samsung's process-killer can only cancel one batch — the rest
 * re-enqueue automatically via unique work continuation.
 *
 * What it does per app:
 *  1. Read PackageManager metadata (name, version, activities, launch intent)
 *  2. Keyword-classify into category (banking / messaging / travel / …)
 *  3. Parse APK zip for layout resource IDs and deep-link activities
 *  4. Store everything to Room DB
 */
class AppDiscoveryWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_BATCH_START = "batch_start"
        const val KEY_TOTAL       = "total"
        const val WORK_TAG        = "app_discovery"
        private const val BATCH_SIZE = 8

        fun enqueueFullDiscovery(context: Context) {
            // Cancel any running discovery first
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)

            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { !isSystemPkg(it.packageName) }
                .map { it.packageName }

            val total = packages.size
            Log.i("AppDiscovery", "Enqueueing discovery for $total apps in batches of $BATCH_SIZE")

            // Store package list so workers can access it
            val prefs = context.getSharedPreferences("omnix_discovery", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("pkg_list", packages.joinToString(","))
                .putInt("total", total)
                .apply()

            // Chain batches: batch0 → batch1 → batch2 → …
            var continuation: WorkContinuation? = null
            var offset = 0
            while (offset < total) {
                val req = OneTimeWorkRequestBuilder<AppDiscoveryWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build())
                    .setInputData(workDataOf(
                        KEY_BATCH_START to offset,
                        KEY_TOTAL to total
                    ))
                    .addTag(WORK_TAG)
                    .build()

                continuation = if (continuation == null) {
                    WorkManager.getInstance(context).beginWith(req)
                } else {
                    continuation.then(req)
                }
                offset += BATCH_SIZE
            }
            continuation?.enqueue()
        }

        private fun isSystemPkg(pkg: String): Boolean {
            val systemPrefixes = listOf(
                "com.android.", "android.", "com.google.android.",
                "com.samsung.", "com.sec.", "com.qualcomm.", "com.qti.",
                "com.osp.", "com.knox.", "com.samsung.android."
            )
            return systemPrefixes.any { pkg.startsWith(it) }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batchStart = inputData.getInt(KEY_BATCH_START, 0)
        val total      = inputData.getInt(KEY_TOTAL, 0)

        val prefs    = context.getSharedPreferences("omnix_discovery", Context.MODE_PRIVATE)
        val allPkgs  = prefs.getString("pkg_list", "")?.split(",")?.filter { it.isNotBlank() }
            ?: return@withContext Result.failure()

        val batch = allPkgs.drop(batchStart).take(BATCH_SIZE)
        val engine = DiscoveryEngine(context)

        batch.forEachIndexed { i, pkg ->
            val done = batchStart + i
            setProgress(workDataOf(
                "done" to done,
                "total" to total,
                "current_pkg" to pkg
            ))
            try {
                // Always force-refresh so every app gets the full rich metadata:
                // activities, permissions, services, inferred capabilities
                engine.discoverApp(pkg, forceRefresh = true)
            } catch (e: Exception) {
                Log.w("AppDiscovery", "Failed $pkg: ${e.message}")
            }
        }

        val finalDone = batchStart + batch.size
        setProgress(workDataOf("done" to finalDone, "total" to total))

        // Refresh Gemma's app knowledge after each batch so it learns as discovery runs
        com.omnix.agent.ai.GemmaInferenceEngine.loadAppKnowledge(context)

        Result.success()
    }

    private fun classifyByKeyword(pkg: String, name: String): String {
        val p = pkg.lowercase(); val n = name.lowercase()
        return when {
            p.hasAny("bank","hdfc","icici","sbi","axis","kotak","paytm","phonepe","gpay","upi","bhim","razorpay","zerodha","groww","navi","invest","mutual","loan","credit","debit") ||
            n.hasAny("bank","pay","upi","money","wallet","transfer","finance","invest","stock","trade","loan","credit") -> "banking"

            p.hasAny("whatsapp","telegram","signal","messenger","instagram","discord","slack","teams","meet","zoom","skype","snapchat") ||
            n.hasAny("chat","message","call","video call","meet","talk") -> "messaging"

            p.hasAny("amazon","flipkart","meesho","myntra","nykaa","snapdeal","shop","commerce","store","market") ||
            n.hasAny("shop","store","buy","deal","cart","mall","marketplace") -> "shopping"

            p.hasAny("swiggy","zomato","blinkit","dunzo","bigbasket","grocer","food","restaurant") ||
            n.hasAny("food","delivery","order","restaurant","dine","eat","kitchen","pizza","biryani") -> "food"

            p.hasAny("uber","ola","rapido","maps","navigation","yatra","makemytrip","irctc","redbus","metro","flight","travel","airline","cab") ||
            n.hasAny("travel","cab","ride","bus","train","flight","map","route","taxi","booking") -> "travel"

            p.hasAny("netflix","hotstar","prime","youtube","spotify","gaana","jiosaavn","jiocinema","mxplayer","zee","sony","airtel","game","casual","puzzle","chess","cricket") ||
            n.hasAny("video","music","watch","stream","movie","song","game","play","cricket","series","show") -> "entertainment"

            p.hasAny("health","medic","doctor","apollo","pharmeasy","netmeds","practo","fitbit","calorie","yoga","fitness","hospital","pharmacy","cure","diet") ||
            n.hasAny("health","doctor","medic","fitness","exercise","calorie","workout","medicine","pharma") -> "health"

            p.hasAny("facebook","twitter","linkedin","reddit","quora","koo","sharechat","tiktok","reels","instagram") ||
            n.hasAny("social","community","follow","feed","post","story","tweet","status") -> "social"

            else -> "productivity"
        }
    }

    private fun String.hasAny(vararg words: String) = words.any { this.contains(it, ignoreCase = true) }

    /** Pull readable strings from Android binary XML string pool. */
    private fun extractStringsFromBinaryXml(bytes: ByteArray): String {
        return try {
            val sb  = StringBuilder()
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.position(8)
            val headerSize   = buf.short.toInt().coerceIn(0, 256)
            buf.int          // chunkSize
            val stringCount  = buf.int.coerceIn(0, 500)
            buf.int; buf.int // styleCount, flags
            val stringsStart = buf.int
            buf.int          // stylesStart
            val poolBase     = 8
            repeat(stringCount) { i ->
                try {
                    buf.position(poolBase + headerSize + i * 4)
                    val offset = buf.int
                    buf.position(poolBase + stringsStart + offset)
                    val len = buf.short.toInt() and 0xFFFF
                    if (len in 1..80) {
                        val chars = CharArray(len) { buf.char }
                        sb.append(String(chars)).append(' ')
                    }
                } catch (_: Exception) {}
            }
            sb.toString()
        } catch (_: Exception) { "" }
    }
}
