package dev.tabdesk.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * TabDesk's side of the helper ([Helper]): starts it through wireless debugging when it isn't running (switching
 * wireless debugging on for the moment if it's off), stays connected, and passes on the running tasks it reports.
 */
internal object HelperClient {
    private const val TAG = "TabDeskHelperLink"
    /** The helper listens on IPv4's 127.0.0.1 (Android's "loopback address" is IPv6's ::1). */
    private val LOCAL: InetAddress = InetAddress.getByName("127.0.0.1")

    class Task(val id: Int, val pkg: String, val running: Boolean, val visible: Boolean, val lastActive: Long)

    /** The tasks in Recents as the helper last reported them, newest first; empty when it isn't connected. */
    @Volatile var tasks: List<Task> = emptyList()
        private set
    @Volatile var connected = false
        private set
    /** Why the helper isn't running, for the Helper page. Null while connected or starting. */
    @Volatile var problem: String? = null
        private set
    @Volatile var starting = false
        private set
    /** Told on the main thread when [tasks], [connected] or [problem] change. */
    val listeners = ArrayList<() -> Unit>()

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var out: OutputStream? = null
    private var failures = 0
    private var waitingForWifi = false
    private lateinit var app: Context

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /** Connects to the helper, starting it if it isn't running. Safe to call often: does nothing if connected. */
    fun ensure(ctx: Context) {
        app = ctx.applicationContext
        exec.execute { if (!connected) ensureNow() }
    }

    /** Asks the helper to close a task, as swiping it away in Recents does. */
    fun close(taskId: Int) = exec.execute { send("close $taskId") }

    private fun send(line: String) = try { out?.run { write("$line\n".toByteArray()); flush() } } catch (_: Exception) { }

    private fun ensureNow() {
        if (!AdbKey.exists(app)) { problem = "TabDesk isn't paired yet"; changed(); return }
        val build = buildId()
        val saved = loadSaved()
        if (saved != null && connect(saved, build, attempts = 1)) return
        starting = true; problem = null; changed()
        try {
            val s = start(build)
            if (!connect(s, build, attempts = 12)) throw Adb.Problem("The helper didn't start")
            failures = 0
        } catch (e: Exception) {
            Log.w(TAG, "couldn't start the helper", e)
            problem = e.message ?: e.javaClass.simpleName
            failures++
            if (!wifi()) waitForWifi() else retryLater()
        } finally {
            starting = false; changed()
        }
    }

    private class Saved(val port: Int, val token: String)

    private fun file() = File(app.noBackupFilesDir, "adb/helper.properties")

    private fun loadSaved(): Saved? = try {
        val p = Properties().apply { file().inputStream().use { load(it) } }
        Saved(p.getProperty("port").toInt(), p.getProperty("token"))
    } catch (_: Exception) { null }

    /** Identifies this install of TabDesk, so a helper left from an older version is replaced. */
    private fun buildId(): String {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        return "${pi.longVersionCode}-${pi.lastUpdateTime}"
    }

    /** Starts a new helper (ending any old one) and returns where to reach it. */
    private fun start(build: String): Saved {
        val port = ServerSocket(0, 1, LOCAL).use { it.localPort }
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val s = Saved(port, token)
        file().parentFile?.mkdirs()
        file().outputStream().use { Properties().apply { setProperty("port", "$port"); setProperty("token", token) }.store(it, null) }
        // adb keeps running, and with it the helper, while USB debugging is on, even with wireless debugging off.
        val usbOn = try { Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (_: Exception) { false }
        if (!usbOn && Adb.canSwitch(app)) {
            try { Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1) } catch (_: Exception) {}
        }
        val t = SystemClock.elapsedRealtime()
        val pid = Adb.use(app) { link ->
            link.shell("killall tabdesk_helper 2>/dev/null; TABDESK_PORT=$port TABDESK_TOKEN=$token TABDESK_BUILD=$build " +
                "CLASSPATH=${app.applicationInfo.sourceDir} setsid app_process /system/bin --nice-name=tabdesk_helper " +
                "${Helper::class.java.name} serve </dev/null >/dev/null 2>&1 & sleep 1; pidof tabdesk_helper").trim()
        }
        Log.i(TAG, "helper started (pid $pid) in ${SystemClock.elapsedRealtime() - t} ms")
        return s
    }

    /** Connects to the helper at [s], trying for a few seconds while it starts. True once connected. */
    private fun connect(s: Saved, build: String, attempts: Int): Boolean {
        repeat(attempts) { i ->
            if (i > 0) Thread.sleep(500)
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(LOCAL, s.port), 1000)
                sock.soTimeout = 5000
                val o = sock.getOutputStream()
                o.write("${s.token}\n".toByteArray()); o.flush()
                val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
                val hello = inp.readLine() ?: throw Exception("closed")
                if (hello != "hello $build") {
                    // Left over from an older TabDesk: replace it.
                    Log.i(TAG, "helper is from another version ($hello), replacing it")
                    o.write("quit\n".toByteArray()); o.flush(); sock.close()
                    return false
                }
                sock.soTimeout = 0
                out = o; connected = true; problem = null; changed()
                Log.i(TAG, "connected")
                thread(name = "helper-link", isDaemon = true) { listen(sock, inp) }
                return true
            } catch (_: Exception) {
                try { sock.close() } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun listen(sock: Socket, inp: BufferedReader) {
        val since = SystemClock.elapsedRealtime()
        try {
            while (true) {
                val line = inp.readLine() ?: break
                if (line.startsWith("tasks")) {
                    tasks = line.split('\t').drop(1).mapNotNull { t ->
                        val w = t.split(' ')
                        if (w.size < 5) null
                        else Task(w[0].toInt(), w[1], w[2] == "1", w[3] == "1", w[4].toLongOrNull() ?: 0L)
                    }
                    changed()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
            out = null; connected = false; tasks = emptyList()
            Log.i(TAG, "helper connection ended")
            problem = "The helper stopped"
            changed()
            if (SystemClock.elapsedRealtime() - since > 60_000) failures = 0
            failures++
            retryLater()
        }
    }

    /** Tries again later, waiting longer after each failure so a lasting problem doesn't keep switching things. */
    private fun retryLater() {
        val delay = when (failures) { 0, 1 -> 5_000L; 2 -> 30_000L; 3 -> 120_000L; else -> 600_000L }
        main.postDelayed({ ensure(app) }, delay)
    }

    private fun wifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        catch (_: Exception) { false }
    }

    /** Wireless debugging needs Wi-Fi: after a restart it may not be connected yet, so try again once it is. */
    private fun waitForWifi() {
        if (waitingForWifi) return
        waitingForWifi = true
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
                waitingForWifi = false
                main.postDelayed({ ensure(app) }, 3000)   // give Wi-Fi a moment to settle
            }
        })
    }
}
