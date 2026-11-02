package dev.matryoshka.vm.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread

class AdbProtocolTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun messageRoundTrip() {
        val msg = AdbMessage(AdbMessage.A_WRTE, 7, 9, "hello".toByteArray())
        val bytes = msg.encode()
        assertEquals(24 + 5, bytes.size)
        val back = AdbMessage.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(AdbMessage.A_WRTE, back.command)
        assertEquals(7, back.arg0)
        assertEquals(9, back.arg1)
        assertArrayEquals("hello".toByteArray(), back.payload)
        assertEquals("WRTE", AdbMessage.commandName(back.command))
        // magic = command ^ 0xffffffff, checksum = byte sum
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("hello".sumOf { it.code }, h.getInt(16))
        assertEquals(AdbMessage.A_WRTE xor -1, h.getInt(20))
    }

    @Test fun mincryptPublicKeyLayout() {
        val key = AdbKey.loadOrCreate(tmp.newFolder())
        val pub = key.keyPair.public as RSAPublicKey
        val raw = AdbKey.encodeMincrypt(pub)
        assertEquals(4 + 4 + 256 + 256 + 4, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.getInt(0))
        // n0inv * n[0] == -1 (mod 2^32)
        val n0inv = b.getInt(4).toLong() and 0xffffffffL
        val n0 = b.getInt(8).toLong() and 0xffffffffL
        assertEquals(0xffffffffL, (n0inv * n0) and 0xffffffffL)
        assertEquals(65537, b.getInt(4 + 4 + 512))
        // modulus words are little-endian
        val nBytes = ByteArray(256) { raw[8 + 255 - it] }
        assertEquals(pub.modulus, BigInteger(1, nBytes))
        assertTrue(key.adbPublicKey().endsWith(" matryoshka@host"))
        Base64.getDecoder().decode(key.adbPublicKey().substringBefore(' '))
    }

    @Test fun keyIsPersisted() {
        val dir = tmp.newFolder()
        val a = AdbKey.loadOrCreate(dir)
        val b = AdbKey.loadOrCreate(dir)
        assertEquals(a.adbPublicKey(), b.adbPublicKey())
    }

    @Test fun signatureIsPkcs1Sha1OverToken() {
        val key = AdbKey.loadOrCreate(tmp.newFolder())
        val token = ByteArray(20) { it.toByte() }
        val sig = key.sign(token)
        assertEquals(256, sig.size)
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key.keyPair.public) }
        val plain = c.doFinal(sig)
        assertEquals(15 + 20, plain.size)
        assertArrayEquals(token, plain.copyOfRange(15, 35))
    }

    /** Talks to an in-process fake adbd: auth handshake, exec stream, flow control, close. */
    @Test fun connectAuthenticateAndExec() {
        val key = AdbKey.loadOrCreate(tmp.newFolder())
        val server = ServerSocket(0)
        var receivedService = ""
        val device = thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val cnxn = AdbMessage.read(input)
                assertEquals(AdbMessage.A_CNXN, cnxn.command)
                val token = ByteArray(20) { (it * 3).toByte() }
                AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_TOKEN, 0, token).writeTo(out)
                val auth = AdbMessage.read(input)
                assertEquals(AdbMessage.AUTH_SIGNATURE, auth.arg0)
                val plain = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                    .apply { init(Cipher.DECRYPT_MODE, key.keyPair.public) }.doFinal(auth.payload)
                assertArrayEquals(token, plain.copyOfRange(15, 35))
                AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, 4096, "device::ro.product.model=Matryoshka VM;".toByteArray()).writeTo(out)

                val open = AdbMessage.read(input)
                assertEquals(AdbMessage.A_OPEN, open.command)
                receivedService = String(open.payload).trimEnd('\u0000')
                val local = open.arg0
                val remote = 42
                AdbMessage(AdbMessage.A_OKAY, remote, local).writeTo(out)
                // Two writes; each must be acknowledged by the host before the next.
                AdbMessage(AdbMessage.A_WRTE, remote, local, "hello ".toByteArray()).writeTo(out)
                assertEquals(AdbMessage.A_OKAY, AdbMessage.read(input).command)
                AdbMessage(AdbMessage.A_WRTE, remote, local, "world".toByteArray()).writeTo(out)
                assertEquals(AdbMessage.A_OKAY, AdbMessage.read(input).command)
                AdbMessage(AdbMessage.A_CLSE, remote, local).writeTo(out)
                // Host may answer the close; drain until the socket ends.
                runCatching { while (true) AdbMessage.read(input) }
            }
        }

        val conn = AdbConnection.connect("127.0.0.1", server.localPort, key)
        assertEquals("Matryoshka VM", conn.bannerProps["ro.product.model"])
        val text = conn.open("exec:echo hi").use { it.readAllText() }
        assertEquals("hello world", text)
        assertEquals("exec:echo hi", receivedService)
        conn.close()
        device.join(5000)
        server.close()
    }
}
