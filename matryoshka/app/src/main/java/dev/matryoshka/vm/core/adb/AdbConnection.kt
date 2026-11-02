package dev.matryoshka.vm.core.adb

import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_AUTH
import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_CLSE
import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_CNXN
import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_OKAY
import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_OPEN
import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_STLS
import dev.matryoshka.vm.core.adb.AdbMessage.Companion.A_WRTE
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multiplexed ADB connection to the guest's adbd over TCP (the guest listens on its private
 * address, reachable only from the host through the VM's /30 link).
 */
class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    val maxPayload: Int,
    val banner: String,
) : Closeable {

    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val writeLock = Any()
    @Volatile private var closed = false

    private val reader = Thread({ readLoop() }, "adb-reader").apply { isDaemon = true; start() }

    val isAlive: Boolean get() = !closed && socket.isConnected && !socket.isClosed

    /** Guest properties advertised in the CNXN banner (`device::ro.product.name=...;...`). */
    val bannerProps: Map<String, String> by lazy {
        banner.substringAfter("::", "").split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
    }

    /** Opens a service stream, e.g. `exec:ls`, `sync:` or `localabstract:scrcpy_1234`. */
    fun open(destination: String, timeoutMs: Long = 10_000): AdbStream {
        check(!closed) { "ADB connection closed" }
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(this, localId)
        streams[localId] = stream
        send(AdbMessage(A_OPEN, localId, 0, (destination + "\u0000").toByteArray()))
        if (!stream.awaitOpen(timeoutMs)) {
            streams.remove(localId)
            throw IOException("Guest refused to open '$destination'")
        }
        return stream
    }

    internal fun send(message: AdbMessage) {
        synchronized(writeLock) {
            if (closed) throw IOException("ADB connection closed")
            message.writeTo(output)
        }
    }

    internal fun forget(localId: Int) {
        streams.remove(localId)
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val msg = AdbMessage.read(input)
                when (msg.command) {
                    A_OKAY -> streams[msg.arg1]?.onOkay(remoteId = msg.arg0)
                    A_WRTE -> {
                        val s = streams[msg.arg1]
                        if (s != null) {
                            s.onData(msg.payload)
                            send(AdbMessage(A_OKAY, msg.arg1, msg.arg0))
                        } else {
                            send(AdbMessage(A_CLSE, 0, msg.arg0))
                        }
                    }
                    A_CLSE -> streams.remove(msg.arg1)?.onRemoteClose()
                    else -> Unit
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        streams.values.forEach { it.onRemoteClose() }
        streams.clear()
    }

    companion object {
        /**
         * Connects and authenticates. When the guest does not know our key yet, the public key is
         * offered; engines pre-install it, so no on-screen confirmation is needed in practice.
         */
        fun connect(host: String, port: Int, key: AdbKey, timeoutMs: Int = 5_000): AdbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
            try {
                AdbMessage(A_CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray())
                    .writeTo(output)
                var sentSignature = false
                while (true) {
                    val msg = AdbMessage.read(input)
                    when (msg.command) {
                        A_CNXN -> {
                            socket.soTimeout = 0
                            val banner = String(msg.payload).trimEnd('\u0000')
                            return AdbConnection(socket, input, output, msg.arg1.coerceAtMost(AdbMessage.MAX_PAYLOAD), banner)
                        }
                        A_AUTH -> {
                            if (msg.arg0 != AdbMessage.AUTH_TOKEN) throw IOException("Unexpected AUTH type ${msg.arg0}")
                            if (!sentSignature) {
                                AdbMessage(A_AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.sign(msg.payload)).writeTo(output)
                                sentSignature = true
                            } else {
                                AdbMessage(A_AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0,
                                    (key.adbPublicKey() + "\u0000").toByteArray()).writeTo(output)
                                // Waiting for the user to accept inside the guest.
                                socket.soTimeout = 120_000
                            }
                        }
                        A_STLS -> throw IOException("Guest requires TLS pairing; use the pre-installed key instead")
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

/** One logical ADB stream with blocking read/write and protocol-level flow control. */
class AdbStream internal constructor(
    private val conn: AdbConnection,
    val localId: Int,
) : Closeable {

    @Volatile private var remoteId = 0
    @Volatile var isClosed = false
        private set
    private val opened = Semaphore(0)
    private val writeReady = Semaphore(0)
    private val queue = LinkedBlockingQueue<ByteArray>()

    internal fun awaitOpen(timeoutMs: Long): Boolean =
        opened.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS) && !isClosed

    internal fun onOkay(remoteId: Int) {
        if (this.remoteId == 0) {
            this.remoteId = remoteId
            opened.release()
        } else {
            writeReady.release()
        }
    }

    internal fun onData(data: ByteArray) {
        queue.put(data)
    }

    internal fun onRemoteClose() {
        if (isClosed) return
        isClosed = true
        queue.put(EOF)
        opened.release()
        writeReady.release()
    }

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            if (isClosed) throw IOException("Stream closed")
            val n = minOf(conn.maxPayload, end - pos)
            conn.send(AdbMessage(A_WRTE, localId, remoteId, data.copyOfRange(pos, pos + n)))
            // adbd acknowledges each WRTE with OKAY before the next one may be sent.
            writeReady.acquire()
            pos += n
        }
    }

    /** Next chunk from the guest, or null at end of stream. */
    fun readChunk(): ByteArray? {
        val chunk = queue.take()
        if (chunk === EOF) {
            queue.put(EOF)
            return null
        }
        return chunk
    }

    val inputStream: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var cur = current
            if (cur == null || pos >= cur.size) {
                cur = readChunk() ?: return -1
                current = cur
                pos = 0
            }
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = (current?.size ?: 0) - pos
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) = this@AdbStream.write(b, off, len)
    }

    fun readAllText(): String = inputStream.readBytes().toString(Charsets.UTF_8)

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { conn.send(AdbMessage(A_CLSE, localId, remoteId)) }
        conn.forget(localId)
        queue.put(EOF)
        writeReady.release()
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
