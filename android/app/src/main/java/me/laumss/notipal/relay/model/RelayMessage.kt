package me.laumss.notipal.relay.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID


data class RelayMessage(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    
    var closed: Boolean = false,
    
    var pinned: Boolean = false,
    
    var inserted: Boolean = false,
    
    var kind: String = "",
) {
    
    val title: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    
    val preview: String
        get() {
            val lines = text.lines()
            val idx = lines.indexOfFirst { it.isNotBlank() }
            if (idx < 0) return ""
            return lines.drop(idx + 1).joinToString("\n").trim()
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("source", source)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("closed", closed)
        put("pinned", pinned)
        put("inserted", inserted)
        put("kind", kind)
    }

    companion object {
        fun fromJson(obj: JSONObject): RelayMessage = RelayMessage(
            id = obj.optString("id", UUID.randomUUID().toString()),
            text = obj.optString("text", ""),
            source = obj.optString("source", "llm"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            closed = obj.optBoolean("closed", true),
            pinned = obj.optBoolean("pinned", false),
            inserted = obj.optBoolean("inserted", false),
            kind = obj.optString("kind", ""),
        )

        fun listToJson(list: List<RelayMessage>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray): List<RelayMessage> =
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
    }
}
