package com.omnix.agent.database

import android.content.Context
import androidx.room.*

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
        ScreenCrawlEntity::class
    ],
    version = 2,
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

    companion object {
        @Volatile private var INSTANCE: OmnixDatabase? = null

        fun getInstance(context: Context): OmnixDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    OmnixDatabase::class.java,
                    "omnix.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
