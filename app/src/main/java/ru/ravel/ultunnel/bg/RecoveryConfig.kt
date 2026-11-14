package ru.ravel.ultunnel.bg

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import ru.ravel.ultunnel.utils.MobileProfileConfig

/** Runtime-only additions: downloaded profile files are never rewritten. */
internal object RecoveryConfig {
    const val HEALTH_TAG = "ultunnel-recovery-health"
    data class Prepared(val content: String, val port: Int)

    fun prepare(content: String): Prepared {
        val config = MobileProfileConfig.normalize(JSONObject(content))
        val nodes = objects(config.optJSONArray("outbounds")) + objects(config.optJSONArray("endpoints"))
        val final = config.optJSONObject("route")?.optString("final").orEmpty()
        val primary = nodes.firstOrNull { it.optString("tag") == final && it.optString("type") !in setOf("direct", "block", "dns") }
            ?: nodes.firstOrNull { it.optString("tag") == "proxy" }
            ?: nodes.firstOrNull { it.optString("type") !in setOf("direct", "block", "dns") }
            ?: error("No proxy outbound for health checks")
        var tag = primary.optString("tag")
        if (tag.isBlank()) {
            tag = "ultunnel-recovery-outbound"
            require(nodes.none { it.optString("tag") == tag })
            primary.put("tag", tag)
        }
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray().also { config.put("inbounds", it) }
        require(objects(inbounds).none { it.optString("tag") == HEALTH_TAG })
        val port = Libbox.availablePort(29_000)
        inbounds.put(JSONObject().put("type", "mixed").put("tag", HEALTH_TAG)
            .put("listen", "127.0.0.1").put("listen_port", port))
        val route = config.optJSONObject("route") ?: JSONObject().also { config.put("route", it) }
        val rules = JSONArray().put(JSONObject().put("inbound", JSONArray().put(HEALTH_TAG))
            .put("action", "route").put("outbound", tag))
        append(rules, route.optJSONArray("rules"))
        route.put("rules", rules)
        return Prepared(config.toString(), port)
    }

    fun withCandidates(activeContent: String, checkContent: String): String {
        val active = JSONObject(activeContent)
        val check = JSONObject(checkContent)
        for (key in listOf("inbounds", "outbounds", "endpoints")) {
            val target = active.optJSONArray(key) ?: JSONArray().also { active.put(key, it) }
            val existing = objects(target).map { it.optString("tag") }.toSet()
            require(objects(check.optJSONArray(key)).none { it.optString("tag") in existing }) { "Recovery tag collision" }
            append(target, check.optJSONArray(key))
        }
        val route = active.getJSONObject("route")
        val rules = JSONArray()
        append(rules, check.getJSONObject("route").optJSONArray("rules"))
        append(rules, route.optJSONArray("rules"))
        route.put("rules", rules)
        return active.toString()
    }

    private fun append(target: JSONArray, source: JSONArray?) {
        if (source != null) for (i in 0 until source.length()) target.put(source.get(i))
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
