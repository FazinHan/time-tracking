package com.fizaan.kimaitimer.data

import com.squareup.moshi.Json

// Reflection-based Moshi (KotlinJsonAdapterFactory) — no codegen/KSP needed.
// Unknown JSON fields are ignored by default.

data class Customer(
    val id: Int,
    val name: String,
    val visible: Boolean = true,
)

data class Project(
    val id: Int,
    val name: String,
    val customer: Int? = null,
    val parentTitle: String? = null,
    val visible: Boolean = true,
)

data class Activity(
    val id: Int,
    val name: String,
    val project: Int? = null,
    val visible: Boolean = true,
    val color: String? = null,
)

/** A nested {id, name} reference as returned by the "expanded" collections. */
data class NamedRef(
    val id: Int,
    val name: String? = null,
)

/**
 * Expanded timesheet record — used for both GET /api/timesheets/active and
 * GET /api/timesheets/recent (same shape: activity/project as {id,name} objects).
 */
data class TimesheetActive(
    val id: Int,
    val begin: String? = null,
    val end: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val activity: NamedRef? = null,
    val project: NamedRef? = null,
)

/** Minimal view of a created/stopped timesheet — only the id is needed. */
data class CreatedTimesheet(
    val id: Int,
)

data class VersionInfo(
    val version: String? = null,
    @Json(name = "versionId") val versionId: Long? = null,
)

/**
 * Compact timesheet record as returned by GET /api/timesheets (range queries):
 * activity/project are plain ids here, unlike the expanded active/recent shape.
 */
data class TimesheetEntry(
    val id: Int,
    val begin: String,
    val end: String? = null,
    val duration: Long? = null,   // seconds; 0 while running
    val description: String? = null,
    val tags: List<String>? = null,
    val activity: Int,
    val project: Int? = null,
)

// ---- Request bodies ----

data class TimesheetCreate(
    val begin: String,
    val project: Int,
    val activity: Int,
    val description: String? = null,
    val tags: String? = null,
)

data class ActivityCreate(
    val name: String,
    val project: Int? = null,
    val visible: Boolean = true,
)

/** Moshi omits nulls, so a null end leaves the server value untouched. */
data class TimesheetUpdate(
    val begin: String,
    val end: String? = null,
    val description: String? = null,
    val tags: String? = null,
)

data class ActivityColorUpdate(
    val color: String,
)
