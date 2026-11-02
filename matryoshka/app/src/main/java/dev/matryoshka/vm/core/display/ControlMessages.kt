package dev.matryoshka.vm.core.display

import java.nio.ByteBuffer

/**
 * Binary control messages understood by the scrcpy 2.x server running in the guest
 * (big-endian, see scrcpy `ControlMessageReader`).
 */
object ControlMessages {
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TEXT = 1
    private const val TYPE_INJECT_TOUCH = 2
    private const val TYPE_INJECT_SCROLL = 3

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    fun key(action: Int, keyCode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray =
        ByteBuffer.allocate(14)
            .put(TYPE_INJECT_KEYCODE.toByte())
            .put(action.toByte())
            .putInt(keyCode)
            .putInt(repeat)
            .putInt(metaState)
            .array()

    fun text(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8).let { if (it.size > 300) it.copyOf(300) else it }
        return ByteBuffer.allocate(5 + bytes.size)
            .put(TYPE_INJECT_TEXT.toByte())
            .putInt(bytes.size)
            .put(bytes)
            .array()
    }

    fun touch(action: Int, pointerId: Long, x: Int, y: Int, screenW: Int, screenH: Int, pressure: Float): ByteArray =
        ByteBuffer.allocate(32)
            .put(TYPE_INJECT_TOUCH.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(screenW.toShort())
            .putShort(screenH.toShort())
            .putShort(toFixedPoint16(pressure))
            .putInt(0) // action button
            .putInt(0) // buttons
            .array()

    fun scroll(x: Int, y: Int, screenW: Int, screenH: Int, hScroll: Float, vScroll: Float): ByteArray =
        ByteBuffer.allocate(21)
            .put(TYPE_INJECT_SCROLL.toByte())
            .putInt(x)
            .putInt(y)
            .putShort(screenW.toShort())
            .putShort(screenH.toShort())
            .putShort(toSignedFixedPoint16(hScroll))
            .putShort(toSignedFixedPoint16(vScroll))
            .putInt(0) // buttons
            .array()

    /** [0, 1] -> u16 fixed point, 1.0 maps to 0xffff. */
    fun toFixedPoint16(v: Float): Short {
        val c = v.coerceIn(0f, 1f)
        val u = if (c >= 1f) 0xffff else (c * 0x10000).toInt()
        return u.toShort()
    }

    /** [-1, 1] -> i16 fixed point. */
    fun toSignedFixedPoint16(v: Float): Short {
        val c = v.coerceIn(-1f, 1f)
        val i = if (c >= 1f) 0x7fff else (c * 0x8000).toInt()
        return i.toShort()
    }
}
