package dev.tabdesk.agentcaller

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import dev.tabdesk.agent.IAgentCallback
import dev.tabdesk.agent.IAgentService
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A test caller for TabDesk's agent service (plan T9), as DroidACP would call it. Each command comes as an intent
 * (see run.sh): to [MainActivity], which comes to the front, or as a broadcast to [CommandReceiver], which leaves
 * whatever app is in front there (to read it). Everything that happens is logged with the tag AgentCaller, e.g.
 * "REPLY c1:3 {…}".
 *
 * Commands (extra "cmd"): bind, unbind, version, status, connect, init, list, call (extras "tool", "args" JSON, and
 * "meta64", the call's `_meta` as base64 JSON),
 * raw (extra "json" or "b64"), cancel (extra "id"), stop, resume, burst (extra "n": that many usage calls at once),
 * die (the process exits, as if it crashed), pw (shows a password field holding "hunter2", for the screen reader's tests),
 * over (pw, then tools/seethrough's see-through card over it in this task), heads (a heads-up notification, to cover
 * the top of the screen), overlay / overlay-off (a small window over every app with a text field that takes the
 * keyboard's focus, like a chat bubble, so the focus is in another app's window). heads and overlay are for plan
 * T12; on the emulator, grant them first: pm grant dev.tabdesk.agentcaller android.permission.POST_NOTIFICATIONS,
 * and appops set dev.tabdesk.agentcaller SYSTEM_ALERT_WINDOW allow.
 *
 * The shared folder (tool list 3, plan T13), as PlasmaLLM's put_file and get_file would use it: put (extras "name",
 * "size", optional "chunk" bytes and "stopafter" chunks: it stops there, leaving the upload unfinished) writes made-up
 * bytes chunk by chunk through sendStream and logs their SHA-256; get (extra "name") reads a file back chunk by chunk
 * and logs its size and SHA-256. streamping, streamhalf (half a message, then the pipe closes), streamstall (half,
 * then 13 s with nothing) and streambig (2.5 MiB) test sendStream itself.
 */
class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(out) })
        Caller.ui = { s -> runOnUiThread { if (::out.isInitialized) out.append(s.take(2000) + "\n") } }
        handle(intent)
    }

    override fun onDestroy() {
        Caller.ui = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when (i?.getStringExtra("cmd")) {
            // A password field holding "hunter2" and a plain one, to check the screen reader never reads the first.
            "pw" -> {
                val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
                box.addView(android.widget.EditText(this).apply {
                    hint = "Password"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText("hunter2")
                })
                box.addView(android.widget.EditText(this).apply { hint = "Name"; setText("plain text") })
                box.addView(android.widget.Button(this).apply { text = "Roll d20" })
                setContentView(box)
            }
            // The password screen, then tools/seethrough's see-through card over it in this same task.
            "over" -> {
                handle(Intent(i).putExtra("cmd", "pw"))
                try { startActivity(Intent().setClassName("dev.tabdesk.seethrough", "dev.tabdesk.seethrough.Card")) }
                catch (e: Exception) { Log.w(BuildConfig.TAG, "over: $e") }
            }
            else -> Caller.handle(applicationContext, i)
        }
    }
}

/** Commands as broadcasts: am broadcast -n dev.tabdesk.agentcaller/.CommandReceiver --es cmd … */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Caller.handle(context.applicationContext, intent)
}

/** The connection to TabDesk, kept for as long as the process lives, and the commands. */
object Caller {
    private val TAG = BuildConfig.TAG
    private var service: IAgentService? = null
    private var conn: ServiceConnection? = null
    private var next = 0
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var ui: ((String) -> Unit)? = null

    /** Answers that put and get wait for, by id: they get the answer's JSON instead of the log. */
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<String>>()

    private val callback = object : IAgentCallback.Stub() {
        override fun reply(id: String, json: String) {
            waiters[id]?.let { it.offer(json); return }
            log("REPLY $id $json")
        }
        override fun onControlState(state: String, reason: String) = log("STATE $state $reason")
        override fun replyStream(id: String, pipe: ParcelFileDescriptor) {
            thread {
                val bytes = try { ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() } } catch (e: Exception) { null }
                val w = waiters[id]
                if (w != null) { w.offer(bytes?.let { String(it) } ?: "(stream failed)"); return@thread }
                log("STREAM $id ${bytes?.size ?: -1} bytes ${bytes?.let { String(it) } ?: "(failed)"}")
            }
        }
    }

    /** Sends [json] through a pipe (sendStream): [cut] bytes, then [holdMs] with nothing, then the rest (or not, if [rest] is false). */
    private fun sendStream(id: String, json: String, cut: Int = -1, holdMs: Long = 0, rest: Boolean = true) {
        val s = service ?: return log("ERROR not bound")
        val bytes = json.toByteArray()
        val (r, w) = ParcelFileDescriptor.createPipe()
        s.sendStream(id, r)
        r.close()
        thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(w).use { o ->
                    val first = if (cut < 0) bytes.size else cut
                    o.write(bytes, 0, first)
                    if (holdMs > 0) Thread.sleep(holdMs)
                    if (rest && first < bytes.size) o.write(bytes, first, bytes.size - first)
                }
            } catch (e: Exception) { log("STREAM_WRITE_FAILED $id $e") }
        }
    }

    /** One tools/call, waiting for its answer (up to 20 s): through sendStream when it's over 32 Ki characters. */
    private fun callAndWait(tool: String, args: JSONObject): JSONObject? {
        val id = id()
        val json = request("tools/call", JSONObject().put("name", tool).put("arguments", args)).toString()
        val q = java.util.concurrent.ArrayBlockingQueue<String>(1)
        waiters[id] = q
        try {
            if (json.length > 32 * 1024) sendStream(id, json) else service?.send(id, json) ?: return null.also { log("ERROR not bound") }
            val answer = q.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: return null.also { log("NO_ANSWER $id $tool") }
            return JSONObject(answer).optJSONObject("result") ?: null.also { log("RPC_ERROR $id $answer") }
        } finally { waiters.remove(id) }
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun textOf(result: JSONObject) = result.getJSONArray("content").let { it.getJSONObject(it.length() - 1).getString("text") }

    private fun put(name: String, size: Int, chunk: Int, stopAfter: Int) {
        val data = ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }
        log("PUT $name $size bytes sha256 ${sha256(data)}")
        var offset = 0
        var upload: String? = null
        var n = 0
        val began = System.currentTimeMillis()
        do {
            val end = minOf(offset + chunk, size)
            val last = end == size
            val args = JSONObject().put("name", name).put("size", size).put("offset", offset)
                .put("data", java.util.Base64.getEncoder().encodeToString(data.copyOfRange(offset, end)))
            upload?.let { args.put("upload", it) }
            if (last) args.put("last", true)
            val r = callAndWait("folder_write", args) ?: return
            if (r.optBoolean("isError")) return log("PUT_REFUSED at $offset ${textOf(r)}")
            val o = JSONObject(textOf(r))
            upload = o.optString("upload").takeIf { it.isNotEmpty() } ?: upload
            offset = end
            n++
            if (last) return log("PUT_DONE ${o} in ${System.currentTimeMillis() - began} ms")
            if (stopAfter in 1..n) return log("PUT_STOPPED after $n chunks, upload $upload")
        } while (true)
    }

    private fun get(name: String, length: Int) {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        var said = ""
        val began = System.currentTimeMillis()
        while (true) {
            val r = callAndWait("folder_read", JSONObject().put("name", name).put("offset", offset).put("length", length)) ?: return
            if (r.optBoolean("isError")) return log("GET_REFUSED at $offset ${textOf(r)}")
            val o = JSONObject(textOf(r))
            val b = java.util.Base64.getDecoder().decode(o.getString("data"))
            out.write(b)
            offset += b.size
            if (o.getBoolean("last")) { said = o.optString("sha256"); break }
        }
        val all = out.toByteArray()
        log("GET_DONE $name ${all.size} bytes sha256 ${sha256(all)} (TabDesk said $said) in ${System.currentTimeMillis() - began} ms")
    }

    private fun log(s: String) {
        // Long lines (a screen read) go to the log in parts: logcat cuts lines at about 4000 characters.
        s.chunked(3500).forEachIndexed { i, part -> Log.i(TAG, if (i == 0) part else "  …$part") }
        ui?.invoke(s)
    }

    private fun id() = "c1:${++next}"

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A heads-up: a notification on a channel of high importance, which Android shows over the top of the screen. */
    private fun heads() {
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("heads", "Heads-up test", android.app.NotificationManager.IMPORTANCE_HIGH))
        nm.notify(1, android.app.Notification.Builder(app, "heads")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Heads-up test").setContentText("Covers the top of the screen for a few seconds.")
            .setCategory(android.app.Notification.CATEGORY_MESSAGE)
            .build())
        log("HEADS posted")
    }

    private var bubble: android.view.View? = null

    /**
     * A small window over every app (TYPE_APPLICATION_OVERLAY) with a text field. It's focusable, so it takes the
     * keyboard's focus as it's added, while the app under it stays the one in front: "the focus in another app's
     * window" (plan T12). Touches outside it go through to the app under it.
     */
    private fun overlay(on: Boolean) {
        val wm = app.getSystemService(android.view.WindowManager::class.java)
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        if (!on) return log("OVERLAY off")
        val field = android.widget.EditText(app).apply {
            hint = "Bubble chat"; setBackgroundColor(0xFFFFF59D.toInt()); setPadding(24, 24, 24, 24)
        }
        val lp = android.view.WindowManager.LayoutParams(600, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END; x = 40; y = 160; title = "Bubble"
        }
        try { wm.addView(field, lp); bubble = field; field.requestFocus(); log("OVERLAY on") }
        catch (e: Exception) { log("ERROR overlay: $e") }
    }

    private fun send(json: JSONObject) {
        val s = service ?: return log("ERROR not bound")
        val id = id()
        log("SEND $id ${json}")
        s.send(id, json.toString())
    }

    private fun request(method: String, params: JSONObject? = null) =
        JSONObject().put("jsonrpc", "2.0").put("id", next + 1).put("method", method).also { if (params != null) it.put("params", params) }

    private lateinit var app: Context

    fun handle(ctx: Context, i: Intent?) {
        app = ctx
        val cmd = i?.getStringExtra("cmd") ?: return
        exec.execute {
            try { run(cmd, i) } catch (e: Exception) { log("ERROR $cmd: $e") }
        }
    }

    private fun run(cmd: String, i: Intent) {
        when (cmd) {
            "bind" -> {
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        service = IAgentService.Stub.asInterface(binder); log("BOUND")
                    }
                    override fun onServiceDisconnected(name: ComponentName) { service = null; log("DISCONNECTED") }
                    override fun onBindingDied(name: ComponentName) { log("BINDING_DIED") }
                }
                conn = c
                val ok = app.bindService(Intent("dev.tabdesk.action.AGENT").setPackage("dev.tabdesk"), c, Context.BIND_AUTO_CREATE)
                log("BIND $ok")
            }
            "unbind" -> { conn?.let { app.unbindService(it) }; conn = null; service = null; log("UNBOUND") }
            "version" -> log("VERSION ${service?.version()}")
            "status" -> log("STATUS ${service?.status()}")
            "connect" -> { service?.connect(callback); log("CONNECTED") }
            "init" -> send(request("initialize", JSONObject().put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "agentcaller").put("version", "1"))))
            "list" -> send(request("tools/list"))
            "call" -> send(request("tools/call", JSONObject().put("name", i.getStringExtra("tool"))
                .put("arguments", JSONObject(i.getStringExtra("args")
                    ?: i.getStringExtra("args64")?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: "{}"))
                .also { p ->
                    // As an agent's host would send it: who the agent is, for its Pixel Person on TabDesk's taskbar.
                    i.getStringExtra("meta64")?.let { p.put("_meta", JSONObject(String(Base64.decode(it, Base64.DEFAULT)))) }
                }))
            "raw" -> {
                val json = i.getStringExtra("json") ?: String(Base64.decode(i.getStringExtra("b64") ?: "", Base64.DEFAULT))
                val s = service ?: return log("ERROR not bound")
                val id = i.getStringExtra("id") ?: id()
                log("SEND $id $json")
                s.send(id, json)
            }
            "big" -> {   // a message over 32 Ki characters
                val s = service ?: return log("ERROR not bound")
                val id = id()
                log("SEND $id (40000 characters)")
                s.send(id, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\"" + "x".repeat(40000) + "\"}")
            }
            "cancel" -> { service?.cancel(i.getStringExtra("id")); log("CANCEL ${i.getStringExtra("id")}") }
            "stop" -> { service?.stop(); log("STOP") }
            "resume" -> { service?.resume(); log("RESUME") }
            "burst" -> repeat(i.getStringExtra("n")?.toIntOrNull() ?: 6) {
                send(request("tools/call", JSONObject().put("name", "apps").put("arguments", JSONObject())))
            }
            "die" -> { log("DYING"); Runtime.getRuntime().halt(1) }
            "put" -> put(i.getStringExtra("name") ?: "test.bin", i.getStringExtra("size")?.toInt() ?: 3_000_000,
                i.getStringExtra("chunk")?.toInt() ?: 1_048_576, i.getStringExtra("stopafter")?.toInt() ?: 0)
            "get" -> get(i.getStringExtra("name") ?: "test.bin", i.getStringExtra("length")?.toInt() ?: 1_048_576)
            "streamping" -> { val id = id(); log("SENDSTREAM $id ping"); sendStream(id, request("ping").toString()) }
            "streamhalf" -> { val id = id(); val j = request("ping").toString(); log("SENDSTREAM $id half a ping")
                sendStream(id, j, cut = j.length / 2, rest = false) }
            "streamstall" -> { val id = id(); val j = request("ping").toString(); log("SENDSTREAM $id half a ping, then 13 s")
                sendStream(id, j, cut = j.length / 2, holdMs = 13_000) }
            "streambig" -> { val id = id(); log("SENDSTREAM $id 2.5 MiB")
                sendStream(id, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\"" + "x".repeat(2_600_000) + "\"}") }
            "heads" -> heads()
            "overlay" -> main.post { overlay(true) }
            "overlay-off" -> main.post { overlay(false) }
            else -> log("ERROR unknown command $cmd")
        }
    }
}
