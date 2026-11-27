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
    const val TOOLS = 2
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
    /**
     * An app that asked and isn't approved. [revoked]: the user took its approval back. [dismissed]: the user dismissed
     * it, so it's hidden from "Apps that asked" until it asks again, but it stays on the never list.
     */
    class Asked(val pkg: String, val cert: String, val at: Long, val notified: Long, val revoked: Boolean = false,
        val dismissed: Boolean = false)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val file get() = File(app.noBackupFilesDir, "agent.json")

    @Volatile var enabled = false; private set
    @Volatile var nameAll = false; private set
    /** "Let the agent wake the screen": a read or open_app with the screen off wakes it (never past the lock). */
    @Volatile var wake = false; private set
    /** "Let the agent tap and type": tool list 2's actions in the allowed apps. Off by default. */
    @Volatile var acting = false; private set
    @Volatile private var tabletName: String? = null
    /**
     * The switches TabDesk turned off when an approved app's signature changed (Part 3, "When they go off"), by app:
     * shown on that app's row in Customize → Agent until the user sets one of them again, or revokes the app.
     */
    private val offForCert = LinkedHashMap<String, List<String>>()
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
        // Alarms don't last past a restart of the tablet: set the next one again, and catch up on any that were missed.
        main.post { checkLapses() }
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

    // ------------------------------------------------------------ the switches that widen what the agent may do

    /** Part 3's switches (not the master switch or the allowed apps), by key, with their short names. */
    private val WIDENING = listOf("acting" to "tap and type", "wake" to "wake the screen", "nameAll" to "see running apps")

    private fun isOn(key: String) = when (key) { "acting" -> acting; "wake" -> wake; "nameAll" -> nameAll; else -> false }
    private fun shortName(key: String) = WIDENING.firstOrNull { it.first == key }?.second ?: key

    /** The ones on now, by short name: for the log, the approved apps' rows and the weekly reminder. */
    fun switchesOn(): List<String> = synchronized(this) { WIDENING.filter { isOn(it.first) }.map { it.second } }

    /** The ones TabDesk turned off when [pkg]'s signature changed, by short name, if the user hasn't set them since. */
    fun switchedOffFor(pkg: String): List<String> = synchronized(this) { offForCert[pkg].orEmpty().map { shortName(it) } }

    /** The user set [key] themselves: TabDesk no longer needs to say it turned it off. Called with the lock held. */
    private fun userSet(key: String) {
        for (pkg in offForCert.keys.toList()) {
            val left = offForCert[pkg]!! - key
            if (left.isEmpty()) offForCert.remove(pkg) else offForCert[pkg] = left
        }
    }

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
            offForCert.remove(pkg)
            if (controller == pkg) setControl(IDLE, "", null)
            save()
        }
        changed("revoked")
    }

    /**
     * Hides an app from "Apps that asked" without approving it. It's kept, marked dismissed, so it stays on the never
     * list: the agent can't read its screens, with their Allow and Deny buttons, just because it was dismissed.
     */
    fun dismiss(pkg: String) {
        synchronized(this) {
            val a = asked[pkg] ?: return
            asked[pkg] = Asked(a.pkg, a.cert, a.at, a.notified, a.revoked, dismissed = true)
            save()
        }
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_ASKED, pkg.hashCode())
        changed("")
    }

    fun setAllowed(pkg: String, on: Boolean) {
        synchronized(this) { if (on) allowed += pkg else allowed -= pkg; save() }
        changed("")
    }

    fun setWake(on: Boolean) {
        synchronized(this) { wake = on; userSet("wake"); save() }
        changed("")
    }

    fun setActing(on: Boolean) {
        synchronized(this) { acting = on; userSet("acting"); save() }
        changed("")
    }

    fun setNameAll(on: Boolean) {
        synchronized(this) { nameAll = on; userSet("nameAll"); save() }
        changed("")
    }

    fun setTabletName(name: String?) {
        synchronized(this) { tabletName = name?.trim()?.takeIf { it.isNotEmpty() }; save() }
        changed("")
    }

    // ------------------------------------------------------------ apps asking

    /**
     * A caller that isn't approved connected: note that it asked, and tell the user at most once a day. If it's an
     * approved app whose signature changed, it's a different app now, so the switches that widen what the agent may
     * do go off (Part 3), and its row says which were on.
     */
    fun noteAsked(pkg: String, cert: String) {
        val now = System.currentTimeMillis()
        var turnedOff = emptyList<String>()
        val notify = synchronized(this) {
            val was = approvals[pkg]
            if (was?.cert == cert) return
            if (was != null) {
                turnedOff = WIDENING.map { it.first }.filter { isOn(it) }
                if (turnedOff.isNotEmpty()) {
                    if ("acting" in turnedOff) acting = false
                    if ("wake" in turnedOff) wake = false
                    if ("nameAll" in turnedOff) nameAll = false
                    offForCert[pkg] = (offForCert[pkg].orEmpty() + turnedOff).distinct()
                }
            }
            val before = asked[pkg]
            val due = before == null || now - before.notified >= ASKED_NOTE_EVERY_MS
            // Asking again brings a dismissed app back to "Apps that asked".
            asked[pkg] = Asked(pkg, cert, before?.at ?: now, if (due) now else before!!.notified, before?.revoked == true)
            save()
            due
        }
        if (turnedOff.isNotEmpty()) AgentLog.add(pkg, "signature changed", null,
            "switched off: ${turnedOff.joinToString { shortName(it) }}")
        if (notify) note(NOTE_ASKED, pkg, "${label(pkg)} wants to use this tablet",
            "Open TabDesk → Customize → Agent to approve it, or leave it.")
        changed("")
    }

    // ------------------------------------------------------------ control

    /**
     * A tools/call from [pkg] starts, its checks passed. Control is looked at again here, under the lock, since it
     * may have changed while the checks ran (another app's call began, or the user pressed Stop). Null if the call
     * may go ahead, else the refusal: "busy", "stopped" or "paused". Only a null needs [endCall].
     */
    fun beginCall(pkg: String): String? {
        val was: String
        synchronized(this) {
            controlRefusal(pkg)?.let { return it }
            main.removeCallbacks(goIdle)
            was = control
            running++
            if (control == IDLE) setControl(ACTIVE, "", pkg)
        }
        if (was == IDLE) changed("")
        return null
    }

    /** Why [pkg] can't use the tablet now as far as control goes ("busy", "stopped", "paused"), or null. */
    fun controlRefusal(pkg: String): String? = synchronized(this) {
        val who = controller
        when {
            control == IDLE -> null
            who != null && who != pkg -> "busy"   // another app is using it, or stopped or paused while using it
            control == STOPPED -> "stopped"
            control == PAUSED -> "paused"
            else -> null
        }
    }

    fun endCall() {
        synchronized(this) {
            running = (running - 1).coerceAtLeast(0)
            if (running == 0) { main.removeCallbacks(goIdle); main.postDelayed(goIdle, IDLE_AFTER_MS) }
        }
    }

    /**
     * Stop: from a caller, [pkg] ("caller"), or from TabDesk's indicator or notification (null, "tabdesk"). A caller
     * may stop only itself: when control is idle, or it's the one using the tablet.
     */
    fun stop(pkg: String?, reason: String) {
        synchronized(this) {
            if (pkg != null && controller != null && controller != pkg) return
            if (control == STOPPED) return
            val who = pkg ?: controller ?: return
            main.removeCallbacks(goIdle)
            setControl(STOPPED, reason, who)
        }
        changed("")
    }

    /**
     * The user's next chat message ([pkg], the caller), or Continue on TabDesk (null): stopped or paused control goes
     * back to idle. A caller may resume only itself, not an app another app's user stopped.
     */
    fun resume(pkg: String? = null) {
        synchronized(this) {
            if (control != STOPPED && control != PAUSED) return
            if (pkg != null && controller != pkg) return
            setControl(IDLE, "resumed", null)
        }
        changed("resumed")
    }

    /**
     * A real touch on the screen (the helper's watch, plan T11). While an app is using the tablet with an allowed app
     * in front, control pauses: the running action is dropped, and calls are refused with "paused" until the user's
     * next chat message (the caller's resume()) or Continue.
     */
    fun touched() {
        val front = ScreenTools.frontTask()?.top
        synchronized(this) {
            if (control != ACTIVE || controller == null) return
            if (front == null || !isAllowed(front)) return
            main.removeCallbacks(goIdle)
            setControl(PAUSED, "touched", controller)
        }
        changed("touched")
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
        val on = switchesOn().takeIf { it.isNotEmpty() }?.let { " Switched on for the agent: ${it.joinToString()}." } ?: ""
        for (pkg in warn) note(NOTE_LAPSING, pkg, "${label(pkg)} can use this tablet until tomorrow",
            "Open TabDesk → Customize → Agent and tap Keep allowing to give it another week.$on")
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
        wake = o.optBoolean("wake")
        acting = o.optBoolean("acting")
        tabletName = o.optString("tablet").takeIf { it.isNotEmpty() }
        o.optJSONArray("approved")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
            approvals[it.getString("pkg")] = Approval(it.getString("pkg"), it.getString("cert"), it.getLong("since"), it.optBoolean("warned"))
        } }
        o.optJSONArray("asked")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
            asked[it.getString("pkg")] = Asked(it.getString("pkg"), it.getString("cert"), it.getLong("at"), it.optLong("notified"), it.optBoolean("revoked"),
                it.optBoolean("dismissed"))
        } }
        o.optJSONArray("allowed")?.let { a -> for (i in 0 until a.length()) allowed += a.getString(i) }
        o.optJSONObject("offForCert")?.let { m -> for (pkg in m.keys()) m.optJSONArray(pkg)?.let { a ->
            offForCert[pkg] = (0 until a.length()).map { a.getString(it) }
        } }
    }

    /** Called with the lock held. Written whole to a new file, then renamed over the old one. */
    private fun save() {
        val o = JSONObject().put("enabled", enabled).put("nameAll", nameAll).put("wake", wake).put("acting", acting)
            .put("tablet", tabletName ?: "")
            .put("approved", JSONArray(approvals.values.map {
                JSONObject().put("pkg", it.pkg).put("cert", it.cert).put("since", it.since).put("warned", it.warned) }))
            .put("asked", JSONArray(asked.values.map {
                JSONObject().put("pkg", it.pkg).put("cert", it.cert).put("at", it.at).put("notified", it.notified).put("revoked", it.revoked)
                    .put("dismissed", it.dismissed) }))
            .put("allowed", JSONArray(allowed.toList()))
            .put("offForCert", JSONObject().also { m -> offForCert.forEach { (pkg, keys) -> m.put(pkg, JSONArray(keys)) } })
        try {
            val tmp = File(file.path + ".new")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }
}
