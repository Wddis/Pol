package dev.matryoshka.vm.core.image

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for Android boot images (header v0–v4). Used after Magisk patched the guest's
 * `boot.img`: the VM engines boot kernel + ramdisk directly, so we split the patched image.
 */
object BootImage {

    data class Layout(val headerVersion: Int, val pageSize: Int, val kernelSize: Int, val ramdiskSize: Int) {
        val kernelOffset: Long get() = pageSize.toLong()
        val ramdiskOffset: Long get() = kernelOffset + align(kernelSize.toLong(), pageSize)
    }

    fun parse(header: ByteArray): Layout {
        if (header.size < 48 || String(header, 0, 8, Charsets.US_ASCII) != "ANDROID!") {
            throw IOException("Not an Android boot image")
        }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val v3Version = b.getInt(40)
        return if (v3Version in 3..4) {
            Layout(v3Version, 4096, b.getInt(8), b.getInt(12))
        } else {
            val kernelSize = b.getInt(8)
            val ramdiskSize = b.getInt(16)
            val pageSize = b.getInt(36)
            val version = b.getInt(40)
            if (pageSize !in setOf(2048, 4096, 8192, 16384)) throw IOException("Bad page size $pageSize")
            Layout(version, pageSize, kernelSize, ramdiskSize)
        }
    }

    fun extract(bootImg: File, kernelOut: File, ramdiskOut: File) {
        RandomAccessFile(bootImg, "r").use { raf ->
            val header = ByteArray(1648.coerceAtMost(raf.length().toInt()))
            raf.readFully(header)
            val layout = parse(header)
            copyRange(raf, layout.kernelOffset, layout.kernelSize.toLong(), kernelOut)
            copyRange(raf, layout.ramdiskOffset, layout.ramdiskSize.toLong(), ramdiskOut)
        }
    }

    private fun copyRange(raf: RandomAccessFile, offset: Long, size: Long, out: File) {
        raf.seek(offset)
        out.outputStream().use { o ->
            val buf = ByteArray(1 shl 20)
            var left = size
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n < 0) throw IOException("Truncated boot image")
                o.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun align(v: Long, page: Int): Long = (v + page - 1) / page * page
}
