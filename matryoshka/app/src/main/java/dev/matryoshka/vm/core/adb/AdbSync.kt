package dev.matryoshka.vm.core.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RemoteEntry(val name: String, val mode: Int, val size: Long, val mtime: Long) {
    val isDirectory: Boolean get() = (mode and 0xF000) == 0x4000
    val isFile: Boolean get() = (mode and 0xF000) == 0x8000
}

/** ADB file-sync service (`sync:`): push / pull / stat / list. */
class AdbSync(private val stream: AdbStream) : Closeable {

    private val input = DataInputStream(stream.inputStream)

    fun push(source: InputStream, remotePath: String, mode: Int = 0x81a4 /* 0644 */, mtimeSec: Long = System.currentTimeMillis() / 1000,
             onProgress: (Long) -> Unit = {}) {
        sendRequest("SEND", "$remotePath,$mode".toByteArray())
        val buf = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = source.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val packet = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN)
            packet.put("DATA".toByteArray()).putInt(n).put(buf, 0, n)
            stream.write(packet.array())
            total += n
            onProgress(total)
        }
        stream.write(header("DONE", mtimeSec.toInt()))
        val id = readId()
        val len = readLe32()
        if (id == "FAIL") throw IOException("push failed: " + readString(len))
        if (id != "OKAY") throw IOException("push: unexpected response $id")
    }

    fun pull(remotePath: String, sink: OutputStream, onProgress: (Long) -> Unit = {}) {
        sendRequest("RECV", remotePath.toByteArray())
        var total = 0L
        val buf = ByteArray(CHUNK)
        while (true) {
            val id = readId()
            val len = readLe32()
            when (id) {
                "DATA" -> {
                    var left = len
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(left, buf.size))
                        if (n < 0) throw IOException("pull: truncated")
                        sink.write(buf, 0, n)
                        left -= n
                        total += n
                    }
                    onProgress(total)
                }
                "DONE" -> return
                "FAIL" -> throw IOException("pull failed: " + readString(len))
                else -> throw IOException("pull: unexpected response $id")
            }
        }
    }

    fun stat(remotePath: String): RemoteEntry? {
        sendRequest("STAT", remotePath.toByteArray())
        val id = readId()
        if (id != "STAT") throw IOException("stat: unexpected response $id")
        val mode = readLe32()
        val size = readLe32().toLong() and 0xffffffffL
        val mtime = readLe32().toLong() and 0xffffffffL
        return if (mode == 0) null else RemoteEntry(remotePath.substringAfterLast('/'), mode, size, mtime)
    }

    fun list(remotePath: String): List<RemoteEntry> {
        sendRequest("LIST", remotePath.toByteArray())
        val out = mutableListOf<RemoteEntry>()
        while (true) {
            val id = readId()
            if (id == "DONE") {
                input.readFully(ByteArray(16))
                return out
            }
            if (id != "DENT") throw IOException("list: unexpected response $id")
            val mode = readLe32()
            val size = readLe32().toLong() and 0xffffffffL
            val mtime = readLe32().toLong() and 0xffffffffL
            val name = readString(readLe32())
            if (name != "." && name != "..") out += RemoteEntry(name, mode, size, mtime)
        }
    }

    override fun close() {
        runCatching { stream.write(header("QUIT", 0)) }
        stream.close()
    }

    private fun sendRequest(id: String, data: ByteArray) {
        val packet = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        packet.put(id.toByteArray()).putInt(data.size).put(data)
        stream.write(packet.array())
    }

    private fun header(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(value).array()

    private fun readId(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)

    private fun readLe32(): Int {
        val b = ByteArray(4).also { input.readFully(it) }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readString(len: Int): String {
        val b = ByteArray(len).also { input.readFully(it) }
        return b.toString(Charsets.UTF_8)
    }

    companion object {
        const val CHUNK = 64 * 1024

        /** Helper for tests and small transfers. */
        fun pullBytes(sync: AdbSync, path: String): ByteArray =
            ByteArrayOutputStream().also { sync.pull(path, it) }.toByteArray()
    }
}
