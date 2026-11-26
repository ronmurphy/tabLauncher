package dev.tabdesk.agentcaller

import android.app.Activity
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
 * (see run.sh), and everything that happens is logged with the tag AgentCaller, e.g. "REPLY c1:3 {…}".
 *
 * Commands (extra "cmd"): bind, unbind, version, status, connect, init, list, call (extras "tool", "args" JSON),
 * raw (extra "json" or "b64"), cancel (extra "id"), stop, resume, burst (extra "n": that many usage calls at once),
 * die (the process exits, as if it crashed).
 */
class MainActivity : Activity() {
    companion object {
        private const val TAG = "AgentCaller"
        private var service: IAgentService? = null
        private var conn: ServiceConnection? = null
        private var next = 0
        private val exec = Executors.newSingleThreadExecutor()
    }

    private lateinit var out: TextView

    private val callback = object : IAgentCallback.Stub() {
        override fun reply(id: String, json: String) = log("REPLY $id $json")
        override fun onControlState(state: String, reason: String) = log("STATE $state $reason")
        override fun replyStream(id: String, pipe: ParcelFileDescriptor) {
            thread {
                val bytes = try { ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() } } catch (e: Exception) { null }
                log("STREAM $id ${bytes?.size ?: -1} bytes ${bytes?.let { String(it).take(300) } ?: "(failed)"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(out) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun log(s: String) {
        Log.i(TAG, s)
        runOnUiThread { if (::out.isInitialized) out.append(s.take(2000) + "\n") }
    }

    private fun id() = "c1:${++next}"

    private fun send(json: JSONObject) {
        val s = service ?: return log("ERROR not bound")
        val id = id()
        log("SEND $id ${json}")
        s.send(id, json.toString())
    }

    private fun request(method: String, params: JSONObject? = null) =
        JSONObject().put("jsonrpc", "2.0").put("id", next + 1).put("method", method).also { if (params != null) it.put("params", params) }

    private fun handle(i: Intent?) {
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
                val ok = applicationContext.bindService(Intent("dev.tabdesk.action.AGENT").setPackage("dev.tabdesk"), c, Context.BIND_AUTO_CREATE)
                log("BIND $ok")
            }
            "unbind" -> { conn?.let { applicationContext.unbindService(it) }; conn = null; service = null; log("UNBOUND") }
            "version" -> log("VERSION ${service?.version()}")
            "status" -> log("STATUS ${service?.status()}")
            "connect" -> { service?.connect(callback); log("CONNECTED") }
            "init" -> send(request("initialize", JSONObject().put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "agentcaller").put("version", "1"))))
            "list" -> send(request("tools/list"))
            "call" -> send(request("tools/call", JSONObject().put("name", i.getStringExtra("tool"))
                .put("arguments", JSONObject(i.getStringExtra("args")
                    ?: i.getStringExtra("args64")?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: "{}"))))
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
            else -> log("ERROR unknown command $cmd")
        }
    }
}
