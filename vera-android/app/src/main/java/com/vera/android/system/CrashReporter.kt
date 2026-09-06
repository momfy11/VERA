package com.vera.android.system

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val BASE_URL = "https://vera-app.hopto.org"
private const val PREF_FILE = "vera_crash"
private const val KEY_STACKTRACE = "pending_stacktrace"
private const val KEY_VERSION = "pending_version"

object CrashReporter {

    fun install(context: Context, appVersion: String) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val default = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                prefs.edit()
                    .putString(KEY_STACKTRACE, throwable.stackTraceToString())
                    .putString(KEY_VERSION, appVersion)
                    .commit()  // commit (sync) — app is dying, must finish before process killed
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    /** Call on app startup — sends any crash saved from previous session. */
    fun flushPending(context: Context, sessionToken: String?) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val stacktrace = prefs.getString(KEY_STACKTRACE, null) ?: return
        val version = prefs.getString(KEY_VERSION, "") ?: ""

        // Clear immediately so we don't retry on next launch if send fails permanently
        prefs.edit().remove(KEY_STACKTRACE).remove(KEY_VERSION).apply()

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val body = JSONObject().apply {
                    put("stacktrace", stacktrace)
                    put("app_version", version)
                    put("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                }.toString().toRequestBody("application/json".toMediaType())

                val reqBuilder = Request.Builder()
                    .url("$BASE_URL/api/crashes")
                    .post(body)
                if (sessionToken != null) reqBuilder.header("X-Session-Token", sessionToken)

                OkHttpClient().newCall(reqBuilder.build()).execute().close()
            }
        }
    }
}
