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
import java.util.Date
import java.util.Locale

// Customize → Agent (docs/agent-control.md): the master switch, the apps that asked and the approved ones, and what
// the agent may see. Only a person can change these: the agent can never read or use TabDesk's own screens.

internal fun Page.agent() {
    Agent.init(act)
    AgentLog.init(act)

    section("Agent control")
    addNote("An app on this tablet, such as DroidACP, can let an AI agent use the tablet for you. The agent can " +
        "read and open the apps you allow below, and see what’s running and how busy the tablet is. TabDesk checks " +
        "every request against this page. The agent can never read or use TabDesk itself, Settings, System UI, the " +
        "keyboard, or the app that asked.")
    add(switchRow(act, "Let apps use this tablet", Agent.enabled) { Agent.setEnabled(it) })

    section("Apps that asked")
    // An approved app whose signature changed asks again, but shows under Approved apps, with "Approve this copy".
    val asked = Agent.asked().filter { !Agent.isApproved(it.pkg) }
    if (asked.isEmpty()) addNote("None yet. An app asks when you switch on its tablet setting, e.g. in DroidACP.")
    for (a in asked) appRow(a.pkg,
        if (a.revoked) "You took its approval back. Approve it to let it use the tablet again."
        else "Asked ${date(a.at)}. Approving lets it use the tablet for a week; you can renew it here.",
        "Approve" to { Agent.approve(a.pkg) }, "Dismiss" to { Agent.dismiss(a.pkg) })

    section("Approved apps")
    val approved = Agent.approvals()
    if (approved.isEmpty()) addNote("None.")
    for (a in approved) {
        val now = System.currentTimeMillis()
        when {
            Agent.certOf(act.packageManager, a.pkg) != a.cert -> appRow(a.pkg,
                "This copy isn’t signed like the one you approved (its signature changed). Approve it again only if " +
                    "you installed it yourself.",
                "Approve this copy" to { Agent.approve(a.pkg) }, "Revoke" to { Agent.revoke(a.pkg) })
            now >= a.lapses -> appRow(a.pkg, "Its week ran out ${date(a.lapses)}. Keep allowing gives it another week.",
                "Keep allowing" to { Agent.renew(a.pkg) }, "Revoke" to { Agent.revoke(a.pkg) })
            else -> appRow(a.pkg, "Allowed until ${date(a.lapses)}. TabDesk reminds you the day before.",
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

    section("This tablet")
    noteWithButton("An agent knows this tablet as “${Agent.tablet()}”, so a second tablet can be told apart.",
        act.overlays.button("Rename", false) {
            act.overlays.prompt("Tablet name", Agent.tablet(), android.os.Build.MODEL,
                if (Agent.customTabletName() != null) ({ Agent.setTabletName(null) }) else null) { Agent.setTabletName(it) }
        })

    section("Where it goes")
    addNote("What the agent reads goes to the AI’s company (Anthropic, for Claude) and into the chat’s history on " +
        "the computer the agent runs on. TabDesk keeps only the log below: when, which app, what it asked and the " +
        "answer’s outcome, never what was read.")

    section("Recent activity")
    val log = AgentLog.recent(30)
    if (log.isEmpty()) addNote("Nothing yet.")
    for (e in log) add(TextView(act).label(pal.textDim, 12.5f).apply {
        text = "${time(e.time)}   ${Agent.label(e.caller)} · ${e.tool}" + (e.front?.let { " · $it" } ?: "") + " · ${e.result}"
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
