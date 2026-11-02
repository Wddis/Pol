package dev.matryoshka.vm.ui.device

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.matryoshka.vm.core.display.ControlMessages
import dev.matryoshka.vm.core.display.DisplaySession

/**
 * Hosts the decoded guest screen and forwards input: multi-touch, mouse wheel, hardware keys and
 * the host's soft keyboard (committed text is injected as text, deletes as KEYCODE_DEL).
 */
@SuppressLint("ViewConstructor")
class GuestSurfaceView(context: Context, private val session: DisplaySession) : SurfaceView(context), SurfaceHolder.Callback {

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = session.setSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = session.setSurface(null)

    private fun mapX(x: Float): Int {
        val w = session.info.value?.width ?: return 0
        return (x / width * w).toInt().coerceIn(0, w - 1)
    }

    private fun mapY(y: Float): Int {
        val h = session.info.value?.height ?: return 0
        return (y / height * h).toInt().coerceIn(0, h - 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestFocus()
                val i = e.actionIndex
                session.touch(ControlMessages.ACTION_DOWN, e.getPointerId(i), mapX(e.getX(i)), mapY(e.getY(i)), e.getPressure(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    session.touch(ControlMessages.ACTION_MOVE, e.getPointerId(i), mapX(e.getX(i)), mapY(e.getY(i)), e.getPressure(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                session.touch(ControlMessages.ACTION_UP, e.getPointerId(i), mapX(e.getX(i)), mapY(e.getY(i)), 0f)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) {
                    session.touch(ControlMessages.ACTION_UP, e.getPointerId(i), mapX(e.getX(i)), mapY(e.getY(i)), 0f)
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            session.scroll(mapX(e.x), mapY(e.y), e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event) || super.onKeyUp(keyCode, event)

    private fun forwardKey(event: KeyEvent): Boolean {
        // Volume keys keep controlling the host; everything else goes to the guest.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.source and android.view.InputDevice.SOURCE_KEYBOARD == 0) return false
        val action = if (event.action == KeyEvent.ACTION_DOWN) ControlMessages.ACTION_DOWN else ControlMessages.ACTION_UP
        session.keyEvent(action, event.keyCode, event.repeatCount, event.metaState)
        return true
    }

    // ---- soft keyboard

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.toString().split('\n').forEachIndexed { i, part ->
                    if (i > 0) session.key(KeyEvent.KEYCODE_ENTER)
                    session.text(part)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { session.key(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength) { session.key(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = forwardKey(event)

            override fun performEditorAction(actionCode: Int): Boolean {
                session.key(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    fun toggleKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (!imm.isActive(this) || !isFocused) imm.showSoftInput(this, 0)
        else imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }
}
