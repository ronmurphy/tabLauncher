package dev.tabdesk.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.tabdesk.BuildInfo
import dev.tabdesk.Prefs
import dev.tabdesk.helper.AdbKey
import dev.tabdesk.helper.HelperClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread

/**
 * The service another app binds to let an agent use this tablet (docs/agent-control.md, Part 1). Any app may call
 * version() and status(); everything else works only for the app the user approved in Customize → Agent.
 *
 * A caller is known by its Binder UID, never by anything it sends. Each caller's tools/call requests run one at a
 * time, in order, on its own thread; initialize, ping and tools/list are answered at once.
 */
class AgentService : Service() {
    companion object {
        private const val TAG = "TabDeskAgent"
        const val MAX_CHARS = 32 * 1024
        private const val MAX_ID = 200
        private const val RATE_PER_SECOND = 4
        private const val STREAM_STALL_MS = 10_000L
        /** How long a call waits for the helper to connect before answering helper_not_running. */
        private const val HELPER_WAIT_MS = 2_000L
        /** At most this many of one caller's tools/call requests waiting or running; more are refused at once. */
        private const val MAX_RUNNING = 8
        /** A refusal by the checks goes in the log at most once in this long per caller and reason. */
        private const val LOG_REFUSAL_EVERY_MS = 10_000L
        /** The MCP versions TabDesk speaks, newest first. It uses nothing that differs between them. */
        private val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18")

        @Volatile private var instance: AgentService? = null

        /** Stop on TabDesk's indicator or notification: drops every running request. */
        fun stopFromTabDesk() {
            instance?.dropAll()
            Agent.stop(null, "tabdesk")
        }
    }

    /** One request on its way. A cancelled one never gets an answer. */
    private class Call(val id: String, val mcpId: Any?) {
        @Volatile var cancelled = false
        @Volatile var future: Future<*>? = null
        @Volatile var pipe: ParcelFileDescriptor? = null
        fun cancel() {
            cancelled = true
            future?.cancel(true)
            try { pipe?.close() } catch (_: Exception) {}
        }
    }

    /** A connected app: one per UID. */
    private inner class Caller(val uid: Int, val pkg: String, @Volatile var cert: String?, @Volatile var callback: IAgentCallback) {
        val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "agent-$pkg") }
        val running = ConcurrentHashMap<String, Call>()
        val recent = ArrayDeque<Long>()
        /** When each refusal by the checks last went in the log, by its code. Only the caller's own thread uses it. */
        val logged = HashMap<String, Long>()
        var lastState = ""
        var lastReason = ""
        var death: IBinder.DeathRecipient? = null
    }

    private val callers = ConcurrentHashMap<Int, Caller>()
    private val main = Handler(Looper.getMainLooper())
    private val onState: (String) -> Unit = {
        pushStates()
        // UiAutomation counts as an accessibility service while the helper reads: let go of it once control isn't active.
        if (Agent.control != Agent.ACTIVE) HelperClient.stopReading()
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Agent.init(this)
        AgentLog.init(this)
        instance = this
        Agent.stateListeners += onState
        // The service can start without the home screen, e.g. when Android restarts TabDesk's process for a caller
        // after a crash: connect to the helper here too, or calls would wait for the home screen to come back.
        if (dev.tabdesk.helper.Setup.ready(this)) HelperClient.ensure(this)
    }

    override fun onDestroy() {
        Agent.stateListeners -= onState
        dropAll()
        callers.values.forEach { it.exec.shutdownNow() }
        callers.clear()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** The single package behind a UID; null for a shared UID or TabDesk itself, which are refused. */
    private fun packageOf(uid: Int): String? {
        if (uid == Process.myUid()) return null
        val pkgs = try { packageManager.getPackagesForUid(uid) } catch (_: Exception) { null }
        return pkgs?.singleOrNull()
    }

    private val binder = object : IAgentService.Stub() {
        override fun version(): Int = Agent.INTERFACE

        override fun status(): String {
            val pkg = packageOf(Binder.getCallingUid())
            val token = Binder.clearCallingIdentity()
            try { return statusFor(pkg) } finally { Binder.restoreCallingIdentity(token) }
        }

        override fun connect(callback: IAgentCallback?) {
            callback ?: return
            val uid = Binder.getCallingUid()
            val pkg = packageOf(uid)
            val token = Binder.clearCallingIdentity()
            try { connectCaller(uid, pkg, callback) } finally { Binder.restoreCallingIdentity(token) }
        }

        override fun send(id: String?, json: String?) {
            val c = callers[Binder.getCallingUid()] ?: return   // connect() comes first; there's nowhere to answer
            val token = Binder.clearCallingIdentity()
            try { receive(c, id, json) } finally { Binder.restoreCallingIdentity(token) }
        }

        override fun cancel(id: String?) {
            val c = callers[Binder.getCallingUid()] ?: return
            id?.let { c.running.remove(it)?.cancel() }
        }

        override fun stop() {
            val c = callers[Binder.getCallingUid()] ?: return
            if (Agent.refusalFor(c.pkg, c.cert) != null) return
            c.running.values.forEach { it.cancel() }
            c.running.clear()
            Agent.stop(c.pkg, "caller")
        }

        override fun resume() {
            val c = callers[Binder.getCallingUid()] ?: return
            if (Agent.refusalFor(c.pkg, c.cert) != null) return
            Agent.resume(c.pkg)
        }
    }

    // ------------------------------------------------------------ status and connecting

    private fun statusFor(pkg: String?): String {
        val cert = pkg?.let { Agent.certOf(packageManager, it) }
        val approval = if (pkg == null) "none" else Agent.approvalOf(pkg, cert)
        val o = JSONObject()
            .put("interface", Agent.INTERFACE)
            .put("tools", Agent.TOOLS)
            .put("tabdesk", BuildInfo.version(this))
            .put("enabled", Agent.enabled)
            .put("approval", approval)
            .put("helper", helperState())
        if (approval == "approved" || approval == "lapsed") pkg?.let { Agent.lapsesAt(it) }?.let { o.put("lapses", iso(it)) }
        if (approval == "approved" && pkg != null) {
            o.put("tablet", Agent.tablet())
            o.put("control", Agent.stateFor(pkg, cert).first)
        }
        return o.toString()
    }

    private fun helperState() = when {
        HelperClient.connected -> "running"
        !AdbKey.exists(this) -> "not_set_up"
        HelperClient.starting -> "starting"
        else -> "not_running"
    }

    private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    private fun connectCaller(uid: Int, pkg: String?, callback: IAgentCallback) {
        if (pkg == null) {
            // A shared UID, or TabDesk itself: never approved, so only told so.
            try { callback.onControlState(Agent.REVOKED, "not_approved") } catch (_: RemoteException) {}
            return
        }
        val cert = Agent.certOf(packageManager, pkg)
        val c = callers[uid]?.also { old ->
            old.death?.let { try { old.callback.asBinder().unlinkToDeath(it, 0) } catch (_: Exception) {} }
            old.callback = callback
            old.cert = cert
            old.lastState = ""; old.lastReason = ""
        } ?: Caller(uid, pkg, cert, callback).also { callers[uid] = it }
        val death = IBinder.DeathRecipient {
            // The caller's process died: its running requests are dropped.
            if (callers[uid] === c && c.callback === callback) {
                c.running.values.forEach { it.cancel() }
                c.running.clear()
                callers.remove(uid)
                c.exec.shutdownNow()
            }
        }
        try { callback.asBinder().linkToDeath(death, 0); c.death = death } catch (_: RemoteException) { callers.remove(uid); return }
        if (cert != null && Agent.approvalOf(pkg, cert) in setOf("none", "asked", "certificate_changed")) Agent.noteAsked(pkg, cert)
        pushState(c)
    }

    private fun pushStates() = callers.values.forEach { pushState(it) }

    /** Tells [c] its control state if it changed since it was last told. */
    private fun pushState(c: Caller) {
        val (state, why) = Agent.stateFor(c.pkg, c.cert)
        val reason = if (state == Agent.IDLE && why.isEmpty()) when (c.lastState) {
            Agent.REVOKED -> if (c.lastReason == "switched_off") "switched_on" else "approved"
            Agent.STOPPED, Agent.PAUSED -> "resumed"
            else -> ""
        } else why
        synchronized(c) {
            if (state == c.lastState && reason == c.lastReason) return
            if (state == c.lastState && state != Agent.REVOKED) return
            c.lastState = state; c.lastReason = reason
        }
        try { c.callback.onControlState(state, reason) } catch (_: RemoteException) {}
    }

    // ------------------------------------------------------------ messages

    private fun receive(c: Caller, id: String?, json: String?) {
        if (id.isNullOrEmpty() || id.length > MAX_ID) { Log.w(TAG, "${c.pkg}: a message with no usable id"); return }
        if (json == null || json.length > MAX_CHARS) { answer(c, id, null, rpcError(null, -32600, "The message is over 32 Ki characters.")); return }
        val msg = try { JSONObject(json) } catch (_: Exception) {
            answer(c, id, null, rpcError(null, -32700, "Not JSON.")); return
        }
        val method = msg.opt("method") as? String
        val hasId = msg.has("id")
        val mcpId = msg.opt("id")
        if (method == null || msg.optString("jsonrpc") != "2.0") {
            if (hasId || method == null) answer(c, id, null, rpcError(mcpId, -32600, "Not a valid JSON-RPC request."))
            return
        }
        if (!hasId) {   // a notification: no answer
            if (method == "notifications/cancelled") {
                val target = msg.optJSONObject("params")?.opt("requestId")
                c.running.entries.firstOrNull { it.value.mcpId == target }?.let { c.running.remove(it.key)?.cancel() }
            }
            return
        }
        if (c.running.containsKey(id)) { answer(c, id, null, rpcError(mcpId, -32600, "That id is still running.")); return }
        val params = msg.optJSONObject("params") ?: JSONObject()
        when (method) {
            "initialize" -> answer(c, id, null, rpcResult(mcpId, initialize(c, params)))
            "ping" -> answer(c, id, null, rpcResult(mcpId, JSONObject()))
            "tools/list" -> answer(c, id, null, rpcResult(mcpId, Tools.listResult()))
            "tools/call" -> {
                val name = params.opt("name") as? String
                val tool = name?.let { Tools.find(it) }
                if (tool == null) { answer(c, id, null, rpcError(mcpId, -32602, "Unknown tool: ${name ?: "(none)"}")); return }
                val args = params.opt("arguments") as? JSONObject ?: JSONObject()
                if (c.running.size >= MAX_RUNNING) {
                    // Not logged: a flood of these would push everything else out of the log.
                    answer(c, id, null, rpcResult(mcpId, Tools.refused("rate_limited", "Too many calls waiting: wait for some to finish.")))
                    return
                }
                val call = Call(id, mcpId)
                c.running[id] = call
                call.future = c.exec.submit { runCall(c, call, tool, args) }
            }
            else -> answer(c, id, null, rpcError(mcpId, -32601, "Unknown method: $method"))
        }
    }

    private fun initialize(c: Caller, params: JSONObject): JSONObject {
        // The client's own if TabDesk speaks it, else TabDesk's newest, as MCP asks: never one TabDesk doesn't know.
        val asked = params.optString("protocolVersion")
        val version = if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first()
        val approved = Agent.refusalFor(c.pkg, c.cert) == null
        val tablet = if (approved) Agent.tablet() else null
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "tabdesk")
                .put("title", if (tablet != null) "TabDesk on $tablet" else "TabDesk")
                .put("version", BuildInfo.version(this)))
            .put("instructions", "Reads, and later uses, apps on a tablet, within limits the tablet enforces. " +
                "If a call is refused, tell the user why, and don't look for another way to what was refused.")
            .put("_meta", JSONObject().put("dev.tabdesk/tablet", tablet ?: JSONObject.NULL).put("dev.tabdesk/tools", Agent.TOOLS))
    }

    /** One tools/call, on the caller's own thread: the checks in Part 3's order, then the tool. */
    private fun runCall(c: Caller, call: Call, tool: Tools.Tool, args: JSONObject) {
        val deadline = System.currentTimeMillis() + tool.longestMs
        var began = false
        var checked = false   // whether the checks passed, so it's a real call (logged every time)
        val (result, front) = try {
            val refusal = check(c, deadline)
                ?: Agent.beginCall(c.pkg)?.let { controlRefused(it) }   // control again, now under Agent's lock
            if (refusal != null) refusal to null
            else {
                began = true; checked = true
                Tools.run(this, c.pkg, tool, args, deadline)
            }
        } catch (e: InterruptedException) {
            c.running.remove(call.id, call)
            return   // cancelled
        } catch (e: Exception) {
            Log.w(TAG, "${tool.name}: $e")
            Tools.refused("timed_out", "The tablet didn't answer in time.") to null
        } finally {
            if (began) Agent.endCall()
        }
        val outcome = Tools.outcome(result)
        if (checked || quietEnough(c, outcome)) AgentLog.add(c.pkg, tool.name, front, outcome)
        // A cancelled call was taken out of running when it was cancelled, and gets no answer.
        if (!call.cancelled) answer(c, call.id, call, rpcResult(call.mcpId, result)) else c.running.remove(call.id, call)
    }

    /** Whether a refusal by the checks may go in the log: not if the same one from [c] did in the last 10 seconds. */
    private fun quietEnough(c: Caller, outcome: String): Boolean {
        val now = System.currentTimeMillis()
        val last = c.logged[outcome]
        if (last != null && now - last < LOG_REFUSAL_EVERY_MS) return false
        c.logged[outcome] = now
        return true
    }

    private fun controlRefused(code: String): JSONObject = when (code) {
        "busy" -> Tools.refused("busy", "${Agent.controller?.let { Agent.label(it) } ?: "Another app"} is using the tablet.")
        "stopped" -> Tools.refused("stopped", "The user stopped the agent. It can go on after the user's next message.")
        else -> Tools.refused("paused", "The user touched the screen. The agent can go on after the user's next message.")
    }

    /** Part 3's limits 1–5, which every tool checks. Null when the call may go ahead. */
    private fun check(c: Caller, deadline: Long): JSONObject? {
        val app = Agent.label(c.pkg)
        when (Agent.refusalFor(c.pkg, c.cert)) {
            "switched_off" -> return Tools.refused("switched_off", "Agent control is switched off on the tablet (TabDesk → Customize → Agent).")
            "not_approved", "revoked" -> return Tools.refused("not_approved", "$app isn't approved: open TabDesk → Customize → Agent on the tablet.")
            "certificate_changed" -> return Tools.refused("certificate_changed", "This copy of $app isn't the one that was approved " +
                "(its signature changed): approve it again in TabDesk → Customize → Agent.")
            "lapsed" -> return Tools.refused("lapsed", "The approval ran out: open TabDesk → Customize → Agent.")
        }
        Agent.controlRefusal(c.pkg)?.let { return controlRefused(it) }
        val now = System.currentTimeMillis()
        synchronized(c.recent) {
            while (c.recent.isNotEmpty() && now - c.recent.first() >= 1000) c.recent.removeFirst()
            if (c.recent.size >= RATE_PER_SECOND) return Tools.refused("rate_limited", "Too many calls: wait a second.")
            c.recent.addLast(now)
        }
        if (!HelperClient.connected && AdbKey.exists(this)) {
            // It may just be connecting (a restarted TabDesk finds the running helper within a second or two). Only
            // the helper already running is tried: starting a new one is the home screen's job, at its own pace, so
            // calls can't make TabDesk restart the helper or switch debugging on over and over.
            HelperClient.reconnect(this)
            val until = minOf(System.currentTimeMillis() + HELPER_WAIT_MS, deadline - 300)
            while (!HelperClient.connected && System.currentTimeMillis() < until) Thread.sleep(100)
        }
        if (!HelperClient.connected) return if (!AdbKey.exists(this))
            Tools.refused("helper_not_set_up", "TabDesk's helper isn't set up on this tablet (TabDesk → Customize → Helper).")
            else Tools.refused("helper_not_running", "TabDesk's helper isn't running.")
        return null
    }

    // ------------------------------------------------------------ answering

    private fun rpcResult(mcpId: Any?, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", mcpId ?: JSONObject.NULL).put("result", result).toString()

    private fun rpcError(mcpId: Any?, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", mcpId ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()

    /**
     * Sends [json] as reply, or through a pipe (replyStream) when it's over 32 Ki characters. A tools/call's [call]
     * stays in running until its answer is sent, or its stream is done, so cancelling it closes the stream.
     */
    private fun answer(c: Caller, id: String, call: Call?, json: String) {
        var streaming = false
        try {
            if (json.length <= MAX_CHARS) {
                call?.let { c.running.remove(id, it) }   // first, so the caller may use the id again once it has the reply
                c.callback.reply(id, json)
                return
            }
            val (read, write) = ParcelFileDescriptor.createPipe()
            call?.pipe = write
            // Cancelled meanwhile? A cancel after this line sees the pipe and closes it itself.
            if (call?.cancelled == true) { read.close(); write.close(); return }
            c.callback.replyStream(id, read)
            read.close()
            streaming = true
            thread(name = "agent-stream", isDaemon = true) {
                try { stream(write, json.toByteArray(Charsets.UTF_8)) } finally { call?.let { c.running.remove(id, it) } }
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "${c.pkg}: couldn't answer $id: $e")
        } catch (e: Exception) {
            Log.w(TAG, "${c.pkg}: couldn't answer $id: $e")
        } finally {
            if (!streaming) call?.let { c.running.remove(id, it) }
        }
    }

    /** Writes a big answer into the pipe. If the caller stops reading for 10 seconds, the pipe is closed. */
    private fun stream(pipe: ParcelFileDescriptor, bytes: ByteArray) {
        var progress = System.currentTimeMillis()
        val watch = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - progress >= STREAM_STALL_MS) { try { pipe.close() } catch (_: Exception) {} }
                else main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(watch, 1000)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { out ->
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(16 * 1024, bytes.size - i)
                    out.write(bytes, i, n)
                    i += n
                    progress = System.currentTimeMillis()
                }
            }
        } catch (_: Exception) {
            // Cancelled or stalled: the caller sees the stream end early.
        } finally {
            main.removeCallbacks(watch)
        }
    }

    /** Stop on TabDesk: every caller's running requests go, unanswered. */
    private fun dropAll() {
        for (c in callers.values) {
            c.running.values.forEach { it.cancel() }
            c.running.clear()
        }
    }
}
