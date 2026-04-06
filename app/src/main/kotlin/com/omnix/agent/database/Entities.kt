package com.omnix.agent.database

import androidx.room.*

// ─── Entity 1: Apps ──────────────────────────────────────────────────────────
@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val category: String,
    val capabilities: String,          // JSON array of capability tags
    val volatility: Float = 0.5f,      // 0=static, 1=highly dynamic UI
    val lastCrawled: Long = 0L,
    val isDiscovered: Boolean = false,
    val apkPath: String = "",
    val packageName: String = "",
    val launchActivity: String = ""
)

// ─── Entity 2: Screens ───────────────────────────────────────────────────────
@Entity(
    tableName = "screens",
    foreignKeys = [ForeignKey(
        entity = AppEntity::class,
        parentColumns = ["id"],
        childColumns = ["appId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("appId")]
)
data class ScreenEntity(
    @PrimaryKey val id: String,
    val appId: String,
    val name: String,
    val visionLabel: String,
    val elementCount: Int,
    val contentHash: String,
    val deepLinkUri: String = "",
    val lastSeen: Long = System.currentTimeMillis()
)

// ─── Entity 3: Elements ──────────────────────────────────────────────────────
@Entity(
    tableName = "elements",
    foreignKeys = [ForeignKey(
        entity = ScreenEntity::class,
        parentColumns = ["id"],
        childColumns = ["screenId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("screenId"), Index("resourceId")]
)
data class ElementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val screenId: String,
    val resourceId: String,
    val contentDesc: String,
    val text: String,
    val visionLabel: String,
    val className: String,
    val boundsJson: String,            // {"left":0,"top":0,"right":100,"bottom":50}
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean = false,
    val depth: Int = 0
)

// ─── Entity 4: Skills ────────────────────────────────────────────────────────
@Entity(
    tableName = "skills",
    indices = [Index("appId"), Index("intentHash")]
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val appId: String,
    val name: String,
    val type: String,                  // "ui_automation" | "api_call" | "composite"
    val category: String,             // "banking" | "payments" | "messaging" etc.
    val version: String,
    val intentPatternsJson: String,   // JSON array of intent pattern strings
    val parametersJson: String,       // JSON object of parameter definitions
    val stepsJson: String,            // JSON array of execution steps
    val confirmationRequired: Boolean,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray,         // Gemma embedding for semantic search
    val intentHash: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val healCount: Int = 0,
    val avgExecMs: Long = 0L,
    val status: String = "active",    // "active" | "deprecated" | "healing"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ─── Entity 5: Tasks ─────────────────────────────────────────────────────────
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val status: String = "pending",   // "pending"|"running"|"done"|"failed"|"cancelled"
    val planJson: String = "[]",      // JSON array of planned steps
    val checkpointJson: String = "",  // JSON checkpoint for resume
    val persistentSummary: String = "",
    val workingMemory: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val errorMsg: String = ""
)

// ─── Entity 6: Memories ──────────────────────────────────────────────────────
@Entity(
    tableName = "memories",
    indices = [Index("memoryType")]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val memoryType: String,           // "episodic"|"semantic"|"procedural"|"preference"
    val importanceScore: Float = 0.5f,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray,
    val accessCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
    val tags: String = "[]"           // JSON array of tags
)

// ─── Entity 7: Action History ────────────────────────────────────────────────
@Entity(
    tableName = "action_history",
    indices = [Index("timestamp"), Index("isFinancial")]
)
data class ActionHistoryEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val skillId: String,
    val skillName: String,
    val paramsJson: String,
    val outcome: String,              // "success" | "failure" | "cancelled"
    val errorMsg: String?,
    val isFinancial: Boolean,
    val retainDays: Int,
    val appId: String = "",
    val taskId: String = ""
)

// ─── Entity 8: Execution History ─────────────────────────────────────────────
@Entity(
    tableName = "execution_history",
    indices = [Index("skillId"), Index("executedAt")]
)
data class ExecutionHistoryEntity(
    @PrimaryKey val id: String,
    val skillId: String,
    val skillName: String,
    val inputParamsJson: String,
    val outputJson: String,
    val outcome: String,            // "success" | "failure" | "cancelled"
    val executedAt: Long,
    val durationMs: Long,
    val healApplied: Boolean = false,
    val healStrategy: String = ""
)

// ─── Entity 9: APK Knowledge ──────────────────────────────────────────────────
@Entity(tableName = "apk_knowledge")
data class APKKnowledgeEntity(
    @PrimaryKey val packageId: String,
    val deepLinksJson: String,
    val screensJson: String,
    val permissionsJson: String,
    val analysedAt: Long,
    val apkHash: String
)

// ─── Entity 10: Screen Crawl ──────────────────────────────────────────────────
@Entity(
    tableName = "screen_crawls",
    indices = [Index("packageId")]
)
data class ScreenCrawlEntity(
    @PrimaryKey val id: String,
    val packageId: String,
    val screenName: String,
    val elementsJson: String,
    val navPathJson: String,
    val crawledAt: Long,
    val contentHash: String
)
