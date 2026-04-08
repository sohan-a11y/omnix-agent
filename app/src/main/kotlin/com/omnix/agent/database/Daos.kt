package com.omnix.agent.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─── App DAO ─────────────────────────────────────────────────────────────────
@Dao
interface AppDao {
    @Query("SELECT * FROM apps ORDER BY name ASC")
    fun getAll(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps WHERE id = :id")
    suspend fun getById(id: String): AppEntity?

    @Query("SELECT * FROM apps WHERE isDiscovered = 0")
    suspend fun getUndiscovered(): List<AppEntity>

    @Query("SELECT * FROM apps WHERE isDiscovered = 1")
    suspend fun getDiscovered(): List<AppEntity>

    @Upsert
    suspend fun upsert(app: AppEntity)

    @Upsert
    suspend fun upsertAll(apps: List<AppEntity>)

    @Query("UPDATE apps SET isDiscovered=1, lastCrawled=:ts WHERE id=:id")
    suspend fun markDiscovered(id: String, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM apps WHERE id = :id")
    suspend fun delete(id: String)
}

// ─── Screen DAO ──────────────────────────────────────────────────────────────
@Dao
interface ScreenDao {
    @Query("SELECT * FROM screens WHERE appId = :appId")
    suspend fun getForApp(appId: String): List<ScreenEntity>

    @Query("SELECT * FROM screens WHERE id = :id")
    suspend fun getById(id: String): ScreenEntity?

    @Upsert
    suspend fun upsert(screen: ScreenEntity)

    @Upsert
    suspend fun upsertAll(screens: List<ScreenEntity>)

    @Query("DELETE FROM screens WHERE appId = :appId")
    suspend fun deleteForApp(appId: String)
}

// ─── Element DAO ─────────────────────────────────────────────────────────────
@Dao
interface ElementDao {
    @Query("SELECT * FROM elements WHERE screenId = :screenId")
    suspend fun getForScreen(screenId: String): List<ElementEntity>

    @Query("SELECT * FROM elements WHERE resourceId LIKE :pattern")
    suspend fun findByResourceId(pattern: String): List<ElementEntity>

    @Upsert
    suspend fun upsert(element: ElementEntity)

    @Upsert
    suspend fun upsertAll(elements: List<ElementEntity>)

    @Query("DELETE FROM elements WHERE screenId = :screenId")
    suspend fun deleteForScreen(screenId: String)
}

// ─── Skill DAO ───────────────────────────────────────────────────────────────
@Dao
interface SkillDao {
    @Query("SELECT * FROM skills WHERE appId = :appId AND status = 'active'")
    suspend fun getForApp(appId: String): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE status = 'active' ORDER BY successCount DESC")
    fun getAllActive(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE category = :category AND status = 'active'")
    suspend fun getByCategory(category: String): List<SkillEntity>

    @Upsert
    suspend fun upsert(skill: SkillEntity)

    @Query("UPDATE skills SET successCount=successCount+1, avgExecMs=:avgMs, updatedAt=:ts WHERE id=:id")
    suspend fun recordSuccess(id: String, avgMs: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE skills SET failureCount=failureCount+1, updatedAt=:ts WHERE id=:id")
    suspend fun recordFailure(id: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE skills SET healCount=healCount+1, stepsJson=:newSteps, updatedAt=:ts WHERE id=:id")
    suspend fun recordHeal(id: String, newSteps: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE skills SET status=:status, updatedAt=:ts WHERE id=:id")
    suspend fun updateStatus(id: String, status: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT * FROM skills WHERE intentHash = :hash")
    suspend fun findByIntentHash(hash: String): SkillEntity?
}

// ─── Task DAO ─────────────────────────────────────────────────────────────────
@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status IN ('pending','running') ORDER BY createdAt ASC")
    suspend fun getActive(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET status=:status, completedAt=:ts WHERE id=:id")
    suspend fun updateStatus(id: String, status: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET checkpointJson=:checkpoint, workingMemory=:mem WHERE id=:id")
    suspend fun updateCheckpoint(id: String, checkpoint: String, mem: String)

    @Query("DELETE FROM tasks WHERE status='done' AND completedAt < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}

// ─── Memory DAO ───────────────────────────────────────────────────────────────
@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE memoryType = :type ORDER BY importanceScore DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY importanceScore DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 100): List<MemoryEntity>

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Query("UPDATE memories SET accessCount=accessCount+1, lastAccessed=:ts WHERE id=:id")
    suspend fun recordAccess(id: Long, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM memories WHERE importanceScore < :threshold AND accessCount = 0")
    suspend fun pruneUnimportant(threshold: Float = 0.2f)
}

// ─── History DAO ──────────────────────────────────────────────────────────────
@Dao
interface HistoryDao {
    @Query("SELECT * FROM action_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ActionHistoryEntity>>

    @Query("SELECT * FROM action_history WHERE isFinancial = 1 ORDER BY timestamp DESC")
    fun getFinancial(): Flow<List<ActionHistoryEntity>>

    @Upsert
    suspend fun insert(action: ActionHistoryEntity)

    @Query("DELETE FROM action_history WHERE timestamp < :cutoff AND retainDays > 0 AND (timestamp/86400000) < :cutoff/86400000 - retainDays")
    suspend fun pruneExpired(cutoff: Long = System.currentTimeMillis())
}

@Dao
interface ExecutionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ExecutionHistoryEntity)

    @Query("SELECT * FROM execution_history WHERE skillId = :skillId ORDER BY executedAt DESC LIMIT 50")
    suspend fun getForSkill(skillId: String): List<ExecutionHistoryEntity>

    @Query("SELECT * FROM execution_history ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<ExecutionHistoryEntity>

    @Query("DELETE FROM execution_history WHERE executedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface APKKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: APKKnowledgeEntity)

    @Query("SELECT * FROM apk_knowledge WHERE packageId = :packageId")
    suspend fun getByPackage(packageId: String): APKKnowledgeEntity?

    @Query("SELECT * FROM apk_knowledge")
    suspend fun getAll(): List<APKKnowledgeEntity>
}

@Dao
interface ScreenCrawlDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScreenCrawlEntity)

    @Query("SELECT * FROM screen_crawls WHERE packageId = :packageId ORDER BY crawledAt DESC")
    suspend fun getForApp(packageId: String): List<ScreenCrawlEntity>

    @Query("SELECT * FROM screen_crawls WHERE packageId = :packageId AND screenName = :screenName ORDER BY crawledAt DESC LIMIT 1")
    suspend fun getLatestForScreen(packageId: String, screenName: String): ScreenCrawlEntity?
}

// ─── Chat Session DAO ─────────────────────────────────────────────────────────
@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET endedAt=:ts, messageCount=:count, summary=:summary WHERE id=:id")
    suspend fun finalize(id: String, ts: Long, count: Int, summary: String)

    @Query("SELECT * FROM chat_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)
}

// ─── Chat Message DAO ─────────────────────────────────────────────────────────
@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSession(sessionId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int
}
