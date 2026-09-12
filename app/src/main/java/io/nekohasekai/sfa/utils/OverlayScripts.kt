package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OverlayScript(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val source: String,
    val url: String = "",
    val code: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * User-imported sing-box overlay scripts. Stored locally, applied at
 * start time, never written back into the subscription file.
 */
object OverlayScripts {
    const val MAX_SCRIPTS = 12
    const val MAX_CODE_CHARS = 256_000
    const val SAMPLE_ASSET = "scripts/airport-region.js"
    const val SAMPLE_NAME = "机场地区分组（sing-box）"
    const val SOURCE_CODE = "code"
    const val SOURCE_URL = "url"
    const val SOURCE_FILE = "file"
    const val SOURCE_SAMPLE = "sample"

    fun list(): List<OverlayScript> = decode(Settings.overlayScriptsJson)

    fun enabled(): List<OverlayScript> = list().filter { it.enabled && it.code.isNotBlank() }

    fun save(items: List<OverlayScript>) {
        Settings.overlayScriptsJson = encode(items.take(MAX_SCRIPTS))
    }

    fun upsert(script: OverlayScript) {
        val trimmed = script.copy(code = script.code.take(MAX_CODE_CHARS), name = script.name.trim().ifBlank { "脚本" })
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == trimmed.id }
        if (index >= 0) {
            current[index] = trimmed
        } else {
            if (current.size >= MAX_SCRIPTS) {
                throw IllegalStateException("最多保存 $MAX_SCRIPTS 条脚本")
            }
            current.add(trimmed)
        }
        save(current)
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun toggle(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun newId(): String = UUID.randomUUID().toString()

    internal fun encode(items: List<OverlayScript>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("enabled", item.enabled)
                    .put("source", item.source)
                    .put("url", item.url)
                    .put("code", item.code)
                    .put("updatedAt", item.updatedAt),
            )
        }
        return array.toString()
    }

    internal fun decode(raw: String): List<OverlayScript> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val code = obj.optString("code")
                    val id = obj.optString("id").ifBlank { newId() }
                    add(
                        OverlayScript(
                            id = id,
                            name = obj.optString("name").ifBlank { "脚本" },
                            enabled = obj.optBoolean("enabled", false),
                            source = obj.optString("source").ifBlank { SOURCE_CODE },
                            url = obj.optString("url"),
                            code = code,
                            updatedAt = obj.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
