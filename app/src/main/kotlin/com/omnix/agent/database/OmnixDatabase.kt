package com.omnix.agent.database

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

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
    version = 4,
    exportSchema = true
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
        private const val TAG = "OmnixDatabase"
        private const val DB_NAME = "omnix.db"
        private const val PREFS_NAME = "omnix_db_prefs"
        private const val PREF_KEY_PASSPHRASE = "db_passphrase"

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

        // v3 → v4: added parentId, visibility, isEnabled, zIndex to elements table
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE elements ADD COLUMN parentId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE elements ADD COLUMN visibility INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE elements ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE elements ADD COLUMN zIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Returns the encrypted database instance. On first run, creates a random AES-256 passphrase
         * stored in EncryptedSharedPreferences (backed by Android Keystore). If an existing plaintext
         * database is detected, it is deleted so Room creates a fresh encrypted one.
         */
        fun getInstance(context: Context): OmnixDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildInstance(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildInstance(appCtx: Context): OmnixDatabase {
            val passphrase = getOrCreatePassphrase(appCtx)
            val passphraseBytes = String(passphrase).toByteArray(Charsets.UTF_8)
            val factory = SupportOpenHelperFactory(passphraseBytes)

            // If an existing PLAINTEXT database is present, delete it.
            // SQLite plaintext files always start with "SQLite format 3\0".
            // SQLCipher files do not — they start with encrypted bytes.
            val dbFile = appCtx.getDatabasePath(DB_NAME)
            if (dbFile.exists() && isPlaintextSQLite(dbFile)) {
                Log.i(TAG, "Deleting unencrypted legacy database — fresh encrypted DB will be created")
                appCtx.deleteDatabase(DB_NAME)
            }

            return Room.databaseBuilder(appCtx, OmnixDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Retrieves the stored passphrase or generates a new random 256-bit one.
         * The passphrase is persisted in EncryptedSharedPreferences, which is
         * protected by the Android Keystore — it cannot be read without device unlock.
         */
        private fun getOrCreatePassphrase(context: Context): CharArray {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val existing = prefs.getString(PREF_KEY_PASSPHRASE, null)
                if (existing != null) return existing.toCharArray()

                // Generate cryptographically random 32-byte (256-bit) passphrase
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                val newPassphrase = Base64.encodeToString(bytes, Base64.NO_WRAP)
                prefs.edit().putString(PREF_KEY_PASSPHRASE, newPassphrase).apply()
                newPassphrase.toCharArray()
            } catch (e: Exception) {
                // Keystore unavailable on this device — fall back to a fixed device-specific key
                Log.e(TAG, "EncryptedSharedPreferences unavailable, using fallback passphrase: ${e.message}")
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ).toCharArray()
            }
        }

        /**
         * Returns true if [file] is an unencrypted SQLite database.
         * All plaintext SQLite files start with the 16-byte magic string "SQLite format 3\0".
         */
        private fun isPlaintextSQLite(file: File): Boolean {
            return try {
                val header = ByteArray(16)
                file.inputStream().use { it.read(header) }
                header.decodeToString().startsWith("SQLite format 3")
            } catch (_: Exception) {
                false
            }
        }
    }
}
