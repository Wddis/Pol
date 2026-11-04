// SPDX-License-Identifier: Apache-2.0
package dev.matryoshka.vm.core.nest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Shows the guest's screen: the engine's Wayland server writes every frame the guest composer
 * presents into STATE/display.fb (64-byte header "NFB1" + R,G,B,A pixels, see
 * nest/src/wayland_server.h). A background thread copies each new frame straight into this
 * SurfaceView's buffer, sized like the guest's display: the phone's compositor scales it, the app's
 * UI thread and GPU do no per-frame work. Touches go back as datagrams "d|u|m ID X Y" (display
 * pixels) on STATE/input.sock.
 */
class NestDisplayView(context: Context, private val stateDir: File, private val waitingText: String) :
    SurfaceView(context), SurfaceHolder.Callback {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 14f * context.resources.displayMetrics.scaledDensity
    }
    private var map: MappedByteBuffer? = null
    private var mappedSize = 0L
    private var bitmap: Bitmap? = null
    private var lastSeq = -1L
    @Volatile private var fbW = 0
    @Volatile private var fbH = 0
    @Volatile private var surfaceReady = false
    @Volatile private var bufW = 0
    @Volatile private var bufH = 0
    @Volatile private var running = false
    private var reader: Thread? = null
    private val input = Executors.newSingleThreadExecutor()
    private var sock: LocalSocket? = null

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    private var native = 0L

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        lastSeq = -1L  // repaint the current frame into the new surface
        // Preferred: the native viewer maps the composer's buffers itself (one copy per frame);
        // the framebuffer file (Java path) is the fallback.
        if (NestNativeView.available) {
            native = runCatching { NestNativeView.start(h.surface, File(stateDir, "display.sock").absolutePath) }.getOrDefault(0L)
            if (native != 0L) {
                fbW = NestGuest.displayWidth
                fbH = NestGuest.displayHeight
                return
            }
        }
        running = true
        reader = Thread({ loop() }, "nest-display").apply { isDaemon = true; start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        lastSeq = -1L
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        if (native != 0L) {
            NestNativeView.stop(native)
            native = 0L
        }
        running = false
        reader?.interrupt()
        runCatching { reader?.join(500) }
    }

    override fun onDetachedFromWindow() {
        input.execute { runCatching { sock?.close() }; sock = null }
        super.onDetachedFromWindow()
    }

    private fun loop() {
        var waitingShown = false
        while (running) {
            val fresh = runCatching { poll() }.getOrDefault(false)
            if (fresh || (bitmap != null && bufW == 0)) {
                present()
            } else if (bitmap == null && !waitingShown) {
                waitingShown = drawWaiting()
            }
            try {
                Thread.sleep(if (fresh) 2L else 6L)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun drawWaiting(): Boolean {
        if (!surfaceReady) return false
        val c = runCatching { holder.lockCanvas() }.getOrNull() ?: return false
        try {
            c.drawColor(Color.BLACK)
            c.drawText(waitingText, c.width / 2f, c.height / 2f, textPaint)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
        return true
    }

    private fun present() {
        val bmp = bitmap ?: return
        if (!surfaceReady) return
        // Buffer the size of the guest's display: one plain copy per frame, scaled by the compositor.
        if (bufW != bmp.width || bufH != bmp.height) {
            bufW = bmp.width
            bufH = bmp.height
            post { holder.setFixedSize(bufW, bufH) }  // UI thread only
            return  // the next frame goes into the resized buffer
        }
        val c = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            c.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
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
        if (w <= 0 || h <= 0 || format != FMT_ABGR || stride != w * 4 || HEADER + stride.toLong() * h > mappedSize) return false
        val bmp = bitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val b = m.duplicate()
        b.position(HEADER)
        b.limit(HEADER + stride * h)
        bmp.copyPixelsFromBuffer(b)  // R,G,B,A bytes: the bitmap's own layout
        fbW = w
        fbH = h
        lastSeq = seq
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fbW == 0 || width == 0 || height == 0) return true
        // the surface buffer is stretched over the whole view (its aspect ratio is the guest's)
        val sx = fbW / width.toFloat()
        val sy = fbH / height.toFloat()
        val msgs = mutableListOf<String>()
        fun at(i: Int) = "${e.getPointerId(i)} ${e.getX(i) * sx} ${e.getY(i) * sy}"
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> msgs += "d " + at(e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> msgs += "u " + at(e.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) msgs += "u " + at(i)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) msgs += "m " + at(i)
        }
        if (msgs.isNotEmpty()) input.execute { msgs.forEach(::send) }
        return true
    }

    /** A key of the guest's keyboard: Linux key code (input-event-codes.h). */
    fun sendKey(code: Int, pressed: Boolean) = input.execute { send("k $code ${if (pressed) 1 else 0}") }

    fun tapKey(code: Int, shift: Boolean = false) = input.execute {
        if (shift) send("k $KEY_LEFTSHIFT 1")
        send("k $code 1")
        send("k $code 0")
        if (shift) send("k $KEY_LEFTSHIFT 0")
    }

    /** Text from the phone's keyboard, typed into the guest key by key (US layout, ASCII). */
    fun typeText(text: CharSequence) {
        for (ch in text) {
            val (code, shift) = NestKeys.forChar(ch) ?: continue
            tapKey(code, shift)
        }
    }

    fun toggleKeyboard() {
        isFocusableInTouchMode = true
        requestFocus()
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { typeText(text); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 64)) { tapKey(KEY_BACKSPACE) }
                return true
            }
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
            override fun performEditorAction(actionCode: Int): Boolean { tapKey(KEY_ENTER); return true }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        NestKeys.forAndroidKey(keyCode)?.let { tapKey(it); return true }
        val ch = event.unicodeChar
        if (ch != 0) { typeText(ch.toChar().toString()); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun send(msg: String) {
        val s = sock ?: runCatching {
            LocalSocket(LocalSocket.SOCKET_DGRAM).apply {
                connect(LocalSocketAddress(File(stateDir, "input.sock").absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            }
        }.getOrNull()?.also { sock = it } ?: return
        runCatching { s.outputStream.write(msg.toByteArray()) }.onFailure { runCatching { s.close() }; sock = null }
    }

    companion object {
        const val KEY_BACK = 158
        const val KEY_HOMEPAGE = 172
        const val KEY_APPSELECT = 580
        const val KEY_VOLUMEDOWN = 114
        const val KEY_VOLUMEUP = 115
        const val KEY_POWER = 116
        const val KEY_ENTER = 28
        const val KEY_BACKSPACE = 14
        const val KEY_LEFTSHIFT = 42
        private const val HEADER = 64
        private const val MAGIC = 0x3142464e
        private const val FMT_ABGR = 0x34324241
    }
}
