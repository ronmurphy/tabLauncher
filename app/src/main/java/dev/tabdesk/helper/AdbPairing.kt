package dev.tabdesk.helper

import android.net.ssl.SSLSockets
import android.util.Base64
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * Pairs TabDesk with this tablet's wireless debugging, as "Pair device with pairing code" expects a computer to:
 * TLS 1.3, then SPAKE2 over the 6-digit code (so neither side learns anything without it), then each side sends
 * its key, encrypted. Written to match Android's adb (packages/modules/adb, pairing_auth and pairing_connection)
 * and BoringSSL's SPAKE2; tools/pairing/fake_pair_server.py is a stand-in tablet for testing it.
 */
internal object AdbPairing {
    class WrongCode : Exception("the pairing code didn't match")

    private const val SPAKE2_MSG = 0
    private const val PEER_INFO = 1
    private const val PEER_INFO_SIZE = 8192

    /** Pairs [key] (shown to adb as [publicKey], see [adbPublicKey]) with the pairing service on [host]:[port].
     *  Returns the tablet's id. Blocks: call off the main thread. */
    fun pair(host: String, port: Int, code: String, key: PrivateKey, cert: X509Certificate, publicKey: String): String {
        Socket().use { raw ->
            raw.connect(InetSocketAddress(host, port), 3000)
            raw.soTimeout = 15_000
            val ssl = adbTls(key, cert).socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.startHandshake()
            // Ties the code to this very connection, so it can't be relayed to another one.
            val bound = SSLSockets.exportKeyingMaterial(ssl, "adb-label\u0000", null, 64) ?: error("TLS key export failed")
            val spake = Spake2(code.toByteArray() + bound)
            val out = ssl.outputStream
            val inp = DataInputStream(ssl.inputStream)

            send(out, SPAKE2_MSG, spake.msg)
            val cipher = Gcm(spake.finish(receive(inp, SPAKE2_MSG)) ?: throw WrongCode())

            val mine = ByteArray(PEER_INFO_SIZE)   // type 0: an adb public key, then its text, NUL-padded
            val text = publicKey.toByteArray()
            require(text.size < PEER_INFO_SIZE - 1)
            text.copyInto(mine, 1)
            send(out, PEER_INFO, cipher.seal(mine))
            // With a wrong code the tablet can't read TabDesk's key and just hangs up.
            val theirs = try { cipher.open(receive(inp, PEER_INFO)) } catch (_: EOFException) { throw WrongCode() }
                catch (_: AEADBadTagException) { throw WrongCode() }
            check(theirs.size == PEER_INFO_SIZE && theirs[0].toInt() == 1) { "unexpected reply from the tablet" }
            return String(theirs, 1, theirs.indexOf(0, 1) - 1)
        }
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int = (from until size).firstOrNull { this[it] == b } ?: size

    private fun send(out: OutputStream, type: Int, payload: ByteArray) {
        val head = ByteBuffer.allocate(6).put(1.toByte()).put(type.toByte()).putInt(payload.size)   // version 1, big-endian size
        out.write(head.array() + payload); out.flush()
    }

    private fun receive(inp: DataInputStream, type: Int): ByteArray {
        val version = inp.readUnsignedByte()
        val got = inp.readUnsignedByte()
        val size = inp.readInt()
        check(version == 1 && got == type && size in 1..PEER_INFO_SIZE * 2) { "unexpected message from the tablet" }
        return ByteArray(size).also { inp.readFully(it) }
    }

    /** AES-128-GCM keyed from SPAKE2's result, with a counter for the nonce in each direction. */
    private class Gcm(keyMaterial: ByteArray) {
        private val key = SecretKeySpec(hkdf(keyMaterial, "adb pairing_auth aes-128-gcm key".toByteArray(), 16), "AES")
        private var sent = 0L
        private var received = 0L

        fun seal(data: ByteArray) = run(Cipher.ENCRYPT_MODE, sent++, data)
        fun open(data: ByteArray) = run(Cipher.DECRYPT_MODE, received++, data)

        private fun run(mode: Int, counter: Long, data: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, nonce)) }.doFinal(data)
        }

        /** HKDF-SHA256 with no salt (RFC 5869). */
        private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            return mac.doFinal(info + 1.toByte()).copyOf(length)
        }
    }

    /** An RSA key as adb writes it in adbkey.pub: base64 of Android's RSAPublicKey struct, then a name. */
    fun adbPublicKey(pub: RSAPublicKey, name: String): String {
        val n = pub.modulus
        require(n.bitLength() <= 2048)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()   // -1/n mod 2^32
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)                  // (2^2048)^2 mod n
        val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(n.le(256)).put(rr.le(256)).putInt(pub.publicExponent.toInt())
        return Base64.encodeToString(b.array(), Base64.NO_WRAP) + " " + name
    }

    /** A new RSA-2048 key (adb's size) and a self-signed certificate for it, for TLS. */
    fun newKey(name: String): Pair<PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cn = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0c, name.toByteArray()))))
        val sha256Rsa = der(0x30, der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)) + der(0x05, ByteArray(0)))
        val tbs = der(0x30,
            der(0xa0, der(0x02, byteArrayOf(2))) +                                 // X.509 v3
            der(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray()) +
            sha256Rsa + cn +
            der(0x30, der(0x17, "250101000000Z".toByteArray()) + der(0x17, "491231235959Z".toByteArray())) +
            cn + pair.public.encoded)
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }.sign()
        val certDer = der(0x30, tbs + sha256Rsa + der(0x03, byteArrayOf(0) + sig))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
        return pair.private to cert
    }

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }
}

/** [this] as [size] little-endian bytes. */
private fun BigInteger.le(size: Int): ByteArray {
    val be = toByteArray()
    return ByteArray(size) { i -> if (i < be.size) be[be.size - 1 - i] else 0 }
}

private fun leInt(b: ByteArray) = BigInteger(1, b.reversedArray())

/**
 * BoringSSL's SPAKE2 over edwards25519 (crypto/curve25519/spake25519.cc), as the client ("alice"), including its
 * fix that makes the password scalar a multiple of eight. Plain BigInteger arithmetic: slow, but it runs once.
 */
private class Spake2(password: ByteArray) {
    private val pwHash = MessageDigest.getInstance("SHA-512").digest(password)
    private val pwScalar = leInt(pwHash).mod(L).let { s0 ->
        var s = s0
        for (bit in 0..2) if (s.testBit(bit)) s = s.add(L.shiftLeft(bit))
        s
    }
    private val private = leInt(ByteArray(64).also { SecureRandom().nextBytes(it) }).mod(L).shiftLeft(3)
    val msg: ByteArray = mul(private, BASE).add(mul(pwScalar, M)).encode()

    /** The shared key, or null if [theirs] isn't a point on the curve. */
    fun finish(theirs: ByteArray): ByteArray? {
        if (theirs.size != 32) return null
        val q = decode(theirs) ?: return null
        val shared = mul(private, q.add(mul(pwScalar, N).negate())).encode()
        val sha = MessageDigest.getInstance("SHA-512")
        for (part in listOf(CLIENT, SERVER, msg, theirs, shared, pwHash)) {
            sha.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(part.size.toLong()).array())
            sha.update(part)
        }
        return sha.digest()
    }

    /** A point in extended coordinates: x = X/Z, y = Y/Z, xy = T/Z. (`%` is Kotlin's remainder, which can be
     *  negative; that's fine in between, and [encode] and [decode] reduce properly.) */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        /** Twisted Edwards addition (a = -1), complete, so it doubles too. */
        fun add(o: Pt): Pt {
            val a = (y - x) * (o.y - o.x) % P
            val b = (y + x) * (o.y + o.x) % P
            val c = t * D2 % P * o.t % P
            val d = z * o.z * TWO % P
            val e = b - a; val f = d - c; val g = d + c; val h = b + a
            return Pt(e * f % P, g * h % P, f * g % P, e * h % P)
        }
        fun negate() = Pt(x.negate().mod(P), y, z, t.negate().mod(P))
        fun encode(): ByteArray {
            val zi = z.mod(P).modInverse(P)
            val ax = (x * zi).mod(P)
            val ay = (y * zi).mod(P)
            return (if (ax.testBit(0)) ay.setBit(255) else ay).le(32)
        }
    }

    private companion object {
        val TWO: BigInteger = BigInteger.valueOf(2)
        val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
        val D2: BigInteger = D * TWO % P
        val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        val ZERO_PT = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val CLIENT = "adb pair client\u0000".toByteArray()
        val SERVER = "adb pair server\u0000".toByteArray()

        val BASE = decode(BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P).le(32))!!
        val M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        val N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun mul(k: BigInteger, p: Pt): Pt {
            var r = ZERO_PT
            for (i in k.bitLength() - 1 downTo 0) {
                r = r.add(r)
                if (k.testBit(i)) r = r.add(p)
            }
            return r
        }

        fun decode(s: ByteArray): Pt? {
            val y = leInt(s).clearBit(255)
            if (y >= P) return null
            val sign = (s[31].toInt() shr 7) and 1
            val u = (y * y - BigInteger.ONE).mod(P)
            val v = (D * y * y + BigInteger.ONE).mod(P)
            val uv = u * v.modInverse(P) % P
            var x = uv.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if ((x * x - uv).mod(P).signum() != 0) x = x * SQRT_M1 % P
            if ((x * x - uv).mod(P).signum() != 0) return null
            if ((if (x.testBit(0)) 1 else 0) != sign) x = P - x
            x = x.mod(P)
            return Pt(x, y, BigInteger.ONE, x * y % P)
        }
    }
}
