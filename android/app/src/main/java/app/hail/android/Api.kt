package app.hail.android

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : IOException(message)

/** Blocking HTTP client for the Hail backend. Call from a background thread. */
class Api(private val prefs: Prefs) {

    fun ensureRegistered() {
        if (prefs.deviceToken != null) return
        val body = JSONObject()
            .put("platform", "android")
            .put("capabilities", JSONObject().put("can_check_fares", true).put("can_book", true))
        val res = request("POST", "/v1/devices", body, auth = false)
        prefs.deviceToken = res.getString("token")
    }

    /**
     * Invite-only beta: registers this phone with an invite code. Returns null on success, or a
     * message to show. (The backend only checks codes when REQUIRE_INVITE is on.)
     */
    fun registerWithInvite(code: String?): String? {
        val body = JSONObject()
            .put("platform", "android")
            .put("capabilities", JSONObject().put("can_check_fares", true).put("can_book", true))
        if (!code.isNullOrBlank()) body.put("invite_code", code.trim().uppercase())
        return try {
            prefs.deviceToken = request("POST", "/v1/devices", body, auth = false).getString("token")
            null
        } catch (e: ApiException) {
            when (e.status) {
                403 -> "That code didn't work. Check it, or request an invite."
                422 -> "Codes look like HAIL-ABC123."
                else -> "Couldn't reach Hail (${e.status}). Try again."
            }
        } catch (e: IOException) {
            "Couldn't reach Hail. Check your internet and try again."
        }
    }

    /** "Request an invite": the contact the person typed in, and optionally their city. */
    fun requestInvite(contact: String, city: String?): String? = try {
        val body = JSONObject().put("contact", contact)
        if (!city.isNullOrBlank()) body.put("city", city)
        request("POST", "/v1/invite-requests", body, auth = false)
        null
    } catch (e: ApiException) {
        if (e.status == 429) "Lots of requests today. Try again tomorrow." else "Couldn't send that (${e.status})."
    } catch (e: IOException) {
        "Couldn't reach Hail. Check your internet and try again."
    }

    fun postMessage(sessionId: String, body: JSONObject): JSONObject {
        ensureRegistered()
        return try {
            request("POST", "/v1/sessions/$sessionId/messages", body)
        } catch (e: ApiException) {
            if (e.status == 401) { // token revoked (e.g. data deleted): re-register once
                prefs.deviceToken = null
                ensureRegistered()
                request("POST", "/v1/sessions/$sessionId/messages", body)
            } else throw e
        }
    }

    // Favourite places (saved per device on the backend; removed with Delete my data).
    fun favorites(): org.json.JSONArray {
        ensureRegistered()
        return request("GET", "/v1/favorites", null).getJSONArray("favorites")
    }

    fun addFavorite(label: String, lat: Double?, lng: Double?): JSONObject {
        ensureRegistered()
        val body = JSONObject().put("label", label)
        if (lat != null && lng != null) body.put("lat", lat).put("lng", lng)
        return request("POST", "/v1/favorites", body)
    }

    fun removeFavorite(id: Int) {
        ensureRegistered()
        request("DELETE", "/v1/favorites/$id", null)
    }

    fun readersConfig(): JSONObject = request("GET", "/v1/config/readers", null, auth = false)

    fun deleteMyData() {
        if (prefs.deviceToken == null) return
        request("DELETE", "/v1/me", null)
    }

    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean = true): JSONObject {
        val conn = URL(prefs.backendUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000 // the agent may make several model calls
            conn.setRequestProperty("Accept", "application/json")
            if (auth) prefs.deviceToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(code, "HTTP $code")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
