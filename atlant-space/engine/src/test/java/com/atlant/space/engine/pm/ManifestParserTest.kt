package com.atlant.space.engine.pm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ManifestParserTest {
    /** Any real APK; supplied through -Datlant.testApk=... */
    private val apk: File? = System.getProperty("atlant.testApk")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun parsesRealApk() {
        assumeTrue(apk != null)
        val m = ManifestParser.parseApk(apk!!)
        assertTrue(m.packageName.contains('.'))
        assertTrue(m.versionCode > 0)
        assertTrue(m.components.isNotEmpty())
        val launcher = m.launcher()
        assertNotNull(launcher)
        assertEquals(ComponentType.ACTIVITY, launcher!!.type)
        // JSON cache must round-trip exactly.
        assertEquals(m, ParsedManifest.fromJson(m.toJson()))
        println("pkg=${m.packageName} vc=${m.versionCode} vn=${m.versionName} min=${m.minSdk} tgt=${m.targetSdk} " +
            "components=${m.components.size} launcher=${launcher.name}")
    }

    @Test(expected = IOException::class)
    fun rejectsGarbage() {
        ManifestParser.parse(ByteArray(64) { it.toByte() })
    }

    @Test
    fun truncatedInputFailsCleanly() {
        assumeTrue(apk != null)
        val bytes = java.util.zip.ZipFile(apk!!).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        for (cut in listOf(8, 40, bytes.size / 3, bytes.size / 2, bytes.size - 5)) {
            try {
                ManifestParser.parse(bytes.copyOf(cut))
            } catch (_: IOException) {
                // expected for most cut points
            }
        }
    }
}
