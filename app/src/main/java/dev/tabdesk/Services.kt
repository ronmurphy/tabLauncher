package dev.tabdesk

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.app.Notification
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional helper the user switches on in Accessibility settings so a double-tap on the desktop can
 * lock the screen like the power button (fingerprint / face unlock keep working). It asks for no
 * events and can't read window content; locking is the only thing it does.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile private var instance: LockService? = null
        val enabled get() = instance != null
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}

/** Feeds [Badges] with which packages currently have dismissible notifications. */
class DotsService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() = refresh()
    override fun onListenerDisconnected() { main.post { Badges.set(emptyMap()) } }
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    private fun refresh() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        val ranking = try { currentRanking } catch (e: Exception) { null }
        val r = Ranking()
        val m = HashMap<String, Int>()
        for (sbn in active) {
            val n = sbn.notification
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (ranking != null && ranking.getRanking(sbn.key, r) && !r.canShowBadge()) continue
            m[sbn.packageName] = (m[sbn.packageName] ?: 0) + 1
        }
        main.post { Badges.set(m) }
    }
}

/** Handles "Add to Home screen" requests: the requesting app already confirmed with the user. */
class PinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val la = getSystemService(LauncherApps::class.java)
        val req = la.getPinItemRequest(intent)
        if (req != null && req.isValid && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            val si = req.shortcutInfo
            if (si != null && req.accept()) {
                val items = Prefs.loadDesktop()
                val key = "${si.`package`}/${si.id}"
                if (items.none { it.type == DesktopItem.SHORTCUT && it.key == key }) {
                    items += DesktopItem(DesktopItem.newId(), DesktopItem.SHORTCUT, key, label = si.shortLabel?.toString())
                    Prefs.saveDesktop(items)
                    Prefs.desktopRev = Prefs.desktopRev + 1
                }
            }
        }
        finish()
    }
}
