package ru.ravel.ultunnel.utils

import org.json.JSONArray
import org.json.JSONObject

object MobileProfileConfig {
    private val amneziaKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "header_protection_key", "content_padding_addition", "rekey_after_time",
        "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts", "random_trailers", "disable_cookies")

    fun normalize(config: JSONObject): JSONObject {
        config.optJSONArray("outbounds").objects().forEach { outbound ->
            outbound.optJSONObject("transport")?.let { transport ->
                if (transport.optString("type") == "grpc" && !transport.has("service_name") &&
                    (transport.has("path") || transport.has("mode"))) transport.put("type", "xhttp")
                if (transport.optString("type") != "xhttp") transport.remove("mode")
            }
        }
        return config
    }

    fun protocol(config: JSONObject): String {
        val nodes = config.optJSONArray("endpoints").objects() + config.optJSONArray("outbounds").objects()
        val primary = nodes.firstOrNull { it.optString("tag") == "proxy" }
            ?: nodes.firstOrNull { it.optString("tag") == config.optJSONObject("route")?.optString("final") }
            ?: nodes.firstOrNull { it.optString("type") !in setOf("direct", "block", "dns") }
            ?: return "unknown"
        val type = primary.optString("type", "unknown")
        if (type == "wireguard" && amneziaKeys.any { key ->
            val value = primary.opt(key)
            value != null && value != JSONObject.NULL && value != false && value != "" &&
                !(value is Number && value.toDouble() == 0.0)
        }) return "amnezia_wg"
        if (type == "shadowsocks" && nodes.any { it.optString("tag") == primary.optString("detour") &&
                it.optString("type") == "shadowtls" }) return "shadowtls"
        return type
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
