package app.frad.chat.ble

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import app.frad.chat.MainActivity
import app.frad.chat.R
import app.frad.chat.chat.ChatController
import app.frad.chat.chat.ChatUiState
import app.frad.chat.crypto.Identity
import app.frad.chat.profile.Profile

/**
 * Hosts the single local-BLE [ChatController] instance outside of any Activity/ViewModel
 * lifecycle, so discovery/chat can keep running while FRAD isn't in the foreground - see
 * [Profile.alwaysVisible]. [app.frad.chat.ui.ChatViewModel] binds to this service to reach the
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
 *    actually reachable without being kept open on-screen. The notification follows the
 *    controller's state and has a one-tap "Turn off".
 */
class LocalBleService : Service() {
    inner class LocalBinder : Binder() {
        val controller: ChatController get() = this@LocalBleService.controller
    }

    private val binder = LocalBinder()
    private lateinit var controller: BleChatController
    private lateinit var profile: Profile
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationUpdates: Job? = null

    override fun onCreate() {
        super.onCreate()
        val identity = Identity.loadOrCreate(applicationContext)
        profile = Profile(applicationContext)
        controller = BleChatController(applicationContext, identity, profile)
        controller.onVisibilityExpired = {
            // The user limited how long they want to be visible - that ends "always visible" too.
            serviceScope.launch {
                profile.alwaysVisible = false
                leaveForeground()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GO_VISIBLE -> {
                startForeground(NOTIFICATION_ID, buildNotification(controller.state.value))
                controller.setBrowsing(true)
                followStateInNotification()
            }
            ACTION_STOP_FOREGROUND -> leaveForeground()
            ACTION_TURN_OFF -> {
                // The notification's "Turn off": same as switching "always visible" off in Profile.
                profile.alwaysVisible = false
                controller.setBrowsing(false)
                leaveForeground()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        controller.close()
        super.onDestroy()
    }

    private fun leaveForeground() {
        notificationUpdates?.cancel()
        notificationUpdates = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun followStateInNotification() {
        if (notificationUpdates != null) return
        val manager = getSystemService(NotificationManager::class.java)
        notificationUpdates = serviceScope.launch {
            controller.state.collect { state -> manager.notify(NOTIFICATION_ID, buildNotification(state)) }
        }
    }

    private fun buildNotification(state: ChatUiState): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "FRAD visibility", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown whenever FRAD is discoverable to people nearby, including in the background."
        }
        manager.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            FLAG_IMMEDIATE_OR_UPDATE,
        )
        val turnOff = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalBleService::class.java).setAction(ACTION_TURN_OFF),
            FLAG_IMMEDIATE_OR_UPDATE,
        )
        val (title, text) = when (state) {
            is ChatUiState.Paused -> "FRAD is paused" to "Bluetooth is off - continues once it's back on"
            is ChatUiState.Connecting, ChatUiState.Handshaking -> "FRAD is connecting" to "Someone nearby is starting a chat - tap to open"
            is ChatUiState.Chatting -> "FRAD: chatting" to "You're in a chat - tap to open FRAD"
            else -> "FRAD is looking for friends" to "Visible to people nearby - tap to open FRAD"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, "Turn off", turnOff)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_GO_VISIBLE = "app.frad.chat.action.GO_VISIBLE"
        const val ACTION_STOP_FOREGROUND = "app.frad.chat.action.STOP_FOREGROUND"
        private const val ACTION_TURN_OFF = "app.frad.chat.action.TURN_OFF"
        private const val CHANNEL_ID = "frad_visibility"
        private const val NOTIFICATION_ID = 1

        /** Immutable, and refreshed in place when the notification is rebuilt. */
        private const val FLAG_IMMEDIATE_OR_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

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
