package com.vera.android.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val BASE_URL = "https://vera-app.hopto.org"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

@Serializable data class LoginRequest(val email: String, val display_name: String)
@Serializable data class LoginResponse(val user_id: String, val session_token: String)
@Serializable data class MemoryItem(val id: String, val kind: String, val text: String, val ts: String, val confidence: Float = 1f)
@Serializable data class MemoryList(val items: List<MemoryItem>)
@Serializable data class Suggestion(val id: String, val type: String, val title: String, val reason: String, val ts: String, val status: String)
@Serializable data class SuggestionList(val items: List<Suggestion>)
@Serializable data class SuggestionAction(val action: String)

class VeraApi(private val http: OkHttpClient = com.vera.android.data.buildHttpClient()) {

    suspend fun login(email: String, displayName: String): LoginResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LoginRequest(email, displayName)).toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url("$BASE_URL/api/auth/login").post(body).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Login failed: ${resp.code}" }
            json.decodeFromString(resp.body!!.string())
        }
    }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/auth/logout")
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun getMemories(token: String): List<MemoryItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE_URL/api/memories").header("X-Session-Token", token).build()
        http.newCall(req).execute().use { resp ->
            json.decodeFromString<MemoryList>(resp.body!!.string()).items
        }
    }

    suspend fun deleteMemory(token: String, id: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/memories/$id")
            .delete()
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun getSuggestions(token: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE_URL/api/suggestions").header("X-Session-Token", token).build()
        http.newCall(req).execute().use { resp ->
            json.decodeFromString<SuggestionList>(resp.body!!.string()).items
        }
    }

    suspend fun patchSuggestion(token: String, id: String, action: String) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SuggestionAction(action)).toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$BASE_URL/api/suggestions/$id")
            .patch(body)
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun approveAction(token: String, actionId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/actions/$actionId/approve")
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun rejectAction(token: String, actionId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/actions/$actionId/reject")
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun uploadWakeSample(token: String, pcm16: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "sample.pcm",
                pcm16.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url("$BASE_URL/api/wake-word/enroll")
            .post(body)
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun getWakeTemplateCount(token: String): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/wake-word/enroll")
            .header("X-Session-Token", token)
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching 0
                val body = resp.body?.string() ?: return@runCatching 0
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<WakeStatus>(body).template_count
            }
        }.getOrDefault(0)
    }

    suspend fun clearWakeEnrollment(token: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/wake-word/enroll")
            .delete()
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    suspend fun submitLearningAnswer(token: String, questionId: String, answer: String) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LearningAnswerRequest(questionId, answer)).toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$BASE_URL/api/learning/answer")
            .post(body)
            .header("X-Session-Token", token)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }
}

@Serializable private data class WakeStatus(val template_count: Int)
@Serializable private data class LearningAnswerRequest(val question_id: String, val answer: String)
