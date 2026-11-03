// SPDX-License-Identifier: Apache-2.0
package dev.matryoshka.vm.core.nest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Shows the guest's screen: the engine's Wayland server writes every frame the guest composer
 * presents into STATE/display.fb (64-byte header "NFB1" + pixels, see nest/src/wayland_server.h).
 * Touches go back as datagrams "d|u|m ID X Y" (display pixels) on STATE/input.sock.
 */
class NestDisplayView(context: Context, private val stateDir: File, private val waitingText: String) : View(context) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 14f * context.resources.displayMetrics.scaledDensity
    }
    private var map: MappedByteBuffer? = null
    private var mappedSize = 0L
    private var bitmap: Bitmap? = null
    private var lastSeq = -1L
    private var fbW = 0
    private var fbH = 0
    private val dst = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val input = Executors.newSingleThreadExecutor()
    private var sock: LocalSocket? = null

    private val tick = object : Runnable {
        override fun run() {
            if (poll()) invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        input.execute { runCatching { sock?.close() }; sock = null }
        super.onDetachedFromWindow()
    }

    /** Copies a new frame into the bitmap; true when there is one. */
    private fun poll(): Boolean {
        val f = File(stateDir, "display.fb")
        if (map == null || f.length() != mappedSize) {
            map = null
            if (f.length() < HEADER) return false
            runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    mappedSize = raf.length()
                    map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, mappedSize)
                    map!!.order(ByteOrder.LITTLE_ENDIAN)
                }
            }.onFailure { return false }
        }
        val m = map ?: return false
        if (m.getInt(0) != MAGIC) return false
        val seq = m.getLong(24)
        if (seq == lastSeq) return false
        val w = m.getInt(4)
        val h = m.getInt(8)
        val stride = m.getInt(12)
        val format = m.getInt(16)
        if (w <= 0 || h <= 0 || HEADER + stride.toLong() * h > mappedSize) return false
        val bmp = bitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
        fbW = w; fbH = h
        val row = IntArray(w)
        val px = IntArray(w * h)
        val b = m.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) {
            b.position(HEADER + y * stride)
            b.asIntBuffer().get(row)
            val o = y * w
            for (x in 0 until w) {
                val v = row[x]
                // wl_shm ARGB/XRGB words are 0xAARRGGBB (= Android's ColorInt); ABGR/XBGR are 0xAABBGGRR.
                val argb = if (format == FMT_ARGB || format == FMT_XRGB) v
                else (v and 0xff00ff00.toInt()) or ((v and 0xff) shl 16) or ((v ushr 16) and 0xff)
                px[o + x] = if (format == FMT_XRGB || format == FMT_XBGR) argb or 0xff000000.toInt() else argb
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        lastSeq = seq
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = bitmap ?: run {
            canvas.drawText(waitingText, width / 2f, height / 2f, textPaint)
            return
        }
        val s = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dw = (bmp.width * s).toInt()
        val dh = (bmp.height * s).toInt()
        dst.set((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fbW == 0 || dst.width() == 0) return true
        val sx = fbW / dst.width().toFloat()
        val sy = fbH / dst.height().toFloat()
        val msgs = mutableListOf<String>()
        fun at(i: Int) = "${e.getPointerId(i)} ${(e.getX(i) - dst.left) * sx} ${(e.getY(i) - dst.top) * sy}"
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> msgs += "d " + at(e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> msgs += "u " + at(e.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) msgs += "u " + at(i)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) msgs += "m " + at(i)
        }
        if (msgs.isNotEmpty()) input.execute { msgs.forEach(::send) }
        return true
    }

    private fun send(msg: String) {
        val s = sock ?: runCatching {
            LocalSocket(LocalSocket.SOCKET_DGRAM).apply {
                connect(LocalSocketAddress(File(stateDir, "input.sock").absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            }
        }.getOrNull()?.also { sock = it } ?: return
        runCatching { s.outputStream.write(msg.toByteArray()) }.onFailure { runCatching { s.close() }; sock = null }
    }

    private companion object {
        const val HEADER = 64
        const val MAGIC = 0x3142464e
        const val FMT_ARGB = 0
        const val FMT_XRGB = 1
        const val FMT_ABGR = 0x34324241
        const val FMT_XBGR = 0x34324258
    }
}
