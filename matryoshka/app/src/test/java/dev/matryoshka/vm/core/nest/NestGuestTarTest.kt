package dev.matryoshka.vm.core.nest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** The guest image unpacker against archives made by GNU tar, as the image workflow does. */
@RunWith(RobolectricTestRunner::class)
class NestGuestTarTest {

    private fun tar(src: File, out: File, vararg extra: String) {
        val cmd = listOf("tar", *extra, "--numeric-owner", "--owner=0", "--group=0", "-C", src.path, "-czf", out.path, ".")
        assertEquals(0, ProcessBuilder(cmd).inheritIO().start().waitFor())
    }

    private fun sampleTree(): File {
        val src = Files.createTempDirectory("nest-src").toFile()
        File(src, "system/bin").mkdirs()
        File(src, "system/bin/init").apply { writeText("#!init"); setExecutable(true) }
        File(src, "system/etc").mkdirs()
        File(src, "system/etc/hosts").writeText("127.0.0.1 localhost\n")
        val deep = File(src, "system/" + "d".repeat(60) + "/" + "e".repeat(60)).apply { mkdirs() }
        File(deep, "long-name-file-" + "x".repeat(80) + ".txt").writeText("long")
        Files.createSymbolicLink(File(src, "bin").toPath(), File("/system/bin").toPath())
        Files.createLink(File(src, "system/bin/init.hard").toPath(), File(src, "system/bin/init").toPath())
        return src
    }

    private fun check(dest: File) {
        assertEquals("#!init", File(dest, "system/bin/init").readText())
        assertEquals("127.0.0.1 localhost\n", File(dest, "system/etc/hosts").readText())
        val deep = File(dest, "system/" + "d".repeat(60) + "/" + "e".repeat(60))
        assertEquals("long", File(deep, "long-name-file-" + "x".repeat(80) + ".txt").readText())
        assertEquals("/system/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertEquals("#!init", File(dest, "system/bin/init.hard").readText())
        assertFalse(File(dest, "./").listFiles().orEmpty().any { it.name == "." })
    }

    @Test fun gnuFormat() = runBlocking {
        val src = sampleTree()
        val archive = File.createTempFile("nest", ".tar.gz")
        tar(src, archive)
        val dest = Files.createTempDirectory("nest-dst").toFile()
        var last = 0L to 0L
        NestGuest.extractTarGz(archive, dest) { d, t -> last = d to t }
        check(dest)
        assertEquals(archive.length(), last.first)
    }

    @Test fun paxFormat() = runBlocking {
        val src = sampleTree()
        val archive = File.createTempFile("nest", ".tar.gz")
        tar(src, archive, "--format=pax")
        val dest = Files.createTempDirectory("nest-dst").toFile()
        NestGuest.extractTarGz(archive, dest) { _, _ -> }
        check(dest)
    }
}
