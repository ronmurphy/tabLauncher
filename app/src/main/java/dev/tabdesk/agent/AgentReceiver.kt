package dev.tabdesk.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stop and Continue on the "is using the tablet" notification, and the alarm for approvals running out. Not exported. */
class AgentReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "dev.tabdesk.agent.STOP"
        const val CONTINUE = "dev.tabdesk.agent.CONTINUE"
        const val CHECK = "dev.tabdesk.agent.CHECK"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Agent.init(ctx)
        when (intent.action) {
            STOP -> AgentService.stopFromTabDesk()
            CONTINUE -> Agent.resume()
            CHECK -> Agent.checkLapses()
        }
    }
}
