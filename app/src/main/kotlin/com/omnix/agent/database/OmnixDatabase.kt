package com.omnix.agent.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppEntity::class,
        ScreenEntity::class,
        ElementEntity::class,
        SkillEntity::class,
        TaskEntity::class,
        MemoryEntity::class,
        ActionHistoryEntity::class,
        ExecutionHistoryEntity::class,
        APKKnowledgeEntity::class,
        ScreenCrawlEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OmnixDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun screenDao(): ScreenDao
    abstract fun elementDao(): ElementDao
    abstract fun skillDao(): SkillDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun historyDao(): HistoryDao
    abstract fun executionHistoryDao(): ExecutionHistoryDao
    abstract fun apkKnowledgeDao(): APKKnowledgeDao
    abstract fun screenCrawlDao(): ScreenCrawlDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile private var INSTANCE: OmnixDatabase? = null

        // v1 → v2: added execution_history, apk_knowledge, screen_crawls tables
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS execution_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        skillId TEXT NOT NULL,
                        skillName TEXT NOT NULL,
                        inputParamsJson TEXT NOT NULL,
                        outputJson TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        executedAt INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        healApplied INTEGER NOT NULL DEFAULT 0,
                        healStrategy TEXT NOT NULL DEFAULT ''
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS apk_knowledge (
                        packageId TEXT NOT NULL PRIMARY KEY,
                        deepLinksJson TEXT NOT NULL,
                        screensJson TEXT NOT NULL,
                        permissionsJson TEXT NOT NULL,
                        analysedAt INTEGER NOT NULL,
                        apkHash TEXT NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS screen_crawls (
                        id TEXT NOT NULL PRIMARY KEY,
                        packageId TEXT NOT NULL,
                        screenName TEXT NOT NULL,
                        elementsJson TEXT NOT NULL,
                        navPathJson TEXT NOT NULL,
                        crawledAt INTEGER NOT NULL,
                        contentHash TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screen_crawls_packageId ON screen_crawls(packageId)")
            }
        }

        // v2 → v3: added chat_sessions and chat_messages tables
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        startedAt INTEGER NOT NULL DEFAULT 0,
                        endedAt INTEGER NOT NULL DEFAULT 0,
                        messageCount INTEGER NOT NULL DEFAULT 0,
                        summary TEXT NOT NULL DEFAULT ''
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        isUser INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
            }
        }

        fun getInstance(context: Context): OmnixDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    OmnixDatabase::class.java,
                    "omnix.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
