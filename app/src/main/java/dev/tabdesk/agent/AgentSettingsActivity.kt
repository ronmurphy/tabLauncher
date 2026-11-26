package dev.tabdesk.agent

import android.app.Activity
import android.os.Bundle
import dev.tabdesk.HomeActivity
import dev.tabdesk.customize.Customize

/** dev.tabdesk.action.AGENT_SETTINGS: shows Customize → Agent on TabDesk's home screen. Opening it approves nothing. */
class AgentSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { startActivity(HomeActivity.customizeIntent(this, Customize.AGENT)) } catch (_: Exception) {}
        finish()
    }
}
