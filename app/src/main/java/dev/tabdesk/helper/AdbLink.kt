package dev.tabdesk.helper

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A minimal adb client, so TabDesk can reach this tablet's own wireless debugging the way a computer does:
 * TLS with TabDesk's paired key, then adb's message protocol, just enough to run shell commands.
 */
internal class AdbLink private constructor(private val sock: Socket) : Closeable {
    /** Proof of concept: told about every message, for finding where things stall. */
    var trace: ((String) -> Unit)? = null
    private val inp = DataInputStream(sock.getInputStream())
    private val out = sock.getOutputStream()
    private var nextId = 1

    /** Runs [command] as the shell user and returns what it printed. */
    fun shell(command: String): String {
        val local = nextId++
        send(out, OPEN, local, 0, "shell:$command\u0000".toByteArray())
        val text = ByteArrayOutputStream()
        while (true) {
            val m = receive(inp)
            trace?.invoke("got ${tag(m.cmd)} ${m.arg0} ${m.arg1} (${m.data.size} bytes)")
            if (m.arg1 != local) continue   // left over from an earlier command
            when (m.cmd) {
                WRTE -> { text.write(m.data); send(out, OKAY, local, m.arg0) }
                CLSE -> break   // the command finished (adb doesn't expect a reply)
                // OKAY: the command started, or our reply was taken. Nothing to do.
            }
        }
        return text.toString(Charsets.UTF_8.name())
    }

    override fun close() = try { sock.close() } catch (_: Exception) {}

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        private const val CNXN = 0x4e584e43
        private const val STLS = 0x534c5453
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val WRTE = 0x45545257
        private const val CLSE = 0x45534c43
        private const val VERSION = 0x01000001
        private const val STLS_VERSION = 0x01000000
        private const val MAX_DATA = 1024 * 1024

        private fun tag(cmd: Int) = String(ByteArray(4) { (cmd shr (8 * it)).toByte() })

        /** Connects to wireless debugging on [port] of this tablet, as TabDesk's paired [key] with its [cert]. */
        fun connect(port: Int, key: PrivateKey, cert: X509Certificate): AdbLink {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress("127.0.0.1", port), 3000)
                raw.soTimeout = 15_000
                val rawIn = DataInputStream(raw.getInputStream())
                send(raw.getOutputStream(), CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
                val first = receive(rawIn)
                check(first.cmd == STLS) { "expected the switch to TLS, got ${Integer.toHexString(first.cmd)}" }
                send(raw.getOutputStream(), STLS, STLS_VERSION, 0)
                val ssl = adbTls(key, cert).socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket
                ssl.useClientMode = true
                ssl.soTimeout = 15_000
                ssl.startHandshake()
                val link = AdbLink(ssl)
                val hello = receive(link.inp)
                check(hello.cmd == CNXN) { "expected adb's hello, got ${Integer.toHexString(hello.cmd)}" }
                return link
            } catch (e: Exception) {
                raw.close(); throw e
            }
        }

        private fun send(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
            val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
                .putInt(data.sumOf { it.toInt() and 0xFF }).putInt(cmd.inv()).put(data)
            out.write(b.array()); out.flush()
        }

        private fun receive(inp: DataInputStream): Msg {
            val head = ByteArray(24).also { inp.readFully(it) }
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val arg0 = b.int; val arg1 = b.int; val len = b.int
            check(len in 0..MAX_DATA) { "bad adb message" }
            val data = ByteArray(len).also { inp.readFully(it) }
            return Msg(cmd, arg0, arg1, data)
        }
    }
}

/** TLS 1.3 as adb uses it: TabDesk shows its own certificate, and takes adb's (self-made) one as it is. */
internal fun adbTls(key: PrivateKey, cert: X509Certificate): SSLContext =
    SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keys(key, cert)), arrayOf(TrustAny), null) }

private fun keys(key: PrivateKey, cert: X509Certificate) = object : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "adb"
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "adb"
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("adb")
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?) = arrayOf(cert)
    override fun getPrivateKey(alias: String?) = key
}

/** Only ever used for this tablet itself. adb's certificate is self-made, so there's nothing to check it
 *  against; what matters is that adb accepts TabDesk's key, and a stand-in server would learn nothing from it. */
private object TrustAny : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** TabDesk's own adb key and certificate, kept where Android's cloud backup doesn't copy them. */
internal object AdbKey {
    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "adb")

    fun save(ctx: Context, pkcs8: ByteArray, certDer: ByteArray) {
        val d = dir(ctx).apply { mkdirs() }
        File(d, "key.der").writeBytes(pkcs8)
        File(d, "cert.der").writeBytes(certDer)
    }

    fun exists(ctx: Context) = File(dir(ctx), "key.der").exists() && File(dir(ctx), "cert.der").exists()

    fun load(ctx: Context): Pair<PrivateKey, X509Certificate>? {
        val d = dir(ctx)
        val k = File(d, "key.der").takeIf { it.exists() } ?: return null
        val c = File(d, "cert.der").takeIf { it.exists() } ?: return null
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k.readBytes()))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(c.inputStream()) as X509Certificate
        return key to cert
    }
}

internal const val ADB_CONNECT = "_adb-tls-connect._tcp"
/** Advertised only while Settings shows "Pair device with pairing code". */
internal const val ADB_PAIRING = "_adb-tls-pairing._tcp"

/**
 * Finds this tablet's wireless-debugging port, which changes each time wireless debugging is switched on, from
 * the service it advertises on Wi-Fi. Null if it isn't on (or there's no Wi-Fi). Blocks: call off the main thread.
 */
internal fun findAdbPort(ctx: Context, service: String = ADB_CONNECT, timeoutMs: Long = 6000, log: (String) -> Unit = {}): Int? {
    val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
    val mine = (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
        .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    var port: Int? = null
    val done = CountDownLatch(1)
    val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val hosts = (if (Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host))
                        .mapNotNull { it.hostAddress?.substringBefore('%') }
                    val ours = hosts.any { it in mine }
                    log("found ${r.serviceName} at $hosts:${r.port}${if (ours) " (this tablet)" else ""}")
                    if (ours) { port = r.port; done.countDown() }
                }
                override fun onResolveFailed(r: NsdServiceInfo, error: Int) { log("couldn't resolve ${r.serviceName}: $error") }
            })
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, error: Int) { log("discovery failed: $error"); done.countDown() }
        override fun onStopDiscoveryFailed(type: String, error: Int) {}
    }
    nsd.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, discovery)
    done.await(timeoutMs, TimeUnit.MILLISECONDS)
    try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) {}
    return port
}
