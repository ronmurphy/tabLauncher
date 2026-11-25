package dev.tabdesk

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * TabDesk's app icon. A home app has no icon of its own, so straight after installing there'd be nothing to open,
 * and on Android 10 and later the Home button doesn't ask which home app to use. This asks Android to make TabDesk
 * the home app, then goes home. Once TabDesk is the home app, the icon opens Customize instead.
 */
class WelcomeActivity : Activity() {
    companion object {
        const val ACTION_CUSTOMIZE = "dev.tabdesk.action.CUSTOMIZE"
        private const val REQ_HOME = 1
    }

    private val roles by lazy { getSystemService(RoleManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // re-created while Android's question is showing: wait for its answer
        if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            startActivity(Intent(this, HomeActivity::class.java).setAction(ACTION_CUSTOMIZE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        try { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME) }
        catch (_: Exception) { chooseInSettings() }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_HOME) return
        if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } else chooseInSettings()
    }

    /** Android didn't ask (some tablets don't, or it was turned down before): the same choice in Settings. */
    private fun chooseInSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "Tap Home app, then choose TabDesk", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Choose TabDesk as the home app in Settings → Apps → Default apps", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
