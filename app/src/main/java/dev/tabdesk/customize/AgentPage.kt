package dev.tabdesk.customize

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.tabdesk.*
import dev.tabdesk.agent.Agent
import dev.tabdesk.agent.AgentLog
import dev.tabdesk.agent.Never
import dev.tabdesk.agent.SharedFolder
import java.util.Date
import java.util.Locale

// Customize → Agent (docs/agent-control.md): the master switch, the apps that asked and the approved ones, and what
// the agent may see. Only a person can change these: the agent can never read or use TabDesk's own screens.

internal fun Page.agent() {
    Agent.init(act)
    AgentLog.init(act)

    section("Agent control")
    addNote("An app on this tablet, such as DroidACP, can let an AI agent use the tablet for you. The agent can " +
        "read and open the apps you allow below, tap and type in them if you let it, and see what’s running and how " +
        "busy the tablet is. TabDesk checks " +
        "every request against this page. The agent can never read or use TabDesk itself, Settings, System UI, the " +
        "keyboard, or the app that asked.")
    add(switchRow(act, "Let apps use this tablet", Agent.enabled) { Agent.setEnabled(it) })

    section("Apps that asked")
    // An approved app whose signature changed asks again, but shows under Approved apps, with "Approve this copy".
    val asked = Agent.asked().filter { !it.dismissed && !Agent.isApproved(it.pkg) }
    if (asked.isEmpty()) addNote("None yet. An app asks when you switch on its tablet setting, e.g. in DroidACP.")
    for (a in asked) appRow(a.pkg,
        if (a.revoked) "You took its approval back. Approve it to let it use the tablet again."
        else "Asked ${date(a.at)}. Approving lets it use the tablet for a week; you can renew it here.",
        "Approve" to { Agent.approve(a.pkg) }, "Dismiss" to { Agent.dismiss(a.pkg) })

    section("Approved apps")
    val approved = Agent.approvals()
    if (approved.isEmpty()) addNote("None.")
    // Part 3: the switches that are on show where the user renews, and a signature change says which it switched off.
    val on = Agent.switchesOn().takeIf { it.isNotEmpty() }?.let { " Switched on below: ${it.joinToString()}." } ?: ""
    for (a in approved) {
        val now = System.currentTimeMillis()
        val off = Agent.switchedOffFor(a.pkg).takeIf { it.isNotEmpty() }?.let {
            " When its signature changed, TabDesk switched off: ${it.joinToString()}. Switch them on again below if you still want them."
        } ?: ""
        when {
            Agent.certOf(act.packageManager, a.pkg) != a.cert -> appRow(a.pkg,
                "This copy isn’t signed like the one you approved (its signature changed). Approve it again only if " +
                    "you installed it yourself.$off",
                "Approve this copy" to { Agent.approve(a.pkg) }, "Revoke" to { Agent.revoke(a.pkg) })
            now >= a.lapses -> appRow(a.pkg, "Its week ran out ${date(a.lapses)}. Keep allowing gives it another week.$on$off",
                "Keep allowing" to { Agent.renew(a.pkg) }, "Revoke" to { Agent.revoke(a.pkg) })
            else -> appRow(a.pkg, "Allowed until ${date(a.lapses)}. TabDesk reminds you the day before.$on$off",
                "Keep allowing" to { Agent.renew(a.pkg) }, "Revoke" to { Agent.revoke(a.pkg) })
        }
    }

    allowedApps()

    section("What the agent sees")
    add(switchRow(act, "Let the agent see which apps are running", Agent.nameAll) { Agent.setNameAll(it) })
    addNote("Off: the agent sees how busy the tablet is and how much each app uses, but an app you haven’t " +
        "allowed shows only as “blocked app”. On: it sees every running app’s name.")
    add(switchRow(act, "Let the agent wake the screen", Agent.wake) { Agent.setWake(it) }, llp(MATCH, WRAP).apply { topMargin = act.dp(10) })
    addNote("On: when the screen is off, the agent can switch it on to read an allowed app. It never gets past the " +
        "lock screen: while the tablet is locked, the agent waits for you.")

    section("What the agent may do")
    add(switchRow(act, "Let the agent tap and type", Agent.acting) { Agent.setActing(it) })
    addNote("On: in the apps you allow above, the agent can tap, type, swipe, scroll, and press Back, Enter and Home, " +
        "like a finger would. Never in a password field, and never where another window is over the spot. Touching " +
        "the screen yourself while it works pauses it until your next message or Continue. Off: it can only read.")

    section("Shared folder")
    add(switchRow(act, "Let the agent use the TabDeskAgent folder", Agent.folder) { Agent.setFolder(it) })
    addNote("On: the agent’s app can see the names of the files in the TabDeskAgent folder, copy them to the " +
        "computer the agent runs on, and put new files there for you. Nothing outside that folder. It never deletes or " +
        "replaces a file: a new one with a name that’s taken gets “(1)”. It can’t put apps (APK files) there, and " +
        "nothing it puts there is opened. Put in the folder only what you’re happy for the agent to read.")
    val tree = SharedFolder.tree(act)
    noteWithButton(when {
        tree != null -> "The folder: ${SharedFolder.where(tree)}."
        Agent.folderUri != null -> "TabDesk lost its access to the folder, or it was moved or renamed. Set it up again."
        else -> "Not set up yet. Set up makes Download/TabDeskAgent, then Android asks you to let TabDesk use that folder: " +
            "tap “Use this folder”, then Allow."
    }, act.overlays.button(if (tree != null) "Change" else "Set up", tree == null) { SharedFolder.setUp(act) },
        top = 8)

    section("This tablet")
    noteWithButton("An agent knows this tablet as “${Agent.tablet()}”, so a second tablet can be told apart.",
        act.overlays.button("Rename", false) {
            act.overlays.prompt("Tablet name", Agent.tablet(), android.os.Build.MODEL,
                if (Agent.customTabletName() != null) ({ Agent.setTabletName(null) }) else null) { Agent.setTabletName(it) }
        })

    section("Where it goes")
    addNote("What the agent reads goes to the AI’s company (Anthropic, for Claude) and into the chat’s history on " +
        "the computer the agent runs on. TabDesk keeps only the log below: when, which app, what it asked, the " +
        "answer’s outcome and which switches above were on, never what was read.")

    section("Recent activity")
    val log = AgentLog.recent(30)
    if (log.isEmpty()) addNote("Nothing yet.")
    for (e in log) add(TextView(act).label(pal.textDim, 12.5f).apply {
        text = "${time(e.time)}   ${Agent.label(e.caller)} · ${e.tool}" + (e.front?.let { " · $it" } ?: "") + " · ${e.result}" +
            (e.on?.let { " · on: $it" } ?: "")
        setPadding(act.dp(2), act.dp(3), 0, act.dp(3))
    })
}

/**
 * The apps the agent may read and open (the user's list, none by default), A–Z with a search. Apps on the never list
 * are greyed out, with the reason.
 */
/** The search, kept while the page is rebuilt (it is after each switch, as the agent's settings change). */
private var allowedQuery = ""

private fun Page.allowedApps() {
    section("Apps the agent may use")
    addNote("The agent can read the app in front only if it’s switched on here, and open only these. Switch on " +
        "only apps whose screens you’re happy to have read: what it reads goes to the AI’s company.")
    val never = Never.list(act)
    val me = android.os.Process.myUserHandle()
    val apps = Apps.all.filter { it.user == me }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    val list = linear(act, true)
    fun bind(query: String) {
        allowedQuery = query
        list.removeAllViews()
        val shown = apps.filter { query.isEmpty() || it.label.contains(query, true) }
        if (shown.isEmpty()) list.addView(note(if (apps.isEmpty()) "No apps found." else "No app is called that."))
        for (app in shown) {
            val reason = never[app.pkg]?.let { name ->
                when {
                    app.pkg == act.packageName -> "it’s TabDesk itself"
                    Agent.asked().any { it.pkg == app.pkg } || Agent.isApproved(app.pkg) -> "it asked to use the tablet"
                    else -> "it’s $name"
                }
            }
            val row = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(5), 0, act.dp(5)) }
            row.addView(ImageView(act).apply { setImageBitmap(app.icon) }, llp(act.dp(30), act.dp(30)))
            val text = linear(act, true)
            text.addView(TextView(act).label(pal.text, 14.5f).apply { this.text = app.label })
            if (reason != null) text.addView(note("Can never be allowed: $reason."))
            row.addView(text, llp(0, WRAP, 1f).apply { marginStart = act.dp(12) })
            val sw = switchRow(act, "", reason == null && Agent.isAllowed(app.pkg)) { Agent.setAllowed(app.pkg, it) }
            if (reason != null) { sw.isEnabled = false; (sw as LinearLayout).getChildAt(1).isEnabled = false; row.alpha = 0.5f }
            row.addView(sw, llp(WRAP, WRAP))
            list.addView(row, llp(MATCH, WRAP))
        }
    }
    if (apps.size > 8) add(EditText(act).apply {
        hint = "Search apps"
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = rounded(pal.field, act.dpf(12))
        setTextColor(pal.text); setHintTextColor(pal.textDim); textSize = 15f; typeface = Fonts.regular
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        setText(allowedQuery)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { bind(s.toString().trim()) }
        })
    }, llp(MATCH, WRAP).apply { topMargin = act.dp(8); bottomMargin = act.dp(4) })
    bind(if (apps.size > 8) allowedQuery else "")
    add(list)
}

/** An app's icon and name, a line about it, and its buttons (the first filled). */
private fun Page.appRow(pkg: String, about: String, vararg buttons: Pair<String, () -> Unit>) {
    val row = linear(act, false) { gravity = Gravity.TOP; setPadding(0, act.dp(6), 0, act.dp(8)) }
    row.addView(ImageView(act).apply {
        setImageDrawable(try { act.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { Glyph(G.AGENT, pal.textDim) })
    }, llp(act.dp(32), act.dp(32)))
    val text = linear(act, true)
    text.addView(TextView(act).label(pal.text, 14.5f).apply { this.text = Agent.label(pkg) })
    text.addView(note(about))
    row.addView(text, llp(0, WRAP, 1f).apply { marginStart = act.dp(12) })
    buttons.forEachIndexed { i, (label, onClick) ->
        row.addView(act.overlays.button(label, i == 0, onClick), llp(WRAP, act.dp(38)).apply { marginStart = act.dp(8) })
    }
    add(row)
}

private fun date(ms: Long): String = android.text.format.DateFormat.format(
    android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMMjmm"), Date(ms)).toString()

private fun time(ms: Long): String = android.text.format.DateFormat.format(
    android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMjmmss"), Date(ms)).toString()
