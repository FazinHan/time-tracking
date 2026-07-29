package com.fizaan.timetracker.pomodoro

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fizaan.timetracker.MainActivity
import com.fizaan.timetracker.R
import com.fizaan.timetracker.data.Prefs

// A channel's sound can't be changed once created, so silencing it needed a new
// id; the original is deleted on first run.
const val ALERT_CHANNEL_ID = "pomodoro-alert-v2"
private const val LEGACY_ALERT_CHANNEL_ID = "pomodoro-alert"
const val ALERT_NOTIFICATION_ID = 1002

/** Extra on the launch intent naming the phase that just began. */
const val EXTRA_POMODORO_PHASE = "pomodoro_phase"

private const val ALARM_REQUEST = 7301
private const val ACTION_PHASE_END = "com.fizaan.timetracker.POMODORO_PHASE"

/**
 * Wakes the phone at every work/break boundary.
 *
 * The schedule is derived, not counted down, so only ever one alarm is pending:
 * the receiver fires, alerts, and arms the next one. [AlarmManager.setAlarmClock]
 * is what a timer app is supposed to use — it is exempt from Doze, so a break
 * still ends on time with the screen off.
 */
object PomodoroAlarm {

    fun scheduleNext(context: Context) {
        val prefs = Prefs(context)
        val start = prefs.pomodoroStartMs
        if (start <= 0L) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        val at = phaseAt(start, now, prefs.pomodoroSessionSettings).endMs
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, ALARM_REQUEST + 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation(context))
        } catch (_: SecurityException) {
            // Exact alarms refused: fall back to an inexact one rather than none.
            am.set(AlarmManager.RTC_WAKEUP, at, operation(context))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context))
        NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
    }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_REQUEST,
        Intent(context, PomodoroReceiver::class.java).setAction(ACTION_PHASE_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * The boundary alert: announces the phase that has just started and arms the
 * next boundary. Runs even if the app's process was killed in between.
 */
class PomodoroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.pomodoroStartMs <= 0L) return
        val slot = phaseAt(
            prefs.pomodoroStartMs, System.currentTimeMillis(), prefs.pomodoroSessionSettings,
        )
        val pending = goAsync()
        PomodoroAlert.show(context, slot)
        PomodoroAlarm.scheduleNext(context)
        // The tone plays in this process, so the receiver has to outlive it —
        // otherwise a boundary that arrives with the app closed goes quiet.
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 5_000)
    }
}

/**
 * The full-screen "time to work" / "time for a break" alert.
 *
 * It is posted as a high-importance notification carrying a full-screen intent,
 * which is the only sanctioned way to put the app in front of you from the
 * background — over the lock screen included. Where the system declines to
 * honour that, the same notification still arrives as a heads-up with its
 * sound, so the alert is never silently lost.
 */
object PomodoroAlert {

    /** Held only so playback can't be cut short by garbage collection. */
    private var tone: Ringtone? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_ALERT_CHANNEL_ID)
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Pomodoro alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Announces the start of each work and break period."
            setShowBadge(false)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)   // the tone is played by [playTone] instead
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The alert tone.
     *
     * The notification can't be trusted to make the sound: it is cancelled the
     * instant the app takes the screen, which cuts playback off, and a
     * full-screen intent the system honours is expected to alert for itself.
     * So the stock notification tone — short, familiar — is played here, on the
     * alarm stream, where a boundary is still heard with the ringer turned down.
     */
    private fun playTone(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        runCatching {
            tone?.stop()
            tone = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                play()
            }
        }
    }

    fun show(context: Context, slot: PhaseSlot) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(EXTRA_POMODORO_PHASE, slot.kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title(slot.kind))
            .setContentText(subtitle(slot))
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            // A boundary you never saw is of no use an hour later.
            .setTimeoutAfter(10 * 60_000L)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
        playTone(context)
    }

    fun title(kind: Phase): String = when (kind) {
        Phase.WORK -> "Back to work"
        Phase.BREAK -> "Time for a break"
        Phase.LONG_BREAK -> "Time for a long break"
    }

    fun subtitle(slot: PhaseSlot): String {
        val minutes = (slot.endMs - slot.startMs) / 60_000
        return when (slot.kind) {
            Phase.WORK -> "Work period ${slot.ordinal} · $minutes min"
            Phase.BREAK -> "Break ${slot.ordinal} · $minutes min"
            Phase.LONG_BREAK -> "Long break · $minutes min"
        }
    }
}
