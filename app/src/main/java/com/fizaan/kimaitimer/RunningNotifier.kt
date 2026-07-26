package com.fizaan.kimaitimer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fizaan.kimaitimer.data.TimesheetActive
import com.fizaan.kimaitimer.util.parseKimaiMillis

private const val CHANNEL_ID = "running-timer"
private const val NOTIFICATION_ID = 1001

/**
 * The lock-screen face of a running timer.
 *
 * Android dropped true lock-screen widgets for phones (they came back in 14 for
 * tablets only), so the thing that actually appears on the lock screen — and is
 * absent entirely when nothing is running — is an ongoing notification. It is
 * posted when a timer starts and cancelled when the last one stops, so there is
 * nothing to see while idle.
 *
 * The elapsed time is drawn by the system from the entry's begin timestamp
 * ([NotificationCompat.Builder.setUsesChronometer]), so it keeps ticking with
 * no help from the app — even after the process is killed.
 */
class RunningNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Running timer",
                NotificationManager.IMPORTANCE_LOW,   // no sound, no heads-up
            ).apply {
                description = "Shows the activity you're currently tracking."
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Mirror the current timers onto the lock screen. [primary] is the entry
     * that owns the headline and the clock; [second], when a second activity is
     * running, contributes its name only — no timer of its own.
     */
    fun sync(primary: TimesheetActive?, second: TimesheetActive?) {
        if (primary == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val name = primary.activity?.name ?: "Tracking"
        val tags = primary.tags?.filter { it.isNotBlank() }.orEmpty().joinToString(" · ")
        val secondName = second?.activity?.name

        // Tags on the first line; the second activity, if any, in the smaller
        // line under it — name only, as asked.
        val lines = listOfNotNull(
            tags.ifBlank { null },
            secondName?.let { "+ $it" },
        )

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(name)
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)            // not swipeable while the timer runs
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Without a parseable begin there is nothing to count from; fall back to
        // a plain notification rather than showing a wrong elapsed time.
        parseKimaiMillis(primary.begin)?.let {
            builder.setWhen(it).setUsesChronometer(true)
        } ?: builder.setShowWhen(false)

        secondName?.let { builder.setSubText("2 running") }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission denied — the app works fine without it.
        }
    }

    fun clear() = manager.cancel(NOTIFICATION_ID)
}
