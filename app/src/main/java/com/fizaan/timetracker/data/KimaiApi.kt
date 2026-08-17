package com.fizaan.timetracker.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface KimaiApi {
    @GET("api/version")
    suspend fun version(): VersionInfo

    @GET("api/customers")
    suspend fun customers(): List<Customer>

    @GET("api/projects")
    suspend fun projects(): List<Project>

    @GET("api/activities")
    suspend fun activities(): List<Activity>

    @GET("api/tags")
    suspend fun tags(): List<String>

    @GET("api/timesheets/active")
    suspend fun active(): List<TimesheetActive>

    @GET("api/timesheets/recent")
    suspend fun recent(@retrofit2.http.Query("size") size: Int = 8): List<TimesheetActive>

    @POST("api/timesheets")
    suspend fun createTimesheet(@Body body: TimesheetCreate): CreatedTimesheet

    @PATCH("api/timesheets/{id}/stop")
    suspend fun stop(@Path("id") id: Int): CreatedTimesheet

    @POST("api/activities")
    suspend fun createActivity(@Body body: ActivityCreate): Activity

    /**
     * Kimai attaches only tags it already knows to an entry and drops the rest
     * without saying so, so anything being imported has to exist first.
     */
    @POST("api/tags")
    suspend fun createTag(@Body body: TagCreate): NamedRef

    @GET("api/timesheets")
    suspend fun timesheets(
        @retrofit2.http.Query("begin") begin: String,
        @retrofit2.http.Query("end") end: String,
        @retrofit2.http.Query("size") size: Int = 1000,
    ): List<TimesheetEntry>

    @PATCH("api/timesheets/{id}")
    suspend fun updateTimesheet(@Path("id") id: Int, @Body body: TimesheetUpdate): CreatedTimesheet

    /** Removes an entry for good; the server answers 204 with no body. */
    @DELETE("api/timesheets/{id}")
    suspend fun deleteTimesheet(@Path("id") id: Int)

    @PATCH("api/activities/{id}")
    suspend fun updateActivityColor(@Path("id") id: Int, @Body body: ActivityColorUpdate): Activity

    /** Renames the activity itself — every entry using it follows. */
    @PATCH("api/activities/{id}")
    suspend fun updateActivityName(@Path("id") id: Int, @Body body: ActivityNameUpdate): Activity

    /** The server's configured color palette (name → hex). PATCHed colors must come from it. */
    @GET("api/config/colors")
    suspend fun configColors(): Map<String, String>
}

/**
 * Hands out whatever is playing the part of the server.
 *
 * On a local-only install that is [LocalApi], reading and writing a file on the
 * device; otherwise it is a Retrofit client, whose auth header is injected
 * per-request from [Prefs] so token changes take effect without rebuilding. The
 * client is rebuilt only when the base URL changes.
 */
object ApiProvider {
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cached: KimaiApi? = null
    @Volatile private var local: LocalApi? = null

    fun get(context: Context, prefs: Prefs): KimaiApi {
        if (prefs.serverless) {
            return local ?: LocalApi(LocalStore(context.applicationContext)).also { local = it }
        }
        val base = normalize(prefs.baseUrl)
        val existing = cached
        if (existing != null && cachedUrl == base) return existing
        return build(base, prefs).also {
            cached = it
            cachedUrl = base
        }
    }

    /** Force a fresh client (e.g. after the base URL or the mode changes). */
    fun invalidate() {
        cached = null
        cachedUrl = null
        local = null
    }

    private fun normalize(url: String): String {
        val u = url.trim()
        return if (u.endsWith("/")) u else "$u/"
    }

    private fun build(base: String, prefs: Prefs): KimaiApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // "Unreachable" is a verdict the app has to reach quickly: every screen
        // waits on it, and the offline path behind it is ready to take over. A
        // Kimai on the same network answers a connection in milliseconds, so
        // four seconds is already generous — while the read and call timeouts
        // stay long, because a server that has answered is worth waiting for.
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(prefs))
            .addInterceptor(logging)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KimaiApi::class.java)
    }
}
