package me.woelki.frad.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import me.woelki.frad.MainActivity
import me.woelki.frad.R
import me.woelki.frad.chat.ChatController
import me.woelki.frad.crypto.Identity
import me.woelki.frad.profile.Profile

/**
 * Hosts the single local-BLE [ChatController] instance outside of any Activity/ViewModel
 * lifecycle, so discovery/chat can keep running while FRAD isn't in the foreground - see
 * [Profile.alwaysVisible]. [me.woelki.frad.ui.ChatViewModel] binds to this service to reach the
 * same controller instance the UI reads and drives; it never constructs its own local
 * [BleChatController].
 *
 * Two ways this service is used, both always available regardless of [Profile.alwaysVisible]:
 *  - **Bound only** (`bindService`): kept alive solely by the binding, same lifetime as today's
 *    in-ViewModel controller - dies once the app is closed and every client unbinds. This is
 *    what backs the manual "Become visible"/"Stop being visible" toggle; no notification, since
 *    the toggle state is already visible in the open app's own UI.
 *  - **Started with [ACTION_GO_VISIBLE]** (`startForegroundService`): promotes it to a real
 *    foreground service with a persistent, honest notification - required by Android for any
 *    background work like this, and exactly the point: never a silent background broadcast -
 *    that survives after the UI unbinds. This is what [Profile.alwaysVisible] uses so the app is
 *    actually reachable without being kept open on-screen.
 */
class LocalBleService : Service() {
    inner class LocalBinder : Binder() {
        val controller: ChatController get() = this@LocalBleService.controller
    }

    private val binder = LocalBinder()
    private lateinit var controller: BleChatController

    override fun onCreate() {
        super.onCreate()
        val identity = Identity.loadOrCreate(applicationContext)
        val profile = Profile(applicationContext)
        controller = BleChatController(applicationContext, identity, profile)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GO_VISIBLE) {
            startForeground(NOTIFICATION_ID, buildNotification())
            controller.setBrowsing(true)
        } else if (intent?.action == ACTION_STOP_FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.setBrowsing(false)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "FRAD visibility", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown whenever FRAD is discoverable to people nearby, including in the background."
        }
        manager.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRAD is looking for friends")
            .setContentText("Visible to people nearby - tap to open FRAD")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_GO_VISIBLE = "me.woelki.frad.action.GO_VISIBLE"
        const val ACTION_STOP_FOREGROUND = "me.woelki.frad.action.STOP_FOREGROUND"
        private const val CHANNEL_ID = "frad_visibility"
        private const val NOTIFICATION_ID = 1

        /** Promotes the service to a persistent foreground one and turns local BLE browsing on -
         *  see [Profile.alwaysVisible]. */
        fun startAlwaysVisible(context: Context) {
            val intent = Intent(context, LocalBleService::class.java).setAction(ACTION_GO_VISIBLE)
            context.startForegroundService(intent)
        }

        /** Drops the persistent notification/foreground state without touching current browsing -
         *  the manual Radar-tab toggle remains independent and keeps working either way. */
        fun stopAlwaysVisible(context: Context) {
            val intent = Intent(context, LocalBleService::class.java).setAction(ACTION_STOP_FOREGROUND)
            context.startService(intent)
        }
    }
}
