package com.omnix.agent.database

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * In-memory cosine similarity search over stored memory embeddings.
 *
 * Embeddings are serialised as little-endian IEEE-754 float arrays (4 bytes per element).
 * SQLite has no native vector index, so we load all candidates and rank in Kotlin.
 * This is acceptable for the expected scale (~1 000s of memories).
 */

/** Deserialise a [ByteArray] produced by [floatArrayToBytes] back to a [FloatArray]. */
fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buf.float }
}

/** Serialise a [FloatArray] to a [ByteArray] suitable for storing in a BLOB column. */
fun floatArrayToBytes(floats: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    floats.forEach { buf.putFloat(it) }
    return buf.array()
}

/**
 * Cosine similarity ∈ [-1, 1] between two vectors of equal length.
 * Returns 0 if either vector has zero magnitude.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Embedding dimension mismatch: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom < 1e-10) 0f else (dot / denom).toFloat()
}

/**
 * Retrieve the [topK] memories whose embeddings are most similar to [queryEmbedding].
 *
 * Optionally filter by [memoryType] before ranking.
 * Results are ordered by descending cosine similarity.
 */
suspend fun MemoryDao.findSimilar(
    queryEmbedding: FloatArray,
    topK: Int = 10,
    memoryType: String? = null
): List<ScoredMemory> {
    val candidates = if (memoryType != null) getByType(memoryType, Int.MAX_VALUE) else getAll()
    return candidates
        .mapNotNull { memory ->
            if (memory.embedding.isEmpty()) return@mapNotNull null
            val vec = try { bytesToFloatArray(memory.embedding) } catch (_: Exception) { return@mapNotNull null }
            if (vec.size != queryEmbedding.size) return@mapNotNull null
            ScoredMemory(memory, cosineSimilarity(queryEmbedding, vec))
        }
        .sortedByDescending { it.score }
        .take(topK)
}

/** A [MemoryEntity] paired with its cosine similarity score against a query. */
data class ScoredMemory(val memory: MemoryEntity, val score: Float)
