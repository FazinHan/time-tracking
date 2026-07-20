package com.fizaan.kimaitimer.data

import okhttp3.Interceptor
import okhttp3.Response

/** Adds Kimai auth headers, supporting both 2.x Bearer tokens and legacy X-AUTH. */
class AuthInterceptor(private val prefs: Prefs) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("Accept", "application/json")
        if (prefs.authMode == "legacy") {
            builder.header("X-AUTH-USER", prefs.legacyUser)
            builder.header("X-AUTH-TOKEN", prefs.token)
        } else {
            builder.header("Authorization", "Bearer ${prefs.token}")
        }
        return chain.proceed(builder.build())
    }
}
