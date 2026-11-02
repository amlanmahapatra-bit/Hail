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
