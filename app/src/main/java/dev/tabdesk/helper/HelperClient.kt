package dev.tabdesk.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import dev.tabdesk.AppInfo
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * TabDesk's side of the helper ([Helper]): starts it through wireless debugging when it isn't running (switching
 * wireless debugging on for the moment if it's off), stays connected, and passes on the running tasks it reports.
 */
internal object HelperClient {
    private const val TAG = "TabDeskHelperLink"
    /** The helper listens on IPv4's 127.0.0.1 (Android's "loopback address" is IPv6's ::1). */
    private val LOCAL: InetAddress = InetAddress.getByName("127.0.0.1")

    /**
     * A task as the helper reports it. [visible]: on screen now (a minimised window is running but not visible).
     * [floating]: in a floating window, which [bounds] gives in screen pixels. [focused]: the window in use.
     * [cls]: the activity it was started with. [top]: the package of the screen on top of it, which may be another
     * app's (a sign-in, a share sheet); the package that counts for agent control.
     */
    class Task(val id: Int, val pkg: String, val running: Boolean, val visible: Boolean, val lastActive: Long,
               val floating: Boolean = false, val focused: Boolean = false, val bounds: Rect? = null, val cls: String? = null,
               val top: String = pkg) {
        /**
         * Whether this is a window of [app]. Another app's task counts by its package, whatever screen of the app it
         * was opened at; TabDesk's own windows (Files, Tasks) share one package, so for those the activity decides.
         */
        fun isOf(app: AppInfo?) = app != null && pkg == app.pkg &&
            (cls == null || pkg != ownPackage || cls == app.component.className)
    }

    /** The tasks in Recents as the helper last reported them, newest first; empty when it isn't connected. */
    @Volatile var tasks: List<Task> = emptyList()
        private set
    @Volatile var connected = false
        private set
    /** A window on screen hides other apps' overlays just now (only known while a floating window shows). */
    @Volatile var overlaysHidden = false
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
    private val ownPackage get() = if (::app.isInitialized) app.packageName else null

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /**
     * Connects to the helper, starting it if it isn't running. Safe to call often: does nothing if connected, and
     * while one try is waiting its turn, more calls add nothing.
     */
    fun ensure(ctx: Context) {
        app = ctx.applicationContext
        queue(start = true)
    }

    /** Connects to the helper only if one is running already: never starts one. For agent calls (AgentService). */
    fun reconnect(ctx: Context) {
        app = ctx.applicationContext
        queue(start = false)
    }

    private val queued = AtomicBoolean(false)
    @Volatile private var queuedStart = false

    /** At most one try waits on [exec] at a time; it starts the helper if any of the calls it stands for asked to. */
    private fun queue(start: Boolean) {
        if (start) queuedStart = true
        if (!queued.compareAndSet(false, true)) return
        exec.execute {
            queued.set(false)
            val s = queuedStart
            queuedStart = false
            if (!connected) ensureNow(s)
        }
    }

    /** Asks the helper to close a task, as swiping it away in Recents does. */
    fun close(taskId: Int) = exec.execute { send("close $taskId") }

    /** Minimises a task's window: it goes behind the desktop and keeps running. */
    fun minimise(taskId: Int) {
        expect(taskId, visible = false)
        exec.execute { send("min $taskId") }
    }

    /** Brings a task's window to the front as it was, e.g. after [minimise]. */
    fun front(taskId: Int) {
        expect(taskId, visible = true)
        exec.execute { send("front $taskId") }
    }

    /**
     * Shows a change at once, before the helper's next list confirms it (it can take a moment), so a second tap
     * straight after the first sees it: a window brought back is also first in the list again.
     */
    private fun expect(taskId: Int, visible: Boolean) {
        val t = tasks.firstOrNull { it.id == taskId } ?: return
        val now = Task(t.id, t.pkg, t.running, visible, t.lastActive, t.floating, visible && t.focused, t.bounds, t.cls, t.top)
        val rest = tasks.filter { it.id != taskId }
        tasks = if (visible) listOf(now) + rest else tasks.map { if (it.id == taskId) now else it }
        changed()
    }

    /** Moves and resizes a floating window to [r], in screen pixels. */
    fun resize(taskId: Int, r: Rect) = exec.execute { send("resize $taskId ${r.left} ${r.top} ${r.right} ${r.bottom}") }

    private val previewsWaiting = HashMap<Int, ArrayList<(Bitmap?) -> Unit>>()

    /**
     * A picture of a task's window, at most [maxPx] on its longer side, given to [done] on the main thread: null if
     * there's none, or the helper doesn't answer within a few seconds.
     */
    fun preview(taskId: Int, maxPx: Int, done: (Bitmap?) -> Unit) {
        if (!connected) { done(null); return }
        val first = synchronized(previewsWaiting) {
            val list = previewsWaiting.getOrPut(taskId) { ArrayList() }
            list += done
            list.size == 1
        }
        if (!first) return   // already asked; this one gets the same answer
        exec.execute { send("shot $taskId $maxPx") }
        main.postDelayed({ answerPreview(taskId, null) }, 4000)
    }

    /**
     * One app's share of the tablet over a moment (see Helper.usage). [owner]: "u<uid>" for an app, "p<package>" for
     * processes an app started under a throwaway uid (and the helper, which is TabDesk's), "android" for the system.
     * [cpu]: per mille of the whole device. [cpuSeconds]: CPU time in all since its processes started. [memKb]: RAM.
     */
    class AppUsage(val owner: String, val cpu: Int, val cpuSeconds: Long, val memKb: Long, val names: List<String>) {
        /** The app these processes belong to, by their uid or the package their name starts with; null for Android's own. */
        fun packageIn(pm: android.content.pm.PackageManager): String? = when {
            owner == "android" -> null
            owner.startsWith("p") -> owner.drop(1)
            else -> owner.drop(1).toIntOrNull()?.let { uid ->
                val pkgs = try { pm.getPackagesForUid(uid).orEmpty() } catch (_: Exception) { emptyArray() }
                // Several packages can share a uid; the one its processes are named after is the one to show.
                pkgs.firstOrNull { p -> names.any { it == p || it.startsWith("$p:") } } ?: pkgs.firstOrNull()
            }
        }
    }

    /** [busy]: the whole device, per mille. [idleMs]: since the user last touched it (-1: unknown). */
    class Usage(val sampleMs: Long, val cpus: Int, val busy: Int, val idleMs: Long, val apps: List<AppUsage>)

    private val usageWaiting = ArrayList<(Usage?) -> Unit>()
    private var usageAsk = 0

    /**
     * What's using the tablet, looked at over about [sampleMs], given to [done] on the main thread; null without the
     * helper, or if it doesn't answer. Asks made while one is under way share its answer.
     */
    fun usage(sampleMs: Long, done: (Usage?) -> Unit) {
        if (!connected) { done(null); return }
        val ask = synchronized(usageWaiting) {
            usageWaiting += done
            if (usageWaiting.size > 1) return
            usageAsk
        }
        exec.execute { send("usage $sampleMs") }
        main.postDelayed({ answerUsage(null, ask) }, sampleMs + 5000)
    }

    private fun answerUsage(u: Usage?, ask: Int? = null) {
        val list = synchronized(usageWaiting) {
            if (ask != null && ask != usageAsk) return   // that ask was answered already
            usageAsk++
            usageWaiting.toList().also { usageWaiting.clear() }
        }
        main.post { list.forEach { it(u) } }
    }

    private val readsWaiting = java.util.concurrent.ConcurrentHashMap<Int, (org.json.JSONObject?) -> Unit>()
    private val readNext = java.util.concurrent.atomic.AtomicInteger()

    /**
     * For agent control: reads [pkg]'s windows if it's on top of task [taskId] in front, from item [root] (null: all)
     * down [depth] levels (-1: all), with a picture at most [picture] px (0: none); see Helper's Reader for the
     * answer. Waits on the calling thread until [deadline] (wall time); null if the helper isn't connected or
     * doesn't answer by then.
     */
    fun read(pkg: String, taskId: Int, root: String?, depth: Int, picture: Int, deadline: Long): org.json.JSONObject? {
        if (!connected) return null
        require(pkg.none { it.isWhitespace() } && root?.matches(Regex("""\d+(\.\d+)*""")) != false)
        val req = readNext.incrementAndGet()
        val latch = java.util.concurrent.CountDownLatch(1)
        var got: org.json.JSONObject? = null
        readsWaiting[req] = { got = it; latch.countDown() }
        exec.execute { send("read $req $pkg $taskId ${root ?: "-"} $depth $picture") }
        try {
            latch.await((deadline - System.currentTimeMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally { readsWaiting.remove(req) }
        return got
    }

    /** Control went idle or stopped: the helper stops reading the screen. */
    fun stopReading() = exec.execute { send("unread") }

    /** Wakes the screen, as the power button does ("Let the agent wake the screen"). */
    fun wake() = exec.execute { send("wake") }

    private fun parseUsage(line: String): Usage? = try {
        val parts = line.split('\t')
        val h = parts[0].split(' ')
        Usage(h[1].toLong(), h[2].toInt(), h[3].toInt(), h[4].toLong(), parts.drop(1).mapNotNull { a ->
            val w = a.split(' ', limit = 6)
            if (w.size < 6) null else AppUsage(w[0], w[1].toInt(), w[2].toLong(), w[3].toLong(), w[5].split(','))
        })
    } catch (_: Exception) { null }

    private fun answerPreview(taskId: Int, bmp: Bitmap?) {
        val list = synchronized(previewsWaiting) { previewsWaiting.remove(taskId) } ?: return
        main.post { list.forEach { it(bmp) } }
    }

    private fun send(line: String) = try { out?.run { write("$line\n".toByteArray()); flush() } } catch (_: Exception) { }

    private fun ensureNow(start: Boolean) {
        if (!AdbKey.exists(app)) { problem = "TabDesk isn't paired yet"; changed(); return }
        val build = buildId()
        val saved = loadSaved()
        if (saved != null && connect(saved, build, attempts = 1)) return
        if (!start) return
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
                    overlaysHidden = line.substringBefore('\t') == "tasks hides"
                    tasks = line.split('\t').drop(1).mapNotNull { t ->
                        val w = t.split(' ')
                        if (w.size < 5) null
                        else Task(w[0].toInt(), w[1], w[2] == "1", w[3] == "1", w[4].toLongOrNull() ?: 0L,
                            floating = w.getOrNull(5) == "5", focused = w.getOrNull(6) == "1", bounds = rect(w.getOrNull(7)),
                            cls = w.getOrNull(8), top = w.getOrNull(9) ?: w[1])
                    }
                    changed()
                } else if (line.startsWith("shot ")) {
                    val w = line.split(' ', limit = 3)
                    val id = w.getOrNull(1)?.toIntOrNull() ?: continue
                    val bmp = w.getOrNull(2)?.takeIf { it != "-" }?.let {
                        try { Base64.decode(it, Base64.NO_WRAP).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }
                        catch (_: Exception) { null }
                    }
                    answerPreview(id, bmp)
                } else if (line.startsWith("usage")) {
                    answerUsage(parseUsage(line))
                } else if (line.startsWith("read ")) {
                    val rest = line.substring(5)
                    val req = rest.substringBefore(' ').toIntOrNull() ?: continue
                    val r = try { org.json.JSONObject(rest.substringAfter(' ')) } catch (_: Exception) { null }
                    readsWaiting.remove(req)?.invoke(r)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
            out = null; connected = false; tasks = emptyList(); overlaysHidden = false
            readsWaiting.keys.toList().forEach { readsWaiting.remove(it)?.invoke(null) }
            Log.i(TAG, "helper connection ended")
            problem = "The helper stopped"
            changed()
            if (SystemClock.elapsedRealtime() - since > 60_000) failures = 0
            failures++
            retryLater()
        }
    }

    /** "left,top,right,bottom", as the helper writes a window's bounds; null for "-" or an empty one. */
    private fun rect(s: String?): Rect? {
        val n = s?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 } ?: return null
        return Rect(n[0], n[1], n[2], n[3]).takeIf { !it.isEmpty }
    }

    private val retry = Runnable { ensure(app) }

    /**
     * Tries again later, waiting longer after each failure so a lasting problem doesn't keep switching things. Only
     * one retry is ever waiting: a new one replaces it.
     */
    private fun retryLater() {
        val delay = when (failures) { 0, 1 -> 5_000L; 2 -> 30_000L; 3 -> 120_000L; else -> 600_000L }
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
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
