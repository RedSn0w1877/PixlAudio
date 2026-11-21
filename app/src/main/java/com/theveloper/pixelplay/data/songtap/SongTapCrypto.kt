package com.theveloper.pixelplay.data.songtap

import com.theveloper.pixelplay.songtap.SongTapSignatureVerifier
import com.theveloper.pixelplay.songtap.SongTapSigning
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * The cryptography Song Tap's core leaves to the app: ECDSA P-256 with SHA-256, keys as an uncompressed
 * point (65 bytes) and signatures as r then s (64 bytes). CryptoKit's `P256.Signing` writes and reads the
 * same two forms, so a Pixel and an iPhone verify each other.
 *
 * A [SongTapSigner] is made per session and thrown away: no key outlives it, so nothing stable identifies a
 * phone from one beam to the next.
 */
class SongTapSigner private constructor(private val pair: KeyPair) {

    val publicKey: ByteArray = uncompressed(pair.public as ECPublicKey)

    /** r then s, 32 bytes each. */
    fun sign(message: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(pair.private)
        sig.update(message)
        return derToRaw(sig.sign())
    }

    companion object {
        fun create(): SongTapSigner {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            return SongTapSigner(gen.generateKeyPair())
        }

        private fun uncompressed(key: ECPublicKey): ByteArray {
            val out = ByteArray(SongTapSigning.PUBLIC_KEY_BYTES)
            out[0] = 4
            fixed(key.w.affineX).copyInto(out, 1)
            fixed(key.w.affineY).copyInto(out, 33)
            return out
        }

        private fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val out = ByteArray(32)
            val n = minOf(raw.size, 32)
            raw.copyInto(out, 32 - n, raw.size - n, raw.size)
            return out
        }

        /** DER `SEQUENCE { INTEGER r, INTEGER s }` to 64 raw bytes. */
        internal fun derToRaw(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F
            require(der[i].toInt() == 0x02)
            val rLen = der[i + 1].toInt() and 0xFF
            val r = der.copyOfRange(i + 2, i + 2 + rLen)
            i += 2 + rLen
            require(der[i].toInt() == 0x02)
            val sLen = der[i + 1].toInt() and 0xFF
            val s = der.copyOfRange(i + 2, i + 2 + sLen)
            val out = ByteArray(64)
            fixed(BigInteger(1, r)).copyInto(out, 0)
            fixed(BigInteger(1, s)).copyInto(out, 32)
            return out
        }
    }
}

/** Checks a signature made by a [SongTapSigner] or by CryptoKit. False for anything malformed. */
object SongTapVerifier : SongTapSignatureVerifier {

    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != SongTapSigning.PUBLIC_KEY_BYTES || publicKey[0].toInt() != 4) return false
        if (signature.size != SongTapSigning.SIGNATURE_BYTES) return false
        return try {
            val point = ECPoint(BigInteger(1, publicKey.copyOfRange(1, 33)), BigInteger(1, publicKey.copyOfRange(33, 65)))
            val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(message)
            sig.verify(rawToDer(signature))
        } catch (_: Exception) {
            false
        }
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
            val body = bytes.copyOfRange(start, bytes.size)
            val padded = if (body[0].toInt() and 0x80 != 0) byteArrayOf(0) + body else body
            return byteArrayOf(0x02, padded.size.toByte()) + padded
        }
        val r = integer(raw.copyOfRange(0, 32))
        val s = integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }
}
