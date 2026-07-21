package com.fizaan.kimaitimer.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OFFSET_NO_COLON = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")
private val LOCAL_NO_ZONE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Parse Kimai's timestamp (e.g. "2026-07-20T15:36:00+0530") to epoch millis. */
fun parseKimaiMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    try {
        return OffsetDateTime.parse(iso, OFFSET_NO_COLON).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    try {
        return OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    return try {
        LocalDateTime.parse(iso, LOCAL_NO_ZONE)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

fun parseKimaiLocal(iso: String?): LocalDateTime? =
    parseKimaiMillis(iso)?.let {
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault())
    }

/** Format a local datetime the way Kimai's API expects it (no zone). */
fun formatKimai(dt: LocalDateTime): String = dt.format(LOCAL_NO_ZONE)

/** Seconds actually spent on an entry; running entries count up to [nowMillis]. */
fun entrySeconds(begin: String?, end: String?, duration: Long?, nowMillis: Long): Long {
    if (end == null) {
        val b = parseKimaiMillis(begin) ?: return 0
        return ((nowMillis - b) / 1000).coerceAtLeast(0)
    }
    duration?.let { if (it > 0) return it }
    val b = parseKimaiMillis(begin) ?: return 0
    val e = parseKimaiMillis(end) ?: return 0
    return ((e - b) / 1000).coerceAtLeast(0)
}

fun entryLocalDate(begin: String?): LocalDate? = parseKimaiLocal(begin)?.toLocalDate()

/** "3h 25m" / "45m" style duration label. */
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        else -> "${m}m"
    }
}
