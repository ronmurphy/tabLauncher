package dev.tabdesk.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.tabdesk.HomeActivity
import dev.tabdesk.R
import dev.tabdesk.customize.Customize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Agent control's state (docs/agent-control.md): the master switch, the apps that asked and the ones approved (with
 * their signing certificate and the weekly renewal), the allowed apps, and who is using the tablet now.
 *
 * It's kept in no_backup/, so neither a backup nor a new tablet ever carries an approval over. Only a person at the
 * tablet changes it, in Customize → Agent: the agent can never read or use TabDesk's own screens.
 */
object Agent {
    const val INTERFACE = 1
    const val TOOLS = 1
    const val ACTION_SETTINGS = "dev.tabdesk.action.AGENT_SETTINGS"
    const val LAPSE_MS = 7L * 24 * 60 * 60 * 1000
    private const val WARN_BEFORE_MS = 24L * 60 * 60 * 1000
    private const val ASKED_NOTE_EVERY_MS = 24L * 60 * 60 * 1000
    const val IDLE_AFTER_MS = 15_000L

    const val IDLE = "idle"; const val ACTIVE = "active"; const val PAUSED = "paused"
    const val STOPPED = "stopped"; const val REVOKED = "revoked"

    /** [since]: when it was approved or last renewed. [warned]: the "runs out tomorrow" note went out for [since]. */
    class Approval(val pkg: String, val cert: String, val since: Long, val warned: Boolean = false) {
        val lapses get() = since + LAPSE_MS
    }
    /** An app that asked and isn't approved. [revoked]: the user took its approval back. */
    class Asked(val pkg: String, val cert: String, val at: Long, val notified: Long, val revoked: Boolean = false)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val file get() = File(app.noBackupFilesDir, "agent.json")

    @Volatile var enabled = false; private set
    @Volatile var nameAll = false; private set
    @Volatile private var tabletName: String? = null
    private val approvals = LinkedHashMap<String, Approval>()
    private val asked = LinkedHashMap<String, Asked>()
    private val allowed = LinkedHashSet<String>()

    // Who is using the tablet: one app at a time.
    @Volatile var control = IDLE; private set
    @Volatile var controlReason = ""; private set
    @Volatile var controller: String? = null; private set
    private var running = 0
    private val goIdle = Runnable {
        val went = synchronized(this) { (control == ACTIVE && running == 0).also { if (it) setControl(IDLE, "", null) } }
        if (went) changed("")
    }

    /** For screens: called on the main thread after any change. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** For the service: called with the event's reason ("approved", "switched_on", …) after any change. */
    internal val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        load()
    }

    // ------------------------------------------------------------ reading

    fun tablet(): String = tabletName
        ?: Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
    fun customTabletName() = tabletName

    fun approvals(): List<Approval> = synchronized(this) { approvals.values.toList() }
    fun asked(): List<Asked> = synchronized(this) { asked.values.toList() }
    fun allowed(): Set<String> = synchronized(this) { allowed.toSet() }
    fun isAllowed(pkg: String) = synchronized(this) { pkg in allowed }
    fun isApproved(pkg: String) = synchronized(this) { pkg in approvals }

    /** For status(): none, asked, approved, lapsed or certificate_changed. */
    fun approvalOf(pkg: String, cert: String?): String = synchronized(this) {
        val a = approvals[pkg]
        when {
            a != null && a.cert != cert -> "certificate_changed"
            a != null && System.currentTimeMillis() >= a.lapses -> "lapsed"
            a != null -> "approved"
            pkg in asked -> "asked"
            else -> "none"
        }
    }

    fun lapsesAt(pkg: String): Long? = synchronized(this) { approvals[pkg]?.lapses }

    /** Why [pkg] (signed with [cert]) can't use the tablet now, in the order the doc gives, or null if it can. */
    fun refusalFor(pkg: String, cert: String?): String? = synchronized(this) {
        if (!enabled) return "switched_off"
        when (approvalOf(pkg, cert)) {
            "none", "asked" -> if (asked[pkg]?.revoked == true) "revoked" else "not_approved"
            "certificate_changed" -> "certificate_changed"
            "lapsed" -> "lapsed"
            else -> null
        }
    }

    /** The control state and reason [pkg] should be told about. */
    fun stateFor(pkg: String, cert: String?): Pair<String, String> = synchronized(this) {
        refusalFor(pkg, cert)?.let { return REVOKED to it }
        if (controller == pkg && control != IDLE) control to controlReason else IDLE to ""
    }

    // ------------------------------------------------------------ the person's choices (Customize → Agent)

    fun setEnabled(on: Boolean) {
        synchronized(this) {
            if (enabled == on) return
            enabled = on
            if (!on) setControl(IDLE, "", null)
            save()
        }
        changed(if (on) "switched_on" else "switched_off")
    }

    /** Approves [pkg] as it is signed now. */
    fun approve(pkg: String) {
        val cert = certOf(app.packageManager, pkg) ?: return
        synchronized(this) {
            approvals[pkg] = Approval(pkg, cert, System.currentTimeMillis())
            asked.remove(pkg)
            save()
        }
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_ASKED, pkg.hashCode())
        scheduleCheck()
        changed("approved")
    }

    /** "Keep allowing <app>": another week from now. */
    fun renew(pkg: String) {
        synchronized(this) {
            val a = approvals[pkg] ?: return
            approvals[pkg] = Approval(pkg, a.cert, System.currentTimeMillis())
            save()
        }
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_LAPSING, pkg.hashCode())
        scheduleCheck()
        changed("approved")
    }

    fun revoke(pkg: String) {
        synchronized(this) {
            val a = approvals.remove(pkg) ?: return
            asked[pkg] = Asked(pkg, a.cert, System.currentTimeMillis(), System.currentTimeMillis(), revoked = true)
            if (controller == pkg) setControl(IDLE, "", null)
            save()
        }
        changed("revoked")
    }

    /** Drops an app from "Apps that asked" without approving it. */
    fun dismiss(pkg: String) {
        synchronized(this) { if (asked.remove(pkg) == null) return; save() }
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_ASKED, pkg.hashCode())
        changed("")
    }

    fun setAllowed(pkg: String, on: Boolean) {
        synchronized(this) { if (on) allowed += pkg else allowed -= pkg; save() }
        changed("")
    }

    fun setNameAll(on: Boolean) {
        synchronized(this) { nameAll = on; save() }
        changed("")
    }

    fun setTabletName(name: String?) {
        synchronized(this) { tabletName = name?.trim()?.takeIf { it.isNotEmpty() }; save() }
        changed("")
    }

    // ------------------------------------------------------------ apps asking

    /** A caller that isn't approved connected: note that it asked, and tell the user at most once a day. */
    fun noteAsked(pkg: String, cert: String) {
        val now = System.currentTimeMillis()
        val notify = synchronized(this) {
            if (approvals[pkg]?.cert == cert) return
            val before = asked[pkg]
            val due = before == null || now - before.notified >= ASKED_NOTE_EVERY_MS
            asked[pkg] = Asked(pkg, cert, before?.at ?: now, if (due) now else before!!.notified, before?.revoked == true)
            save()
            due
        }
        if (notify) note(NOTE_ASKED, pkg, "${label(pkg)} wants to use this tablet",
            "Open TabDesk → Customize → Agent to approve it, or leave it.")
        changed("")
    }

    // ------------------------------------------------------------ control

    /** A tools/call from [pkg] starts. The checks have passed. */
    fun beginCall(pkg: String) {
        val was: String
        synchronized(this) {
            main.removeCallbacks(goIdle)
            was = control
            running++
            if (control == IDLE) setControl(ACTIVE, "", pkg)
        }
        if (was == IDLE) changed("")
    }

    fun endCall() {
        synchronized(this) {
            running = (running - 1).coerceAtLeast(0)
            if (running == 0) { main.removeCallbacks(goIdle); main.postDelayed(goIdle, IDLE_AFTER_MS) }
        }
    }

    /** Stop: from the caller ("caller") or TabDesk's indicator or notification ("tabdesk"). */
    fun stop(pkg: String?, reason: String) {
        synchronized(this) {
            if (control == STOPPED) return
            val who = pkg ?: controller ?: return
            main.removeCallbacks(goIdle)
            setControl(STOPPED, reason, who)
        }
        changed("")
    }

    /** The user's next chat message, or Continue: stopped or paused control goes back to idle. */
    fun resume() {
        synchronized(this) {
            if (control != STOPPED && control != PAUSED) return
            setControl(IDLE, "resumed", null)
        }
        changed("resumed")
    }

    private fun setControl(state: String, reason: String, who: String?) {
        control = state; controlReason = reason; controller = who
        if (state == IDLE) running = 0
    }

    // ------------------------------------------------------------ lapses

    /** Warns about approvals running out tomorrow, and tells the service about ones that just ran out. */
    fun checkLapses() {
        val now = System.currentTimeMillis()
        val warn = ArrayList<String>()
        var lapsed = false
        synchronized(this) {
            for (a in approvals.values.toList()) {
                if (now >= a.lapses) { lapsed = true; if (controller == a.pkg) setControl(IDLE, "", null) }
                else if (!a.warned && now >= a.lapses - WARN_BEFORE_MS) {
                    approvals[a.pkg] = Approval(a.pkg, a.cert, a.since, warned = true)
                    warn += a.pkg
                }
            }
            if (warn.isNotEmpty()) save()
        }
        for (pkg in warn) note(NOTE_LAPSING, pkg, "${label(pkg)} can use this tablet until tomorrow",
            "Open TabDesk → Customize → Agent and tap Keep allowing to give it another week.")
        scheduleCheck()
        if (lapsed || warn.isNotEmpty()) changed("")
    }

    /** An alarm for the next warning or lapse. */
    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        val next = synchronized(this) {
            approvals.values.flatMap { a -> listOfNotNull(if (!a.warned) a.lapses - WARN_BEFORE_MS else null, a.lapses) }
                .filter { it > now }.minOrNull()
        }
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, AgentReceiver::class.java).setAction(AgentReceiver.CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (next == null) am.cancel(pi) else am.set(AlarmManager.RTC, next + 1000, pi)
    }

    // ------------------------------------------------------------ notifications

    private const val CHANNEL_ASK = "agent"
    private const val CHANNEL_USING = "agent_using"
    private const val NOTE_ASKED = "agent_asked"
    private const val NOTE_LAPSING = "agent_lapsing"
    private const val NOTE_USING = 4107

    private fun settingsIntent(requestCode: Int) = PendingIntent.getActivity(app, requestCode,
        HomeActivity.customizeIntent(app, Customize.AGENT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun note(tag: String, pkg: String, title: String, text: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ASK, "Apps asking to use the tablet", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(tag, pkg.hashCode(), Notification.Builder(app, CHANNEL_ASK)
            .setSmallIcon(R.drawable.ic_note_tabdesk)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settingsIntent(1)).setAutoCancel(true)
            .build())
    }

    /** "<app> is using the tablet" with Stop, or Continue once stopped: for when an app hides the taskbar. */
    private fun updateUsingNote() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val (state, who) = synchronized(this) { control to controller }
        if (state == IDLE || who == null) { nm.cancel(NOTE_USING); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL_USING, "An app using the tablet", NotificationManager.IMPORTANCE_LOW))
        val stopped = state == STOPPED || state == PAUSED
        val act = if (stopped) AgentReceiver.CONTINUE else AgentReceiver.STOP
        val pi = PendingIntent.getBroadcast(app, 2, Intent(app, AgentReceiver::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE_USING, Notification.Builder(app, CHANNEL_USING)
            .setSmallIcon(R.drawable.ic_note_tabdesk)
            .setContentTitle(usingText())
            .setContentText(if (stopped) "Stopped. Tap Continue, or send your next message, to let it go on." else "Tap Stop to stop it.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(settingsIntent(3))
            .addAction(Notification.Action.Builder(null, if (stopped) "Continue" else "Stop", pi).build())
            .build())
    }

    /** "DroidACP is using the tablet", or "… is stopped". */
    fun usingText(): String {
        val (state, who) = synchronized(this) { control to controller }
        val name = who?.let { label(it) } ?: "An app"
        return when (state) {
            STOPPED -> "$name is stopped"
            PAUSED -> "$name is paused"
            else -> "$name is using the tablet"
        }
    }

    // ------------------------------------------------------------ helpers

    fun label(pkg: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** The SHA-256 of [pkg]'s current signing certificates, as hex (several are sorted and joined by commas). */
    fun certOf(pm: PackageManager, pkg: String): String? = try {
        val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        si.apkContentsSigners.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
            .sorted().joinToString(",").takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun changed(event: String) {
        stateListeners.forEach { try { it(event) } catch (_: Exception) {} }
        main.post {
            updateUsingNote()
            listeners.toList().forEach { it() }
        }
    }

    private fun load() {
        val o = try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
        enabled = o.optBoolean("enabled")
        nameAll = o.optBoolean("nameAll")
        tabletName = o.optString("tablet").takeIf { it.isNotEmpty() }
        o.optJSONArray("approved")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
            approvals[it.getString("pkg")] = Approval(it.getString("pkg"), it.getString("cert"), it.getLong("since"), it.optBoolean("warned"))
        } }
        o.optJSONArray("asked")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
            asked[it.getString("pkg")] = Asked(it.getString("pkg"), it.getString("cert"), it.getLong("at"), it.optLong("notified"), it.optBoolean("revoked"))
        } }
        o.optJSONArray("allowed")?.let { a -> for (i in 0 until a.length()) allowed += a.getString(i) }
    }

    /** Called with the lock held. Written whole to a new file, then renamed over the old one. */
    private fun save() {
        val o = JSONObject().put("enabled", enabled).put("nameAll", nameAll).put("tablet", tabletName ?: "")
            .put("approved", JSONArray(approvals.values.map {
                JSONObject().put("pkg", it.pkg).put("cert", it.cert).put("since", it.since).put("warned", it.warned) }))
            .put("asked", JSONArray(asked.values.map {
                JSONObject().put("pkg", it.pkg).put("cert", it.cert).put("at", it.at).put("notified", it.notified).put("revoked", it.revoked) }))
            .put("allowed", JSONArray(allowed.toList()))
        try {
            val tmp = File(file.path + ".new")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }
}
