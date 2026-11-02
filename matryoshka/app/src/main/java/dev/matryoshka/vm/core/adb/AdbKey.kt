package dev.matryoshka.vm.core.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * RSA-2048 identity used to authenticate against the guest's adbd.
 * The public half is written into the guest's `/data/misc/adb/adb_keys` by the engines, so the
 * guest trusts *only* this app — other host apps cannot connect to the virtual device.
 */
class AdbKey(val keyPair: KeyPair) {

    /** Signs the 20-byte AUTH token: PKCS#1 v1.5 over a SHA-1 DigestInfo, as adbd expects. */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        cipher.update(SHA1_DIGEST_INFO)
        return cipher.doFinal(token)
    }

    /** Public key in the Android "mincrypt" format, base64 encoded, with a user@host suffix. */
    fun adbPublicKey(comment: String = "matryoshka@host"): String {
        val pub = keyPair.public as RSAPublicKey
        return Base64.getEncoder().encodeToString(encodeMincrypt(pub)) + " " + comment
    }

    companion object {
        private const val KEY_WORDS = 64 // 2048 bits / 32

        // ASN.1 DigestInfo prefix for SHA-1.
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun loadOrCreate(dir: File): AdbKey {
            dir.mkdirs()
            val privFile = File(dir, "adbkey.pk8")
            val pubFile = File(dir, "adbkey.x509")
            if (privFile.exists() && pubFile.exists()) {
                runCatching {
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                    return AdbKey(KeyPair(pub, priv))
                }
            }
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
            privFile.setReadable(false, false)
            privFile.setReadable(true, true)
            return AdbKey(kp)
        }

        fun encodeMincrypt(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            require(n.bitLength() <= KEY_WORDS * 32) { "Only RSA-2048 keys are supported" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
            val rr = BigInteger.ONE.shiftLeft(KEY_WORDS * 32 * 2).mod(n)

            val buf = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(KEY_WORDS)
            buf.putInt(n0inv.toInt())
            putLittleEndianWords(buf, n)
            putLittleEndianWords(buf, rr)
            buf.putInt(pub.publicExponent.toInt())
            return buf.array()
        }

        private fun putLittleEndianWords(buf: ByteBuffer, value: BigInteger) {
            val mask = BigInteger.valueOf(0xffffffffL)
            var v = value
            repeat(KEY_WORDS) {
                buf.putInt(v.and(mask).toInt())
                v = v.shiftRight(32)
            }
        }
    }
}
