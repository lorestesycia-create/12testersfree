package com.testersfree.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public project key only. Never place service-role secrets in the APK. */
class SupabaseApi(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val base = "https://fhyspdahxejqloxplzdz.supabase.co"
    private val publicKey = "sb_publishable_7ephAOWUNPSvhc1F283mCQ_kYlJv2DE"

    fun currentUserId(): String? {
        return try {
            val token = prefs.getString("access_token", null) ?: return null
            val part = token.split(".").getOrNull(1) ?: return null
            val decoded = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(decoded).optString("sub").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    fun isSignedIn(): Boolean {
        val token = prefs.getString("access_token", null) ?: return false
        return try {
            val payload = token.split(".").getOrNull(1) ?: return false
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            val exp = JSONObject(decoded).optLong("exp", 0)
            exp > System.currentTimeMillis() / 1000 || !prefs.getString("refresh_token", null).isNullOrBlank()
        } catch (_: Exception) { false }
    }
    fun saveSession(accessToken: String, refreshToken: String) {
        prefs.edit().putString("access_token", accessToken).putString("refresh_token", refreshToken).apply()
    }
    fun signOut() { prefs.edit().clear().apply() }

    fun signUp(email: String, password: String): Boolean {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)

        val response = JSONObject(
            request(
                "/auth/v1/signup",
                "POST",
                payload,
                authenticated = false
            )
        )

        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")

        if (access.isNotBlank() && refresh.isNotBlank()) {
            saveSession(access, refresh)
            return true
        }

        return false
    }

    fun signIn(email: String, password: String) {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)

        val response = JSONObject(
            request(
                "/auth/v1/token?grant_type=password",
                "POST",
                payload,
                authenticated = false
            )
        )

        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")

        if (access.isBlank() || refresh.isBlank()) {
            throw IllegalStateException("Invalid login response")
        }

        saveSession(access, refresh)
    }

    private fun refreshSession(): Boolean {
        val token = prefs.getString("refresh_token", null) ?: return false
        return try {
            val response = JSONObject(request("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", token), authenticated = false))
            saveSession(response.getString("access_token"), response.getString("refresh_token"))
            true
        } catch (_: Exception) { false }
    }

    private fun request(path: String, method: String = "GET", data: JSONObject? = null, authenticated: Boolean = true, retry: Boolean = true): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("apikey", publicKey)
            if (authenticated) {
                val token = prefs.getString("access_token", null)
                    ?: throw IllegalStateException("Sign in required")
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.setRequestProperty("Accept", "application/json")
            if (data != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (conn.responseCode == 401 && authenticated && retry && refreshSession())
                return request(path, method, data, authenticated, retry = false)
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}: $response")
            return response
        } finally { conn.disconnect() }
    }

    fun rpc(name: String, args: JSONObject = JSONObject()): String = request("/rest/v1/rpc/$name", "POST", args)
    fun tests(search: String = ""): JSONArray = JSONArray(rpc("available_tests", JSONObject().put("p_search", search)))
    fun library(): JSONArray = JSONArray(rpc("my_library"))
    fun submissions(): JSONArray = JSONArray(rpc("my_test_submissions"))
    fun receivedFeedback(): JSONArray = JSONArray(rpc("my_received_feedback"))
    fun coinHistory(): JSONArray = JSONArray(rpc("my_coin_history"))
    fun saveFeedback(appId: String, body: String): String = rpc("save_private_feedback", JSONObject().put("p_app_id", appId).put("p_body", body))
    fun profile(): JSONArray = JSONArray(request("/rest/v1/profiles?select=normal_coins,boost_coins,library_slots"))
    fun startTest(listingId: String): String = rpc("start_test", JSONObject().put("p_listing_id", listingId)).trim().trim('"')
    fun submitTest(sessionId: String, seconds: Int): String = rpc("submit_test", JSONObject().put("p_session_id", sessionId).put("p_observed_seconds", seconds))
    fun publish(appId: String): String = rpc("publish_app", JSONObject().put("p_app_id", appId))
    fun boost(listingId: String, boostCoins: Boolean): String = rpc("boost_listing", JSONObject().put("p_listing_id", listingId).put("p_use_boost_coins", boostCoins))
    fun remove(listingId: String): String = rpc("remove_listing", JSONObject().put("p_listing_id", listingId))
    fun deleteApp(appId: String): String = rpc("delete_library_app", JSONObject().put("p_app_id", appId))
    fun unlockSlot(): String = rpc("unlock_library_slot")
    fun requestAccountDeletion(): String = rpc("request_account_deletion")

    fun rewardedAdStatus(): JSONObject {
        val rows = JSONArray(rpc("rewarded_ad_status"))
        return if (rows.length() > 0) rows.getJSONObject(0)
        else JSONObject().put("used", 0).put("remaining", 2)
    }

    fun iconUrl(path: String): String {
        if (path.isBlank() || path == "null") return ""
        return "$base/storage/v1/object/public/app-icons/$path"
    }

    fun uploadIcon(path: String, bytes: ByteArray): String {
        fun upload(retry: Boolean): String {
            val conn = URL("$base/storage/v1/object/app-icons/$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.doOutput = true
                conn.setRequestProperty("apikey", publicKey)
                val token = prefs.getString("access_token", null)
                    ?: throw IllegalStateException("Sign in required")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "image/webp")
                conn.setRequestProperty("x-upsert", "false")
                conn.outputStream.use { it.write(bytes) }

                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (conn.responseCode == 401 && retry && refreshSession())
                    return upload(false)

                if (conn.responseCode !in 200..299)
                    throw IllegalStateException("HTTP ${conn.responseCode}: $response")

                return path
            } finally {
                conn.disconnect()
            }
        }
        return upload(true)
    }

    fun editApp(id: String, name: String, url: String, iconPath: String): String =
        rpc(
            "update_library_app",
            JSONObject()
                .put("p_app_id", id)
                .put("p_name", name)
                .put("p_play_url", url)
                .put("p_icon_path", iconPath)
        )

    fun addApp(packageName: String, name: String, playUrl: String, iconPath: String): String =
        rpc(
            "add_library_app",
            JSONObject()
                .put("p_package", packageName)
                .put("p_name", name)
                .put("p_play_url", playUrl)
                .put("p_icon_path", iconPath)
        )
}
