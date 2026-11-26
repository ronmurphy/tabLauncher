package dev.tabdesk.customize

import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import dev.tabdesk.*
import dev.tabdesk.agent.Agent
import dev.tabdesk.agent.AgentLog
import java.util.Date
import java.util.Locale

// Customize → Agent (docs/agent-control.md): the master switch, the apps that asked and the approved ones, and what
// the agent may see. Only a person can change these: the agent can never read or use TabDesk's own screens.

internal fun Page.agent() {
    Agent.init(act)
    AgentLog.init(act)

    section("Agent control")
    addNote("An app on this tablet, such as DroidACP, can let an AI agent use the tablet for you. For now the " +
        "agent can see what’s running and how busy the tablet is; reading the apps you choose comes later. TabDesk " +
        "checks every request against this page. The agent can never read or use TabDesk itself, Settings, " +
        "System UI, the keyboard, or the app that asked.")
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

    section("What the agent sees")
    add(switchRow(act, "Let the agent see which apps are running", Agent.nameAll) { Agent.setNameAll(it) })
    addNote("Off: the agent sees how busy the tablet is and how much each app uses, but an app you haven’t " +
        "allowed shows only as “blocked app”. On: it sees every running app’s name.")

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
