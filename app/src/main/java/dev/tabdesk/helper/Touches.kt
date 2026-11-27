package dev.tabdesk.helper

import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * A real touch on the screen, for agent control (plan T11): while TabDesk asks, the helper watches the touchscreen
 * itself (`getevent`, as the shell user may), and says when a finger goes down. What the helper taps for the agent
 * goes in through Android's input manager, not the touchscreen, so it never shows here.
 */
internal object Touches {
    private const val TAG = "TabDeskHelper"
    /** At most one report in this long: a finger's down, its moves and a second finger are one touch. */
    private const val QUIET_MS = 700L

    @Volatile private var proc: Process? = null

    /** Starts watching, if it isn't already; [touched] is called (on the watcher's thread) for each touch. */
    @Synchronized
    fun start(touched: () -> Unit) {
        if (proc != null) return
        val devices = touchscreens()
        if (devices.isEmpty()) { Log.w(TAG, "no touchscreen to watch"); return }
        // getevent watches one device or all of them: all, keeping the touchscreens' lines ("/dev/input/event2: …").
        val p = try { ProcessBuilder("getevent", "-l").redirectErrorStream(true).start() }
            catch (e: Throwable) { Helper.log("getevent", e); return }
        proc = p
        Log.i(TAG, "watching for touches on ${devices.joinToString()}")
        thread(name = "touches", isDaemon = true) {
            var last = 0L
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (devices.none { line.startsWith("$it:") }) return@forEachLine
                    // A finger going down: BTN_TOUCH DOWN, or a new contact's tracking id (not ffffffff, which lifts).
                    val down = (line.contains("BTN_TOUCH") && line.contains("DOWN")) ||
                        (line.contains("ABS_MT_TRACKING_ID") && !line.trimEnd().endsWith("ffffffff"))
                    if (down) {
                        val now = SystemClock.uptimeMillis()
                        if (now - last > QUIET_MS) { last = now; try { touched() } catch (_: Throwable) {} }
                    }
                }
            } catch (_: Throwable) {}
            synchronized(this) { if (proc === p) proc = null }
        }
    }

    @Synchronized
    fun stop() {
        val p = proc ?: return
        proc = null
        try { p.destroy() } catch (_: Throwable) {}
        Log.i(TAG, "stopped watching for touches")
    }

    /** The input devices that are touchscreens: they report where each finger is (ABS_MT_POSITION_X). */
    private fun touchscreens(): List<String> = try {
        val p = ProcessBuilder("getevent", "-pl").redirectErrorStream(true).start()
        val found = ArrayList<String>()
        var dev: String? = null
        p.inputStream.bufferedReader().forEachLine { line ->
            Regex("""^add device \d+: (\S+)""").find(line)?.let { dev = it.groupValues[1] }
            if (line.contains("ABS_MT_POSITION_X")) dev?.let { if (it !in found) found += it }
        }
        p.waitFor()
        found
    } catch (e: Throwable) { Helper.log("touchscreens", e); emptyList() }
}
