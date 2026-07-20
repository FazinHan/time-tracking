package com.fizaan.kimaitimer.data

import android.content.Context

/** Simple persisted settings for the single-user personal tracker. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kimai_timer", Context.MODE_PRIVATE)

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

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank() && projectId >= 0

    // ---- Per-activity tag memory ----
    //
    // The comma-separated tag string chosen for a given activity, stored on the
    // device so every future start of that activity rides with the same tag(s).
    // Returns null when the activity has never been tagged (→ prompt the user);
    // returns "" when the user deliberately chose no tag (→ never reprompt).

    fun tagFor(activityId: Int): String? = sp.getString("tag_$activityId", null)

    fun setTag(activityId: Int, tags: String) =
        sp.edit().putString("tag_$activityId", tags).apply()
}
