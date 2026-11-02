package app.hail.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service = the visible "Hail is working" indicator with a Stop button.
 * It runs only while a user-started request is in progress (or while the user has
 * debug dump mode switched on).
 */
class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AgentState.stopRequested = true
                AgentState.end()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DUMP -> {
                dumpNow()
                return START_NOT_STICKY
            }
        }
        val dumpMode = intent?.action == ACTION_DUMP_MODE
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Comparing rides…"
        val n = build(text, dumpMode)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    private fun dumpNow() {
        val svc = HailAccessibilityService.instance
        if (svc == null) {
            update("Accessibility service is off. Enable it first.", dumpMode = true)
            return
        }
        svc.dismissShade()
        AgentState.begin(null)
        Handler(Looper.getMainLooper()).postDelayed({
            val msg = try {
                val dump = svc.dumpRideAppWindow()
                if (dump == null) "No Uber/Ola/Rapido screen found. Open the app, then tap Dump."
                else {
                    val app = RideApps.packages.entries
                        .firstOrNull { it.value == dump.optString("package") }?.key ?: "app"
                    "Saved ${Dumps.save(this, app, dump.toString(1))}"
                }
            } catch (e: Exception) {
                "Dump failed: ${e.javaClass.simpleName}"
            } finally {
                AgentState.end()
            }
            update(msg, dumpMode = true)
        }, 1500)
    }

    private fun update(text: String, dumpMode: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text, dumpMode))
    }

    private fun build(text: String, dumpMode: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hail at work", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(if (dumpMode) "Hail dump mode" else "Hail is working")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (dumpMode) {
            val dump = PendingIntent.getService(
                this, 3, Intent(this, AgentService::class.java).setAction(ACTION_DUMP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, "Dump screen", dump).build())
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "agent"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "app.hail.STOP"
        const val ACTION_DUMP_MODE = "app.hail.DUMP_MODE"
        const val ACTION_DUMP = "app.hail.DUMP"
        const val EXTRA_TEXT = "text"

        fun show(ctx: Context, text: String) {
            ctx.startForegroundService(
                Intent(ctx, AgentService::class.java).putExtra(EXTRA_TEXT, text)
            )
        }

        fun startDumpMode(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, AgentService::class.java).setAction(ACTION_DUMP_MODE)
                    .putExtra(EXTRA_TEXT, "Open Uber, Ola or Rapido, then tap Dump screen.")
            )
        }

        fun hide(ctx: Context) {
            ctx.stopService(Intent(ctx, AgentService::class.java))
        }
    }
}
