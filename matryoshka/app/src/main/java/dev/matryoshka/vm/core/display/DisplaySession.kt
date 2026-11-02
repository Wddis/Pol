package dev.matryoshka.vm.core.display

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import dev.matryoshka.vm.BuildConfig
import dev.matryoshka.vm.core.adb.AdbConnection
import dev.matryoshka.vm.core.adb.AdbStream
import dev.matryoshka.vm.core.adb.AdbSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

data class VideoInfo(val width: Int, val height: Int, val deviceName: String)

/**
 * Live screen of the virtual device.
 *
 * The guest renders with its own SurfaceFlinger; the scrcpy server (Apache-2.0, pushed into the
 * guest) captures the display, encodes it with the guest's encoder and streams H.264 over an
 * ADB tunnel. Here the stream is decoded by the *host's hardware* decoder (MediaCodec) straight
 * into a Surface — zero copies through the CPU, so it is cheap on battery and fast.
 * Touch, keys and text go back over the control socket.
 */
class DisplaySession(
    private val context: Context,
    private val conn: AdbConnection,
    private val maxSize: Int,
    private val bitRate: Int,
    private val maxFps: Int,
) {
    private val _info = MutableStateFlow<VideoInfo?>(null)
    val info: StateFlow<VideoInfo?> = _info.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var surface: Surface? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var server: AdbStream? = null
    private var video: AdbStream? = null
    private var control: AdbStream? = null
    private var config: ByteArray? = null
    private val controlQueue = LinkedBlockingQueue<ByteArray>(256)
    private var threads = emptyList<Thread>()

    /** Blocking: pushes the server, starts it and opens the tunnels. Call off the main thread. */
    fun start() {
        running = true
        val remoteJar = "/data/local/tmp/mvm-display.jar"
        AdbSync(conn.open("sync:")).use { sync ->
            val size = context.assets.openFd("scrcpy-server.jar").use { it.length }
            if (sync.stat(remoteJar)?.size != size) {
                context.assets.open("scrcpy-server.jar").use { sync.push(it, remoteJar) }
            }
        }
        val scid = "%08x".format(Random.nextInt(0x7fffffff))
        val args = listOf(
            "scid=$scid", "log_level=info", "tunnel_forward=true", "audio=false", "control=true",
            "cleanup=false", "video_codec=h264", "max_size=$maxSize", "max_fps=$maxFps",
            "video_bit_rate=$bitRate", "power_off_on_close=false", "clipboard_autosync=false",
            "stay_awake=false", "send_dummy_byte=true",
        ).joinToString(" ")
        server = conn.open(
            "exec:CLASSPATH=$remoteJar app_process / com.genymobile.scrcpy.Server ${BuildConfig.SCRCPY_SERVER_VERSION} $args"
        )
        threads = listOf(Thread({ drainServerLog() }, "display-server"))
        threads.forEach { it.isDaemon = true; it.start() }

        val socketName = "localabstract:scrcpy_$scid"
        video = openWithRetry(socketName)
        val v = DataInputStream(video!!.inputStream)
        v.readByte() // dummy byte: the tunnel is established
        control = openWithRetry(socketName)

        val nameBytes = ByteArray(64).also { v.readFully(it) }
        val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
        v.readInt() // codec id ("h264")
        val w = v.readInt()
        val h = v.readInt()
        _info.value = VideoInfo(w, h, name)

        threads = threads + Thread({ decodeLoop(v) }, "display-decoder") + Thread({ controlLoop() }, "display-control")
        threads.drop(1).forEach { it.isDaemon = true; it.start() }
    }

    private fun openWithRetry(name: String): AdbStream {
        repeat(50) {
            runCatching { return conn.open(name, 2_000) }
            Thread.sleep(100)
        }
        throw IOException("Display server did not start")
    }

    private fun drainServerLog() {
        val s = server ?: return
        try {
            while (running) {
                val chunk = s.readChunk() ?: break
                Log.i(TAG, String(chunk).trimEnd())
            }
        } catch (_: Exception) {
        }
    }

    /** Attach/replace the output surface (SurfaceView created / recreated). */
    @Synchronized
    fun setSurface(s: Surface?) {
        surface = s
        releaseCodec()
        if (s != null && _info.value != null) configureCodec(s)
    }

    @Synchronized
    private fun configureCodec(s: Surface) {
        val i = _info.value ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, i.width, i.height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            config?.let { setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, s, null, 0)
        c.start()
        codec = c
    }

    @Synchronized
    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }

    private fun decodeLoop(v: DataInputStream) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val ptsAndFlags = v.readLong()
                val size = v.readInt()
                val packet = ByteArray(size).also { v.readFully(it) }
                val isConfig = ptsAndFlags and FLAG_CONFIG != 0L
                val isKey = ptsAndFlags and FLAG_KEY_FRAME != 0L
                val pts = ptsAndFlags and PTS_MASK
                if (isConfig) {
                    // New SPS/PPS (first frame or rotation): restart the decoder with them.
                    config = packet
                    synchronized(this) {
                        releaseCodec()
                        surface?.let { configureCodec(it) }
                    }
                    continue
                }
                val c = codec ?: continue
                try {
                    val idx = c.dequeueInputBuffer(20_000)
                    if (idx >= 0) {
                        c.getInputBuffer(idx)!!.apply { clear(); put(packet) }
                        c.queueInputBuffer(idx, 0, size, pts, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                    while (true) {
                        val out = c.dequeueOutputBuffer(info, 0)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = c.outputFormat
                            val w = f.getInteger(MediaFormat.KEY_WIDTH)
                            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                            _info.value = _info.value?.copy(width = w, height = h)
                            continue
                        }
                        if (out < 0) break
                        c.releaseOutputBuffer(out, true)
                    }
                } catch (e: IllegalStateException) {
                    // Codec was swapped by setSurface() concurrently; next packet goes to the new one.
                }
            }
        } catch (e: Exception) {
            if (running) _error.value = "Display stream ended: ${e.message}"
        }
    }

    private fun controlLoop() {
        try {
            while (running) {
                val msg = controlQueue.take()
                if (msg.isEmpty()) break
                control?.write(msg)
            }
        } catch (_: Exception) {
        }
    }

    private fun send(msg: ByteArray) {
        if (!controlQueue.offer(msg)) {
            controlQueue.clear() // drop stale input rather than lag behind
            controlQueue.offer(msg)
        }
    }

    // ------------------------------------------------------------------ input API

    fun touch(action: Int, pointerId: Int, x: Int, y: Int, pressure: Float) {
        val i = _info.value ?: return
        send(ControlMessages.touch(action, pointerId.toLong(), x, y, i.width, i.height, pressure))
    }

    fun scroll(x: Int, y: Int, h: Float, v: Float) {
        val i = _info.value ?: return
        send(ControlMessages.scroll(x, y, i.width, i.height, h, v))
    }

    fun key(keyCode: Int, metaState: Int = 0) {
        send(ControlMessages.key(ControlMessages.ACTION_DOWN, keyCode, 0, metaState))
        send(ControlMessages.key(ControlMessages.ACTION_UP, keyCode, 0, metaState))
    }

    fun keyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int) {
        send(ControlMessages.key(action, keyCode, repeat, metaState))
    }

    fun text(text: String) {
        if (text.isNotEmpty()) send(ControlMessages.text(text))
    }

    fun stop() {
        running = false
        controlQueue.offer(ByteArray(0))
        setSurface(null)
        listOf(control, video, server).forEach { runCatching { it?.close() } }
    }

    companion object {
        private const val TAG = "MvmDisplay"
        private const val FLAG_CONFIG = 1L shl 63
        private const val FLAG_KEY_FRAME = 1L shl 62
        private const val PTS_MASK = FLAG_KEY_FRAME - 1
    }
}
