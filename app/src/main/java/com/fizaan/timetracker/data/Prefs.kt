package com.fizaan.timetracker.data

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.fizaan.timetracker.DefaultIconVariant
import com.fizaan.timetracker.IconVariant
import com.fizaan.timetracker.pomodoro.PomodoroSettings
import com.fizaan.timetracker.pomodoro.PomodoroSkips
import com.fizaan.timetracker.ui.DefaultAccent

/** "No pomodoro entry" — a value no id, server-issued or queued, can take. */
const val NO_ENTRY = Int.MIN_VALUE

/** Simple persisted settings for the single-user personal tracker. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("time_tracker", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "") ?: ""
        set(v) = sp.edit().putString("base_url", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    /** "bearer" (Kimai 2.x API token) or "legacy" (X-AUTH headers). */
    var authMode: String
        get() = sp.getString("auth_mode", "bearer") ?: "bearer"
        set(v) = sp.edit().putString("auth_mode", v).apply()

    /** Only used when authMode == "legacy". */
    var legacyUser: String
        get() = sp.getString("legacy_user", "") ?: ""
        set(v) = sp.edit().putString("legacy_user", v).apply()

    var projectId: Int
        get() = sp.getInt("project_id", -1)
        set(v) = sp.edit().putInt("project_id", v).apply()

    var projectName: String
        get() = sp.getString("project_name", "") ?: ""
        set(v) = sp.edit().putString("project_name", v).apply()

    var customerName: String
        get() = sp.getString("customer_name", "") ?: ""
        set(v) = sp.edit().putString("customer_name", v).apply()

    /**
     * Local-only mode: the app keeps everything on the device and never
     * contacts a server. Set at setup and switchable there afterwards.
     */
    var serverless: Boolean
        get() = sp.getBoolean("serverless", false)
        set(v) = sp.edit().putBoolean("serverless", v).apply()

    val isConfigured: Boolean
        get() = if (serverless) projectId >= 0
        else baseUrl.isNotBlank() && token.isNotBlank() && projectId >= 0

    /** Packed ARGB of the accent every themed element is drawn from. */
    var accentColor: Int
        get() = sp.getInt("accent_color", DefaultAccent.toArgb())
        set(v) = sp.edit().putInt("accent_color", v).apply()

    /** Which launcher-icon alias is currently the enabled one. */
    var iconVariant: IconVariant
        get() = sp.getString("icon_variant", null)
            ?.let { name -> IconVariant.entries.firstOrNull { it.name == name } }
            ?: DefaultIconVariant
        set(v) = sp.edit().putString("icon_variant", v.name).apply()

    // ---- Per-activity tag memory ----
    //
    // The comma-separated tag string chosen for a given activity, stored on the
    // device so every future start of that activity rides with the same tag(s).
    // Returns null when the activity has never been tagged (→ prompt the user);
    // returns "" when the user deliberately chose no tag (→ never reprompt).

    fun tagFor(activityId: Int): String? = sp.getString("tag_$activityId", null)

    fun setTag(activityId: Int, tags: String) =
        sp.edit().putString("tag_$activityId", tags).apply()

    // ---- Pomodoro ----
    //
    // The lengths, plus enough about a session in flight for an alarm receiver
    // (or a restarted process) to work out where the session stands without the
    // app having been running in between.

    var pomodoroSettings: PomodoroSettings
        get() = PomodoroSettings(
            workMinutes = sp.getInt("pomo_work", 25),
            breakMinutes = sp.getInt("pomo_break", 5),
            longBreakMinutes = sp.getInt("pomo_long", 15),
            breaksBeforeLong = sp.getInt("pomo_cycle", 3),
        )
        set(v) {
            val s = v.sane()
            sp.edit()
                .putInt("pomo_work", s.workMinutes)
                .putInt("pomo_break", s.breakMinutes)
                .putInt("pomo_long", s.longBreakMinutes)
                .putInt("pomo_cycle", s.breaksBeforeLong)
                .apply()
        }

    /**
     * The lengths a running session started with. The phase is arithmetic on
     * the start instant, so editing the lengths mid-session would re-slice the
     * periods already taken; freezing them here keeps a session the shape it
     * began as and lets an edit apply to the next one. Falls back to the
     * current settings when nothing is running.
     */
    var pomodoroSessionSettings: PomodoroSettings
        get() = if (!sp.contains("pomo_run_work")) pomodoroSettings else PomodoroSettings(
            workMinutes = sp.getInt("pomo_run_work", 25),
            breakMinutes = sp.getInt("pomo_run_break", 5),
            longBreakMinutes = sp.getInt("pomo_run_long", 15),
            breaksBeforeLong = sp.getInt("pomo_run_cycle", 3),
        )
        set(v) {
            val s = v.sane()
            sp.edit()
                .putInt("pomo_run_work", s.workMinutes)
                .putInt("pomo_run_break", s.breakMinutes)
                .putInt("pomo_run_long", s.longBreakMinutes)
                .putInt("pomo_run_cycle", s.breaksBeforeLong)
                .apply()
        }

    /** Epoch millis the running session began, or 0 when none is. */
    var pomodoroStartMs: Long
        get() = sp.getLong("pomo_start", 0L)
        set(v) = sp.edit().putLong("pomo_start", v).apply()

    /**
     * The entry the session is being tracked against. Negative when it was
     * started offline and is still sitting in the queue, so "none" needs a
     * sentinel of its own rather than the old -1.
     */
    var pomodoroEntryId: Int
        get() = sp.getInt("pomo_entry", NO_ENTRY).let { if (it == -1) NO_ENTRY else it }
        set(v) = sp.edit().putInt("pomo_entry", v).apply()

    var pomodoroActivityId: Int
        get() = sp.getInt("pomo_activity", -1)
        set(v) = sp.edit().putInt("pomo_activity", v).apply()

    var pomodoroActivityName: String
        get() = sp.getString("pomo_activity_name", "") ?: ""
        set(v) = sp.edit().putString("pomo_activity_name", v).apply()

    /**
     * How far the schedule has been pushed ahead of the clock by skips. The
     * phase is read at `pomodoroTimelineOrigin`, so skipping is arithmetic on
     * one number and survives the process being killed like everything else.
     */
    var pomodoroSkew: Long
        get() = sp.getLong("pomo_skew", 0L)
        set(v) = sp.edit().putLong("pomo_skew", v).apply()

    /** The instant the *schedule* runs from — the start, less everything skipped. */
    val pomodoroTimelineOrigin: Long
        get() = pomodoroStartMs - pomodoroSkew

    /** Period index → time skipped off it, as "index:millis" pairs. */
    var pomodoroSkips: PomodoroSkips
        get() = (sp.getString("pomo_skips", "") ?: "")
            .split(',')
            .mapNotNull { pair ->
                val (i, ms) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val index = i.toIntOrNull() ?: return@mapNotNull null
                val millis = ms.toLongOrNull() ?: return@mapNotNull null
                index to millis
            }
            .toMap()
        set(v) = sp.edit()
            .putString("pomo_skips", v.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()

    /** The entry's begin exactly as sent to Kimai; PATCHes have to echo it. */
    var pomodoroBeginIso: String
        get() = sp.getString("pomo_begin_iso", "") ?: ""
        set(v) = sp.edit().putString("pomo_begin_iso", v).apply()

    fun clearPomodoroSession() = sp.edit()
        .remove("pomo_start").remove("pomo_entry").remove("pomo_activity")
        .remove("pomo_activity_name").remove("pomo_begin_iso")
        .remove("pomo_skew").remove("pomo_skips")
        .remove("pomo_run_work").remove("pomo_run_break")
        .remove("pomo_run_long").remove("pomo_run_cycle")
        .apply()
}
