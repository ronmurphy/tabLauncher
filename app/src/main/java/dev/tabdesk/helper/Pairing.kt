package dev.tabdesk.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.tabdesk.R
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.interfaces.RSAPublicKey
import kotlin.concurrent.thread

/**
 * Pairing TabDesk with wireless debugging, on the tablet itself. Settings' "Pair device with pairing code" dialog
 * has to stay on screen while pairing, so the code goes into TabDesk's notification rather than TabDesk's own
 * screen: TabDesk watches for the pairing service the dialog starts, then offers a reply box in the notification.
 */
internal object Pairing {
    private const val TAG = "TabDeskPairing"
    private const val CHANNEL = "setup"
    private const val NOTE = 41
    const val KEY_CODE = "code"
    const val ACTION_CODE = "dev.tabdesk.action.PAIR_CODE"
    const val ACTION_CANCEL = "dev.tabdesk.action.PAIR_CANCEL"
    /** The name shown under Paired devices in Settings. */
    const val KEY_NAME = "TabDesk"
    private const val WATCH_MS = 10 * 60_000L

    /** Waiting for the user to pair. */
    @Volatile var active = false
        private set
    @Volatile private var found: NsdServiceInfo? = null
    @Volatile private var busy = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private val timeout = Runnable { appCtx?.let { stop(it) } }

    /** Opens Settings → Wireless debugging directly (the way its quick-settings tile does when long-pressed),
     *  or Developer options if this Android doesn't support that. */
    fun wirelessDebuggingIntent(): Intent =
        Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES").setPackage("com.android.settings")
            .putExtra(Intent.EXTRA_COMPONENT_NAME,
                ComponentName("com.android.settings", "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"))

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")   // scrolls to and highlights Wireless debugging

    /** Starts waiting for the pairing dialog, with a notification saying what to do. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        stopWatching(app)
        active = true; found = null; busy = false
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pairing TabDesk with wireless debugging"; setSound(null, null)
            })
        post(app, "Pairing TabDesk", "On the Wireless debugging screen, tap “Pair device with pairing code”.", withReply = false)
        watch(app)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, WATCH_MS)
    }

    /** Stops waiting and removes the notification. */
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        stopWatching(app)
        main.removeCallbacks(timeout)
        active = false
        app.getSystemService(NotificationManager::class.java).cancel(NOTE)
        Setup.changed()
    }

    private fun stopWatching(ctx: Context) {
        discovery?.let { d -> try { ctx.getSystemService(NsdManager::class.java).stopServiceDiscovery(d) } catch (_: Exception) {} }
        discovery = null
    }

    /** Watches for the pairing service on this tablet, which exists only while the pairing dialog is open. */
    private fun watch(ctx: Context) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val d = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        if (!isThisTablet(r)) return
                        Log.i(TAG, "pairing service at port ${r.port}")
                        found = r
                        main.post { if (active && !busy) askForCode(ctx, null) }
                    }
                    override fun onResolveFailed(r: NsdServiceInfo, error: Int) { Log.i(TAG, "couldn't resolve ${r.serviceName}: $error") }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceName != found?.serviceName) return
                found = null
                main.post {
                    if (active && !busy) post(ctx, "Pairing TabDesk", "The pairing code closed. Tap “Pair device with pairing code” again.", withReply = false)
                }
            }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "discovery failed: $error") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        discovery = d
        nsd.discoverServices(ADB_PAIRING, NsdManager.PROTOCOL_DNS_SD, d)
    }

    private fun askForCode(ctx: Context, problem: String?) =
        post(ctx, problem ?: "Enter the pairing code",
            if (problem != null) "Check the 6 digits on the screen, then tap Enter code again."
            else "Tap Enter code below, and type the 6 digits shown on the screen.", withReply = true)

    /** The code the user typed into the notification. */
    fun onCode(ctx: Context, typed: String, done: BroadcastReceiver.PendingResult) {
        val app = ctx.applicationContext
        val service = found
        val code = typed.filter { it.isDigit() }
        if (service == null || !active) {
            post(app, "Pairing TabDesk", "The pairing code closed. Tap “Pair device with pairing code” again.", withReply = false)
            done.finish(); return
        }
        if (code.length != 6) { askForCode(app, "That isn't 6 digits"); done.finish(); return }
        busy = true
        post(app, "Pairing…", "Checking the code.", withReply = false)
        thread(name = "pairing") {
            try {
                val (key, cert) = AdbPairing.newKey(KEY_NAME)
                val pub = AdbPairing.adbPublicKey(cert.publicKey as RSAPublicKey, KEY_NAME)
                val hosts = listOf("127.0.0.1") + addresses(service).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                var result: Exception? = null
                for (h in hosts.distinct()) {
                    try { AdbPairing.pair(h, service.port, code, key, cert, pub); result = null; break }
                    catch (e: java.net.ConnectException) { result = e }   // try its Wi-Fi address instead
                }
                result?.let { throw it }
                AdbKey.save(app, key.encoded, cert.encoded)
                Log.i(TAG, "paired")
                main.post {
                    busy = false
                    stopWatching(app); main.removeCallbacks(timeout); active = false
                    post(app, "TabDesk is paired", "You can close Settings now.", withReply = false, ongoing = false)
                    Setup.changed()
                }
                Adb.notePort(app)   // while wireless debugging's announcement is fresh (Android 12 may drop it soon)
            } catch (e: AdbPairing.WrongCode) {
                main.post { busy = false; askForCode(app, "That code didn't match") }
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                main.post { busy = false; askForCode(app, "Couldn't pair (${e.message ?: e.javaClass.simpleName})") }
            } finally {
                done.finish()
            }
        }
    }

    private fun addresses(r: NsdServiceInfo) =
        @Suppress("DEPRECATION") (if (Build.VERSION.SDK_INT >= 34) r.hostAddresses else listOfNotNull(r.host))

    private fun isThisTablet(r: NsdServiceInfo): Boolean {
        val mine = (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
            .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
        return addresses(r).any { it.hostAddress?.substringBefore('%') in mine }
    }

    private fun post(ctx: Context, title: String, text: String, withReply: Boolean, ongoing: Boolean = true) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_note_tabdesk)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing).setOnlyAlertOnce(!withReply)
            .setCategory(Notification.CATEGORY_STATUS)
        if (withReply) {
            val reply = PendingIntent.getBroadcast(ctx, 1,
                Intent(ACTION_CODE).setClass(ctx, PairCodeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // mutable: Android adds the typed text
            b.addAction(Notification.Action.Builder(null, "Enter code", reply)
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()).build())
        }
        if (ongoing) {
            val cancel = PendingIntent.getBroadcast(ctx, 2,
                Intent(ACTION_CANCEL).setClass(ctx, PairCodeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        } else {
            b.setTimeoutAfter(20_000).setAutoCancel(true)
        }
        try { nm.notify(NOTE, b.build()) } catch (e: SecurityException) { Log.w(TAG, "can't post notifications", e) }
    }
}

/** Receives the code typed into the pairing notification, and its Cancel button. */
class PairCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Pairing.ACTION_CODE -> {
                val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Pairing.KEY_CODE)?.toString() ?: return
                Pairing.onCode(ctx, typed, goAsync())
            }
            Pairing.ACTION_CANCEL -> Pairing.stop(ctx)
        }
    }
}
