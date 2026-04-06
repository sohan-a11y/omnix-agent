package com.omnix.agent.discovery

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class APKAnalyzerTest {

    @Test
    fun `parseBinaryXmlFromApk does not throw on valid zip`() {
        val tempFile = File.createTempFile("test", ".apk")
        java.util.zip.ZipOutputStream(tempFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest package=\"com.test\"/>".toByteArray())
            zos.closeEntry()
        }
        try {
            APKAnalyzer.parseBinaryXmlFromApk(tempFile, "AndroidManifest.xml")
            // pass — no throw
        } catch (e: Exception) {
            fail("parseBinaryXmlFromApk threw: ${e.message}")
        }
        tempFile.delete()
    }

    @Test
    fun `computeApkHash returns 64 char hex string`() {
        val tempFile = File.createTempFile("test", ".apk")
        tempFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val hash = APKAnalyzer.computeApkHash(tempFile)
        assertEquals(64, hash.length)
        assertTrue("Hash must be lowercase hex", hash.all { it.isDigit() || it in 'a'..'f' })
        tempFile.delete()
    }

    @Test
    fun `isSystemApp returns false for regular package`() {
        assertFalse(APKAnalyzer.isSystemApp("com.example.myapp"))
    }

    @Test
    fun `isSystemApp returns true for samsung package`() {
        assertTrue(APKAnalyzer.isSystemApp("com.samsung.android.app"))
    }

    @Test
    fun `isSystemApp returns true for com dot android package`() {
        assertTrue(APKAnalyzer.isSystemApp("com.android.settings"))
    }
}
