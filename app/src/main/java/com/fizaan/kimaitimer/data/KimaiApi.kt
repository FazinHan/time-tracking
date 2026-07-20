package com.fizaan.kimaitimer.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
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
}

/**
 * Builds (and caches) a Retrofit client. The auth header is injected per-request
 * from [Prefs], so token changes take effect without rebuilding. The client is
 * rebuilt only when the base URL changes.
 */
object ApiProvider {
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cached: KimaiApi? = null

    fun get(prefs: Prefs): KimaiApi {
        val base = normalize(prefs.baseUrl)
        val existing = cached
        if (existing != null && cachedUrl == base) return existing
        return build(base, prefs).also {
            cached = it
            cachedUrl = base
        }
    }

    /** Force a fresh client (e.g. after the base URL is reconfigured). */
    fun invalidate() {
        cached = null
        cachedUrl = null
    }

    private fun normalize(url: String): String {
        val u = url.trim()
        return if (u.endsWith("/")) u else "$u/"
    }

    private fun build(base: String, prefs: Prefs): KimaiApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(prefs))
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
