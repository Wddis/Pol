package dev.matryoshka.vm.core.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One packet of the ADB transport protocol (see AOSP `packages/modules/adb/protocol.txt`).
 * Clean-room implementation; header is six little-endian u32 values.
 */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum(payload))
        buf.putInt(command xor -0x1)
        buf.put(payload)
        return buf.array()
    }

    fun writeTo(out: OutputStream) {
        out.write(encode())
        out.flush()
    }

    override fun toString(): String =
        "AdbMessage(${commandName(command)}, $arg0, $arg1, ${payload.size} bytes)"

    companion object {
        const val HEADER_SIZE = 24

        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        /** Version that allows skipping the payload checksum. */
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 1024 * 1024

        val EMPTY = ByteArray(0)

        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += b.toInt() and 0xff
            return sum
        }

        fun read(input: DataInputStream): AdbMessage {
            val header = ByteArray(HEADER_SIZE)
            input.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = h.int
            val arg0 = h.int
            val arg1 = h.int
            val length = h.int
            h.int // checksum: optional since protocol version 0x01000001
            val magic = h.int
            if (magic != command xor -0x1) throw IOException("Bad ADB magic for ${commandName(command)}")
            if (length < 0 || length > MAX_PAYLOAD * 2) throw IOException("Bad ADB payload size $length")
            val payload = if (length == 0) EMPTY else ByteArray(length).also { input.readFully(it) }
            return AdbMessage(command, arg0, arg1, payload)
        }

        fun commandName(cmd: Int): String {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).array()
            return String(b, Charsets.US_ASCII)
        }
    }
}
