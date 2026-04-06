package com.omnix.agent.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {

    private lateinit var db: OmnixDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OmnixDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `executionHistoryDao inserts and retrieves`() = runTest {
        val entity = ExecutionHistoryEntity(
            id = "eh1",
            skillId = "skill1",
            skillName = "Test Skill",
            inputParamsJson = "{}",
            outputJson = "{}",
            outcome = "success",
            executedAt = System.currentTimeMillis(),
            durationMs = 123L
        )
        db.executionHistoryDao().insert(entity)
        val result = db.executionHistoryDao().getForSkill("skill1")
        assertEquals(1, result.size)
        assertEquals("eh1", result[0].id)
    }

    @Test
    fun `apkKnowledgeDao upserts and retrieves`() = runTest {
        val entity = APKKnowledgeEntity(
            packageId = "com.example.app",
            deepLinksJson = "[]",
            screensJson = "[]",
            permissionsJson = "[]",
            analysedAt = System.currentTimeMillis(),
            apkHash = "abc123"
        )
        db.apkKnowledgeDao().upsert(entity)
        val result = db.apkKnowledgeDao().getByPackage("com.example.app")
        assertNotNull(result)
        assertEquals("abc123", result!!.apkHash)
    }

    @Test
    fun `screenCrawlDao inserts and retrieves`() = runTest {
        val entity = ScreenCrawlEntity(
            id = "crawl1",
            packageId = "com.example.app",
            screenName = "MainActivity",
            elementsJson = "[]",
            navPathJson = "[]",
            crawledAt = System.currentTimeMillis(),
            contentHash = "hash1"
        )
        db.screenCrawlDao().insert(entity)
        val results = db.screenCrawlDao().getForApp("com.example.app")
        assertEquals(1, results.size)
        assertEquals("hash1", results[0].contentHash)
    }

    @Test
    fun `skillEntity embedding field has BLOB type affinity annotation`() {
        val field = SkillEntity::class.java.getDeclaredField("embedding")
        val columnInfo = field.getAnnotation(androidx.room.ColumnInfo::class.java)
        assertNotNull("ColumnInfo annotation must be present on embedding field", columnInfo)
        assertEquals(
            "embedding must have BLOB type affinity",
            androidx.room.ColumnInfo.BLOB,
            columnInfo!!.typeAffinity
        )
    }
}
