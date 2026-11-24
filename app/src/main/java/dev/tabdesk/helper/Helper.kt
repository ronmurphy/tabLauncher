package dev.tabdesk.helper

import android.content.Intent
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
 * fixed commands; it never runs anything it's sent.
 */
object Helper {
    private const val TAG = "TabDeskHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "serve" -> serve()
            "tasks" -> tasks().map { it.line() }.ifEmpty { listOf("(no tasks)") }.forEach { println(it) }
            "close" -> println("close ${args[1]}: ${close(args[1].toInt())}")
            else -> println("usage: serve | tasks | close <task id>")
        }
    }

    // ---------------------------------------------------------------- tasks

    private val atm: Any by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }
    private val int = Int::class.javaPrimitiveType

    class Task(val id: Int, val pkg: String, val running: Boolean, val visible: Boolean, val lastActive: Long) {
        fun line() = "$id $pkg ${if (running) 1 else 0} ${if (visible) 1 else 0} $lastActive"
    }

    /** Closes a task, as swiping it away in Recents does. */
    private fun close(taskId: Int) = atm.javaClass.getMethod("removeTask", int).invoke(atm, taskId) as Boolean

    /** The tasks in Recents, newest first. */
    private fun tasks(): List<Task> {
        val slice = atm.javaClass.getMethod("getRecentTasks", int, int, int)
            .invoke(atm, 50, 2 /* RECENT_IGNORE_UNAVAILABLE */, 0 /* user */)!!
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
        return list.filterNotNull().mapNotNull { t ->
            val pkg = (field(t, "baseIntent") as? Intent)?.component?.packageName ?: return@mapNotNull null
            Task(field(t, "taskId") as Int, pkg, field(t, "isRunning") == true, field(t, "isVisible") == true,
                (field(t, "lastActiveTime") as? Long) ?: 0L)
        }
    }

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }

    // ---------------------------------------------------------------- serving TabDesk

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
                when (w[0]) {
                    "close" -> w.getOrNull(1)?.toIntOrNull()?.let { id ->
                        val ok = try { close(id) } catch (e: Exception) { Log.w(TAG, "close $id: $e"); false }
                        synchronized(lock) { write(out, "closed $id ${if (ok) 1 else 0}") }
                    }
                    "ping" -> synchronized(lock) { write(out, "pong") }
                    "quit" -> { Log.i(TAG, "asked to quit"); exitProcess(0) }
                }
            }
            synchronized(lock) { if (client === out) client = null }
        }
    }

    private fun write(out: OutputStream, line: String) {
        try { out.write("$line\n".toByteArray()); out.flush() } catch (_: Exception) {}
    }

    /** Sends the task list when it has changed, at most a few times a second. */
    private fun sendLoop() {
        while (true) {
            synchronized(lock) {
                while (!changed || client == null) lock.wait(30_000)   // also re-checks every 30 s, just in case
                changed = false
            }
            Thread.sleep(150)   // let a burst of changes settle
            val now = try { tasks().joinToString("\t") { it.line() } } catch (e: Throwable) { Log.w(TAG, "tasks: $e"); continue }
            synchronized(lock) {
                val out = client ?: return@synchronized
                if (now != lastSent) { write(out, "tasks\t$now"); lastSent = now }
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
