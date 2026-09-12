package me.laumss.notipal.relay.net

import android.util.Log
import org.json.JSONObject


object HttpJson {
    private const val TAG = "HttpJson"

    
    @Volatile var authToken: String? = null

    
    fun sseUrl(url: String): String {
        val token = authToken ?: return url
        val sep = if ("?" in url) "&" else "?"
        return "$url${sep}access_token=$token"
    }

    
    fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = authToken ?: return extra
        return extra + ("Authorization" to "Bearer $token")
    }

    
    fun normalizeBaseUrl(host: String, defaultPort: Int): String {
        val h = host.trim()
        return if (h.startsWith("http")) h else "http://$h:$defaultPort"
    }

    
    fun get(url: String, timeoutMs: Int = 5_000): String? =
        RawHttp.request(
            method = "GET",
            url = url,
            headers = authHeaders(),
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
        ).use { resp ->
            if (resp.isSuccess) {
                resp.body.readBytes().toString(Charsets.UTF_8)
            } else {
                Log.w(TAG, "GET $url → ${resp.status}")
                null
            }
        }

    
    fun getJsonOrNull(url: String, timeoutMs: Int = 5_000): JSONObject? =
        try {
            get(url, timeoutMs)?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }

    
    fun delete(url: String, timeoutMs: Int = 5_000): Boolean =
        try {
            RawHttp.request(
                method = "DELETE",
                url = url,
                headers = authHeaders(),
                connectTimeoutMs = timeoutMs,
                readTimeoutMs = timeoutMs,
            ).use { it.isSuccess }
        } catch (e: Exception) {
            Log.w(TAG, "DELETE $url failed: ${e.message}")
            false
        }

    
    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 30_000): String =
        post(
            url = url,
            body = body.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = timeoutMs,
        )

    
    fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        timeoutMs: Int = 30_000,
    ): String =
        RawHttp.request(
            method = "POST",
            url = url,
            headers = authHeaders(mapOf("Content-Type" to contentType)),
            body = body,
            
            
            connectTimeoutMs = minOf(timeoutMs, 10_000),
            readTimeoutMs = timeoutMs,
        ).use { resp ->
            val text = resp.body.readBytes().toString(Charsets.UTF_8)
            if (!resp.isSuccess) {
                Log.w(TAG, "POST $url → ${resp.status}: ${text.take(200)}")
            }
            text
        }
}
