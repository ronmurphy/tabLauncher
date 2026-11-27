package dev.tabdesk.helper

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * TabDesk's helper. It runs as the shell user (the account adb uses), started by TabDesk through wireless debugging:
 *     TABDESK_PORT=… TABDESK_TOKEN=… CLASSPATH=<TabDesk's apk> app_process /system/bin dev.tabdesk.helper.Helper serve
 * so it can see what ordinary apps can't: every app's tasks. It's a separate process from the app, and keeps running
 * while adb does (USB debugging on keeps adb running).
 *
 * TabDesk talks to it over a socket on 127.0.0.1 ([HelperClient]): it must first send the token it started the
 * helper with, so no other app can use it. The helper then sends the task list whenever it changes, and takes a few
 * fixed commands (close, minimise, bring to front, move or resize a floating window, a preview picture, and for
 * agent control reading and acting in the app in front, see [Reader] and [Actor], and watching for a real touch,
 * [Touches]); it never runs anything it's sent.
 */
object Helper {
    private const val TAG = "TabDeskHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        // Besides "serve", these run once from an adb shell, for trying things out on a new Android version.
        val id = args.getOrNull(1)?.toIntOrNull() ?: -1
        when (args.firstOrNull()) {
            "serve" -> serve()
            "tasks" -> tasks().map { it.line() }.ifEmpty { listOf("(no tasks)") }.forEach { println(it) }
            "usage" -> println(usage(args.getOrNull(1)?.toLongOrNull() ?: 1500).replace('\t', '\n'))
            // What an agent would read of the app in front (or of <package> if it's in front): read [package|-]
            // [item id|-] [depth, -1 all] [picture size, 0 none] [picture file].
            "read" -> {
                val front = Reader.front()
                val pkg = args.getOrNull(1)?.takeIf { it != "-" } ?: front?.top
                val r = Reader.read(pkg, front?.id ?: -1, args.getOrNull(2)?.takeIf { it != "-" },
                    args.getOrNull(3)?.toIntOrNull() ?: -1, args.getOrNull(4)?.toIntOrNull() ?: 0)
                (r.remove("picture") as? org.json.JSONObject)?.let { p ->
                    val file = args.getOrElse(5) { "/data/local/tmp/read.jpg" }
                    java.io.File(file).writeBytes(android.util.Base64.decode(p.remove("jpeg") as String, android.util.Base64.NO_WRAP))
                    r.put("picture", p.put("file", file))
                }
                println(r.toString(1))
                Reader.disconnect()
                exitProcess(0)
            }
            // One action, as TabDesk would ask it (see [Actor.act]): act '<JSON>'; "pkg" and "task" default to the app
            // in front.
            "act" -> {
                val r = org.json.JSONObject(args.getOrElse(1) { "{}" })
                Reader.front()?.let { f -> if (!r.has("pkg")) r.put("pkg", f.top); if (!r.has("task")) r.put("task", f.id) }
                println(Actor.act(r).toString(1))
                Reader.disconnect(); exitProcess(0)
            }
            // Real touches on the screen, as the agent's pause sees them, for [seconds].
            "touches" -> {
                Touches.start { println("touched ${System.currentTimeMillis()}") }
                Thread.sleep((args.getOrNull(1)?.toLongOrNull() ?: 10) * 1000)
                Touches.stop(); exitProcess(0)
            }
            "close" -> println("close $id: ${close(id)}")
            "min" -> println("min $id: ${minimise(id)}")
            "front" -> println("front $id: ${front(id)}")
            "resize" -> println("resize $id: ${resize(id, args.drop(2).map { it.toInt() })}")
            "shot" -> {
                val jpeg = snapshot(id, args.getOrNull(2)?.toIntOrNull() ?: 480)
                if (jpeg == null) println("shot $id: none")
                else { java.io.File(args.getOrElse(3) { "/data/local/tmp/shot.jpg" }).writeBytes(jpeg); println("shot $id: ${jpeg.size} bytes") }
            }
            // The windows on screen as the reader sees them: type, layer, task, bounds, package and title.
            "windows" -> {
                repeat(args.getOrNull(1)?.toIntOrNull() ?: 1) { i ->
                    if (i > 0) { Thread.sleep(300); println("--") }
                    Reader.windows().forEach { println(it) }
                }
                Reader.disconnect(); exitProcess(0)
            }
            // The system calls with this word in their name, as this Android version has them.
            "methods" -> atm.javaClass.methods.filter { it.name.contains(args.getOrElse(1) { "" }, true) }
                .map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }.sorted().forEach { println(it) }
            // Another interface's calls, e.g. a maker's own window service: iface <class name> [word].
            "iface" -> Class.forName(args[1]).methods.filter { it.name.contains(args.getOrElse(2) { "" }, true) }
                .map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}" }
                .sorted().forEach { println(it) }
            // Tries one of the window calls by name, with whole-number arguments ("ints" passes them as one int[]).
            "call" -> println("${args[1]}: ${call(atm, args[1], *args.drop(2).map { it.toInt() }.toTypedArray())}")
            "ints" -> println("${args[1]}: ${call(atm, args[1], args.drop(2).map { it.toInt() }.toIntArray())}")
            else -> println("usage: serve | tasks | usage [ms] | close|min|front <task id> | resize <task id> <l> <t> <r> <b> | " +
                "shot <task id> [size] [file] | read [package|-] [id|-] [depth] [size] [file] | act <JSON> | touches [s] | methods <word> | " +
                "iface <class> [word] | call|ints <method> <numbers…>")
        }
    }

    // ---------------------------------------------------------------- tasks

    internal val atm: Any by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }
    private val int = Int::class.javaPrimitiveType

    /**
     * One task, as the taskbar needs it. [mode] is how its window is shown (1 full screen, 5 floating, 6 split
     * screen, 0 unknown); [bounds] is where, as "left,top,right,bottom". [focused]: the window the user is using.
     * [cls]: the activity the task was started with, which tells TabDesk's own windows (Files, Tasks) apart.
     * [top]: the package of the screen on top of the task, which may be another app's (a sign-in, a share sheet).
     */
    class Task(val id: Int, val pkg: String, val running: Boolean, val visible: Boolean, val lastActive: Long,
               val mode: Int, val focused: Boolean, val bounds: String, val cls: String, val top: String) {
        fun line() = "$id $pkg ${if (running) 1 else 0} ${if (visible) 1 else 0} $lastActive $mode ${if (focused) 1 else 0} $bounds $cls $top"

        /** [bounds] as a Rect; null if unknown. */
        fun rect(): Rect? = bounds.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
            ?.let { Rect(it[0], it[1], it[2], it[3]) }?.takeIf { !it.isEmpty }
    }

    /** Closes a task, as swiping it away in Recents does. */
    private fun close(taskId: Int) = atm.javaClass.getMethod("removeTask", int).invoke(atm, taskId) as Boolean

    /** The tasks in Recents, newest first. */
    internal fun tasks(): List<Task> = recentTasks().mapNotNull { t ->
        val component = (field(t, "baseIntent") as? Intent)?.component ?: return@mapNotNull null
        val wc = windowConfig(t)
        val mode = try { wc?.javaClass?.getMethod("getWindowingMode")?.invoke(wc) as? Int } catch (_: Throwable) { null } ?: 0
        val b = try { wc?.javaClass?.getMethod("getBounds")?.invoke(wc) as? Rect } catch (_: Throwable) { null }
        Task(field(t, "taskId") as Int, component.packageName, field(t, "isRunning") == true, field(t, "isVisible") == true,
            (field(t, "lastActiveTime") as? Long) ?: 0L, mode, field(t, "isFocused") == true,
            b?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "-", component.className,
            (field(t, "topActivity") as? ComponentName)?.packageName ?: component.packageName)
    }

    private fun recentTasks(): List<Any> {
        val slice = atm.javaClass.getMethod("getRecentTasks", int, int, int)
            .invoke(atm, 50, 2 /* RECENT_IGNORE_UNAVAILABLE */, 0 /* user */)!!
        return (slice.javaClass.getMethod("getList").invoke(slice) as List<*>).filterNotNull()
    }

    private fun windowConfig(t: Any): Any? = field(t, "configuration")?.let { field(it, "windowConfiguration") }

    // ---------------------------------------------------------------- windows

    /**
     * Minimises a task: its window goes behind everything, TabDesk's desktop included, the way Android's own
     * desktop windowing minimises one (a reorder to the bottom). It keeps running; [front] brings it back.
     */
    private fun minimise(taskId: Int): Boolean {
        val task = recentTasks().firstOrNull { field(it, "taskId") == taskId } ?: return false
        val token = field(task, "token") ?: return false
        // A picture of it first, for the taskbar's previews: Android keeps one only when a window leaves the screen
        // its own ways. (Android 12L and later; the previews just stay blank before that.)
        try { call(atm, "takeTaskSnapshot", taskId, true) } catch (_: Throwable) {}
        val wctClass = Class.forName("android.window.WindowContainerTransaction")
        val tokenClass = Class.forName("android.window.WindowContainerToken")
        val wct = wctClass.getConstructor().newInstance()
        val reorder = wctClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType)
        reorder.invoke(wct, token, false)
        // Lenovo's ZUI keeps floating windows "always on top", which no reorder gets below the home screen. Its own
        // minimise button switches that off first; bringing the window back switches it on again.
        if (alwaysOnTop(task)) {
            wctClass.getMethod("setAlwaysOnTop", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, false)
            reorder.invoke(wct, token, false)
            unTopped += taskId
        }
        val organizer = atm.javaClass.getMethod("getWindowOrganizerController").invoke(atm)!!
        // As a "to back" transition (Android 14 and later), the way the system's own minimise button does it. Applied
        // outside a transition, some makers' Android (Lenovo's ZUI) puts the window straight back on top.
        try {
            organizer.javaClass.getMethod("startNewTransition", Int::class.javaPrimitiveType, wctClass)
                .invoke(organizer, 4 /* TRANSIT_TO_BACK */, wct)
            return true
        } catch (e: Throwable) {
            if (e !is NoSuchMethodException) Log.w(TAG, "startNewTransition $taskId: ${e.cause ?: e}")
        }
        organizer.javaClass.getMethod("applyTransaction", wctClass).invoke(organizer, wct)
        return true
    }

    /** Whether a task is "always on top", as Lenovo's ZUI makes its floating windows. */
    private fun alwaysOnTop(task: Any): Boolean = try {
        val config = field(task, "configuration")
        val wc = config?.let { field(it, "windowConfiguration") }
        wc?.javaClass?.getMethod("isAlwaysOnTop")?.invoke(wc) == true
    } catch (_: Throwable) { false }

    /** Brings a task to the front as it was (floating stays floating), the way Recents does. */
    private fun front(taskId: Int): Boolean {
        var done = false
        try {
            val r = call(atm, "startActivityFromRecents", taskId, null) as? Int ?: -1
            if (r >= 0) done = true else Log.w(TAG, "startActivityFromRecents $taskId: $r")
        } catch (e: Throwable) { Log.w(TAG, "startActivityFromRecents $taskId: ${e.cause ?: e}") }
        // The other way in: needs only REORDER_TASKS, which the shell has.
        if (!done) call(atm, "moveTaskToFront", null, "com.android.shell", taskId, 0, null)
        // A floating window back from minimise is "always on top" again on Lenovo's ZUI, as its own taskbar does it.
        val task = recentTasks().firstOrNull { field(it, "taskId") == taskId }
        if (task != null && (unTopped.remove(taskId) || (zuiFreeform && windowingMode(task) == 5)) && !alwaysOnTop(task)) {
            try {
                val token = field(task, "token")
                val wctClass = Class.forName("android.window.WindowContainerTransaction")
                val tokenClass = Class.forName("android.window.WindowContainerToken")
                val wct = wctClass.getConstructor().newInstance()
                wctClass.getMethod("setAlwaysOnTop", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, true)
                wctClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, true)
                val organizer = atm.javaClass.getMethod("getWindowOrganizerController").invoke(atm)!!
                organizer.javaClass.getMethod("applyTransaction", wctClass).invoke(organizer, wct)
            } catch (e: Throwable) { Log.w(TAG, "always on top $taskId: ${e.cause ?: e}") }
        }
        return true
    }

    /** Floating windows [minimise] took off "always on top", to put back on when they return. */
    private val unTopped = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Lenovo's ZUI, whose floating windows are always on top while they're showing. */
    private val zuiFreeform by lazy {
        try { Class.forName("com.android.internal.ov.IOvFreeformService"); true } catch (_: Throwable) { false }
    }

    private fun windowingMode(task: Any): Int = try {
        val wc = field(task, "configuration")?.let { field(it, "windowConfiguration") }
        wc?.javaClass?.getMethod("getWindowingMode")?.invoke(wc) as? Int ?: 0
    } catch (_: Throwable) { 0 }

    /** Moves and resizes a floating window: [b] is left, top, right, bottom in screen pixels. */
    private fun resize(taskId: Int, b: List<Int>): Boolean {
        if (b.size != 4) return false
        val r = call(atm, "resizeTask", taskId, Rect(b[0], b[1], b[2], b[3]), 1 /* RESIZE_MODE_PRESERVE_WINDOW */)
        return r as? Boolean ?: true
    }

    /**
     * A picture of a task's window, at most [maxPx] on its longer side, as JPEG: Android keeps one of each window
     * for Recents, also while it's minimised. Null if there's none yet (e.g. a window that was never drawn).
     */
    private fun snapshot(taskId: Int, maxPx: Int): ByteArray? {
        val full = taskPicture(taskId, fresh = false) ?: return null
        val scale = minOf(1f, maxPx.toFloat() / maxOf(full.width, full.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1),
            (full.height * scale).toInt().coerceAtLeast(1), true) else full
        val out = java.io.ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (small !== full) small.recycle()
        full.recycle()
        return out.toByteArray()
    }

    /**
     * A task's window alone, as Recents keeps it: the last picture Android kept, or with [fresh] (or none kept yet)
     * one taken now (a window on screen; Android 12L and later). Null if there's none.
     */
    internal fun taskPicture(taskId: Int, fresh: Boolean): Bitmap? {
        var snap: Any? = null
        if (!fresh) {
            // The last one Android kept: (taskId, lowResolution), on some versions also (…, takeSnapshotIfNeeded).
            val m = atm.javaClass.methods.firstOrNull { it.name == "getTaskSnapshot" }
            snap = m?.let { if (it.parameterTypes.size >= 3) it.invoke(atm, taskId, false, true) else it.invoke(atm, taskId, false) }
        }
        // updateCache, so the taskbar's next preview is quick too.
        if (snap == null) snap = try { call(atm, "takeTaskSnapshot", taskId, true) } catch (e: Throwable) { log("takeTaskSnapshot", e); null }
        if (snap == null) { Log.i(TAG, "no snapshot of $taskId"); return null }
        val buffer = snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer
            ?: run { Log.i(TAG, "snapshot of $taskId has no buffer"); return null }
        val space = try { snap.javaClass.getMethod("getColorSpace").invoke(snap) as? ColorSpace } catch (_: Throwable) { null }
        val hw = Bitmap.wrapHardwareBuffer(buffer, space ?: ColorSpace.get(ColorSpace.Named.SRGB))
            ?: run { Log.i(TAG, "can't wrap the snapshot of $taskId"); return null }
        val full = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle(); buffer.close()
        return full ?: run { Log.i(TAG, "can't copy the snapshot of $taskId"); null }
    }

    internal fun log(what: String, e: Throwable) = Log.w(TAG, "$what: ${(e as? java.lang.reflect.InvocationTargetException)?.cause ?: e}")

    /** Calls the system method [name] that takes as many arguments as given (the types differ between versions). */
    internal fun call(o: Any, name: String, vararg args: Any?): Any? {
        val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodException("$name/${args.size}")
        return m.invoke(o, *args)
    }

    internal fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }

    // ---------------------------------------------------------------- serving TabDesk

    // ---------------------------------------------------------------- usage

    /** One process at a moment: whose it is, and the CPU time it has used since it started, in clock ticks. */
    private class Proc(val owner: String, val name: String, val ticks: Long)

    /** Process names by pid, read once per process (they don't change, and reading them is the slow part). */
    private val procNames = HashMap<Int, String>()
    private val clockTicks: Long by lazy { try { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) } catch (_: Throwable) { 100L } }
    private val pageKb: Long by lazy { try { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024 } catch (_: Throwable) { 4L } }

    /**
     * Whose a process is, for grouping: "u<uid>" for an app, "p<package>" for a process an app started under a
     * throwaway uid (web pages, sandboxes: the package is the first part of its name), "android" for the rest.
     * The helper itself runs as the shell user but is part of TabDesk.
     */
    private fun owner(uid: Int, name: String): String {
        val appId = uid % 100_000
        return when {
            name == "tabdesk_helper" -> "pdev.tabdesk"
            appId in 10_000..19_999 -> "u$uid"
            appId >= 90_000 -> name.substringBefore(':').takeIf { '.' in it }?.let { "p$it" } ?: "android"
            else -> "android"
        }
    }

    private fun readProcs(): HashMap<Int, Proc> {
        val out = HashMap<Int, Proc>()
        val seen = HashSet<Int>()
        for (d in java.io.File("/proc").listFiles() ?: emptyArray()) {
            val pid = d.name.toIntOrNull() ?: continue
            try {
                val stat = java.io.File(d, "stat").readText()
                // The name in brackets may hold spaces, so count fields from after its closing bracket.
                val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                val ticks = f[11].toLong() + f[12].toLong()   // utime + stime
                val name = procNames.getOrPut(pid) {
                    java.io.File(d, "cmdline").readText().substringBefore('\u0000').trim()
                        .ifEmpty { stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')')) }
                        .replace(' ', '_').replace('\t', '_')
                }
                val uid = android.system.Os.stat(d.path).st_uid
                out[pid] = Proc(owner(uid, name), name, ticks)
                seen += pid
            } catch (_: Exception) { }   // it ended while being read
        }
        procNames.keys.retainAll(seen)
        return out
    }

    /** All CPUs' busy and total time from /proc/stat, in clock ticks. */
    private fun cpuTimes(): LongArray = try {
        val f = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val idle = f[3] + f.getOrElse(4) { 0L }   // idle + iowait
        longArrayOf(f.sum() - idle, f.sum())
    } catch (_: Exception) { longArrayOf(0, 0) }

    /** How long since the user last touched the screen (or a key), in ms; -1 if Android doesn't say. */
    private fun idleMs(): Long = try {
        val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "power"))
        val re = Regex("lastUserActivityTime=\\d+ \\((\\d+) ms ago\\)")
        val ms = p.inputStream.bufferedReader().useLines { lines -> lines.firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1) } }
        p.destroy()
        ms?.toLongOrNull() ?: -1L
    } catch (_: Exception) { -1L }

    /**
     * What's using the tablet: the CPU each app used over [ms] (looking twice, that far apart), its memory, and its
     * CPU time in all, as one line:
     *     usage <ms> <cpus> <device busy ‰> <ms since the user touched it>\t<owner> <cpu ‰> <cpu seconds> <memory kB> <processes> <names,…>\t…
     * CPU ‰ is of the whole device (all its CPUs), so one busy thread on an 8-CPU tablet is 125 ‰. Memory is what's
     * in RAM (RSS), which counts shared libraries in every process, so the numbers add up to more than the tablet has.
     */
    private fun usage(ms: Long): String {
        val cpus = Runtime.getRuntime().availableProcessors()
        val t0 = cpuTimes(); val a = readProcs(); val start = System.nanoTime()
        Thread.sleep(ms)
        val t1 = cpuTimes(); val b = readProcs(); val took = (System.nanoTime() - start) / 1_000_000.0
        val busy = if (t1[1] > t0[1]) ((t1[0] - t0[0]) * 1000 / (t1[1] - t0[1])).toInt() else 0

        class Sum { var delta = 0L; var ticks = 0L; var kb = 0L; val names = LinkedHashSet<String>() }
        val sums = LinkedHashMap<String, Sum>()
        for ((pid, p) in b) {
            val s = sums.getOrPut(p.owner) { Sum() }
            a[pid]?.takeIf { it.name == p.name }?.let { s.delta += (p.ticks - it.ticks).coerceAtLeast(0) }
            s.ticks += p.ticks
            s.kb += try { java.io.File("/proc/$pid/statm").readText().split(' ')[1].toLong() * pageKb } catch (_: Exception) { 0L }
            s.names += p.name
        }
        val perMille = { delta: Long -> (delta * 1000.0 * 1000 / (clockTicks * took * cpus)).toInt().coerceIn(0, 1000) }
        val sb = StringBuilder("usage ${took.toLong()} $cpus $busy ${idleMs()}")
        for ((owner, s) in sums) {
            if (s.kb == 0L) continue   // kernel threads: no memory of their own, and counted in "android"'s CPU anyway
            sb.append('\t').append("$owner ${perMille(s.delta)} ${s.ticks / clockTicks} ${s.kb} ${s.names.size} ${s.names.take(8).joinToString(",")}")
        }
        return sb.toString()
    }

    private val lock = Object()
    private var changed = true
    private var client: OutputStream? = null
    /** The list as last sent, to send only changes (cleared for a new connection, which needs it all). */
    private var lastSent = ""

    private fun serve() {
        val port = System.getenv("TABDESK_PORT")?.toIntOrNull() ?: exitProcess(2)
        val token = System.getenv("TABDESK_TOKEN") ?: exitProcess(2)
        val build = System.getenv("TABDESK_BUILD") ?: ""
        val server = try { ServerSocket(port, 2, InetAddress.getByName("127.0.0.1")) }
            catch (e: Exception) { Log.w(TAG, "can't listen on $port: $e"); exitProcess(3) }
        Log.i(TAG, "serving on $port, pid ${android.os.Process.myPid()}")
        watchTasks()
        thread(name = "sender", isDaemon = true) { sendLoop() }
        while (true) {
            val s = try { server.accept() } catch (e: Exception) { Log.w(TAG, "accept: $e"); continue }
            thread(name = "client", isDaemon = true) { talk(s, token, build) }
        }
    }

    /**
     * Sends the task list again once a change has had time to happen. Some changes (Lenovo's minimise) don't call
     * the task listener, and the 3-second check is too slow for a second tap on the taskbar.
     */
    private fun recheck() = thread(isDaemon = true) {
        for (wait in longArrayOf(350, 900)) {
            Thread.sleep(wait)
            synchronized(lock) { changed = true; lock.notifyAll() }
        }
    }

    /** One connection from TabDesk: check the token, then send tasks and take commands until it closes. */
    private fun talk(s: Socket, token: String, build: String) {
        s.use {
            s.soTimeout = 5000
            val inp = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = s.getOutputStream()
            if (inp.readLine() != token) { Log.w(TAG, "wrong token"); return }
            s.soTimeout = 0
            synchronized(lock) {
                client?.let { old -> try { old.close() } catch (_: Exception) {} }   // a newer TabDesk replaces the old one
                client = out
                lastSent = ""
                write(out, "hello $build")
                changed = true; lock.notifyAll()
            }
            while (true) {
                val line = try { inp.readLine() } catch (_: Exception) { null } ?: break
                val w = line.split(' ')
                val id = w.getOrNull(1)?.toIntOrNull()
                when (w[0]) {
                    "close" -> if (id != null) {
                        val ok = try { close(id) } catch (e: Exception) { Log.w(TAG, "close $id: $e"); false }
                        synchronized(lock) { write(out, "closed $id ${if (ok) 1 else 0}") }
                    }
                    "min" -> if (id != null) { try { minimise(id) } catch (e: Throwable) { log("min $id", e) }; recheck() }
                    "front" -> if (id != null) { try { front(id) } catch (e: Throwable) { log("front $id", e) }; recheck() }
                    "resize" -> if (id != null) {
                        try { resize(id, w.drop(2).mapNotNull { it.toIntOrNull() }) } catch (e: Throwable) { log("resize $id", e) }
                        recheck()
                    }
                    // A preview: "shot <id> <base64 JPEG>", or "shot <id> -" if there's none. Off this thread, as it
                    // takes a moment and the taskbar may ask for several at once.
                    "shot" -> if (id != null) {
                        val size = w.getOrNull(2)?.toIntOrNull()?.coerceIn(64, 1024) ?: 320
                        thread(name = "shot", isDaemon = true) {
                            val jpeg = try { snapshot(id, size) } catch (e: Throwable) { log("shot $id", e); null }
                            val data = jpeg?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: "-"
                            synchronized(lock) { write(out, "shot $id $data") }
                        }
                    }
                    // What's using the tablet, looked at over a moment (see [usage]). Off this thread too.
                    "usage" -> {
                        val ms = w.getOrNull(1)?.toLongOrNull()?.coerceIn(250, 5000) ?: 1500
                        thread(name = "usage", isDaemon = true) {
                            val line = try { synchronized(procNames) { usage(ms) } } catch (e: Throwable) { log("usage", e); "usage -" }
                            synchronized(lock) { write(out, line) }
                        }
                    }
                    // Reading for agent control (see [Reader]): "read <request> <package> <task id> <item id|-> <depth>
                    // <picture size>", answered "read <request> <JSON>". Off this thread: it takes a moment.
                    "read" -> if (w.size >= 7) {
                        val req = w[1]
                        thread(name = "read", isDaemon = true) {
                            val r = try {
                                Reader.read(w[2], w[3].toIntOrNull() ?: -1, w[4].takeIf { it != "-" }, w[5].toIntOrNull() ?: 0,
                                    w[6].toIntOrNull()?.coerceIn(0, 2048) ?: 0)
                            } catch (e: Throwable) { log("read", e); org.json.JSONObject().put("ok", false).put("why", "failed") }
                            synchronized(lock) { write(out, "read $req $r") }
                        }
                    }
                    // Acting for agent control (see [Actor]): "act <request> <base64 JSON>", answered
                    // "act <request> <JSON>". Off this thread: a long tap or a scroll takes most of a second.
                    "act" -> if (w.size >= 3) {
                        val req = w[1]
                        thread(name = "act", isDaemon = true) {
                            val r = try {
                                Actor.act(org.json.JSONObject(String(android.util.Base64.decode(w[2], android.util.Base64.NO_WRAP))))
                            } catch (e: Throwable) { log("act", e); org.json.JSONObject().put("ok", false).put("why", "failed") }
                            synchronized(lock) { write(out, "act $req $r") }
                        }
                    }
                    // While an agent is using the tablet: "watch 1" reports each real touch on the screen as
                    // "touched", until "watch 0" (see [Touches]).
                    "watch" -> if (w.getOrNull(1) == "1") Touches.start { synchronized(lock) { write(out, "touched") } }
                        else Touches.stop()
                    // Control went idle or was stopped: stop reading the screen (UiAutomation counts as an
                    // accessibility service while it's connected).
                    "unread" -> thread(isDaemon = true) { Reader.disconnect() }
                    // Wakes the screen, as the power button does (never past the lock screen).
                    "wake" -> try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")) } catch (e: Throwable) { log("wake", e) }
                    "ping" -> synchronized(lock) { write(out, "pong") }
                    "quit" -> { Log.i(TAG, "asked to quit"); exitProcess(0) }
                }
            }
            synchronized(lock) { if (client === out) client = null }
            Touches.stop()
            thread(isDaemon = true) { Reader.disconnect() }
        }
    }

    private fun write(out: OutputStream, line: String) {
        try { out.write("$line\n".toByteArray()); out.flush() } catch (_: Exception) {}
    }

    /**
     * Whether a window on screen asks Android to hide other apps' overlays while it shows: Android's own permission
     * screens and installer do, as may Settings and banking apps. Android doesn't tell the app it hides.
     */
    private fun overlaysHidden(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "window", "visible"))
        val found = p.inputStream.bufferedReader().useLines { lines ->
            lines.any { it.contains("pfl=") && it.contains("HIDE_NON_SYSTEM_OVERLAY_WINDOWS") }
        }
        p.destroy()
        found
    } catch (e: Throwable) { Log.w(TAG, "overlays: $e"); false }

    /** A floating window was on screen at the last look; [hides] is what [overlaysHidden] said then. */
    private var floatingShown = false
    private var hides = false
    private var lastList = ""

    /**
     * Sends the task list when it has changed, at most a few times a second. Android says when tasks open, close or
     * come forward, but not when a floating window is dragged or resized, so while one is on screen the list is also
     * read every 2 s (and otherwise every 30 s, just in case).
     */
    private fun sendLoop() {
        while (true) {
            val nudged: Boolean
            synchronized(lock) {
                while (client == null) lock.wait()
                if (!changed) lock.wait(if (floatingShown) 2_000 else 30_000)
                nudged = changed
                changed = false
            }
            Thread.sleep(150)   // let a burst of changes settle
            val list = try { tasks() } catch (e: Throwable) { Log.w(TAG, "tasks: $e"); continue }
            val body = list.joinToString("\t") { it.line() }
            floatingShown = list.any { it.visible && it.mode == 5 }
            // "tasks hides": a window on screen hides other apps' overlays, so TabDesk's menus can't go above
            // floating windows just now. Only looked at while a floating window shows, as that's when it matters,
            // and when something happened rather than on every 2 s look.
            if (nudged || body != lastList) hides = floatingShown && overlaysHidden()
            lastList = body
            val now = (if (hides) "tasks hides\t" else "tasks\t") + body
            synchronized(lock) {
                val out = client ?: return@synchronized
                if (now != lastSent) { write(out, now); lastSent = now }
            }
        }
    }

    /**
     * Asks Android to tell the helper whenever tasks change (apps opened, moved, closed). The listener's calls are
     * all "something changed", so instead of implementing Android's hidden ITaskStackListener, a plain Binder
     * stands in for it and treats any call as a nudge to re-read the list.
     */
    private fun watchTasks() {
        val nudge = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString("android.app.ITaskStackListener"); return true }
                synchronized(lock) { changed = true; lock.notifyAll() }
                return true
            }
        }
        try {
            val iface = Class.forName("android.app.ITaskStackListener")
            val listener = Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, nudge)
            atm.javaClass.getMethod("registerTaskStackListener", iface).invoke(atm, listener)
            Log.i(TAG, "watching tasks")
        } catch (e: Throwable) {
            Log.w(TAG, "can't watch tasks, checking every few seconds instead: $e")
            thread(isDaemon = true) { while (true) { Thread.sleep(3000); synchronized(lock) { changed = true; lock.notifyAll() } } }
        }
    }
}
