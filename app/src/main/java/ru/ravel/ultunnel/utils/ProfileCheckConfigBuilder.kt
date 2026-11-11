package ru.ravel.ultunnel.utils

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import ru.ravel.ultunnel.database.Profile
import java.io.File

/**
 * Собирает один общий sing-box конфиг для параллельной проверки всех профилей.
 *
 * Для каждого профиля берутся его outbounds/endpoints (теги получают уникальный префикс),
 * поднимается отдельный mixed-инбаунд на 127.0.0.1:<port>, и route-правило направляет
 * трафик этого инбаунда в основной outbound профиля (route.final или первый outbound).
 * TUN не создаётся — проверка идёт через локальные прокси.
 */
object ProfileCheckConfigBuilder {

	private const val DNS_TAG = "check-dns-local"
	private const val DIRECT_TAG = "check-direct"
	private val IGNORED_OUTBOUND_TYPES = setOf("dns", "block")

	data class Result(
		/** Готовый конфиг или null, если ни один профиль не удалось подготовить. */
		val content: String?,
		/** profileId -> локальный порт mixed-инбаунда. */
		val ports: Map<Long, Int>,
		/** profileId -> текст ошибки подготовки конфига. */
		val errors: Map<Long, String>,
	)

	private class PreparedProfile(
		val outbounds: List<JSONObject>,
		val endpoints: List<JSONObject>,
		val mainTag: String,
	)

	fun build(profiles: List<Profile>): Result {
		val prepared = linkedMapOf<Long, PreparedProfile>()
		val errors = linkedMapOf<Long, String>()

		for (profile in profiles) {
			try {
				val item = prepareProfile(profile)
				// Проверяем каждый профиль отдельно, чтобы один битый конфиг не ломал общий.
				Libbox.checkConfig(assemble(mapOf(profile.id to item), mapOf(profile.id to 0)).toString())
				prepared[profile.id] = item
			} catch (e: Exception) {
				errors[profile.id] = "Ошибка конфига: ${e.message?.replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "") ?: e.javaClass.simpleName}"
			}
		}

		if (prepared.isEmpty()) {
			return Result(content = null, ports = emptyMap(), errors = errors)
		}

		val ports = linkedMapOf<Long, Int>()
		var nextPort = 20_000
		for (profileId in prepared.keys) {
			val port = Libbox.availablePort(nextPort)
			ports[profileId] = port
			nextPort = port + 1
		}

		return Result(
			content = assemble(prepared, ports).toString(),
			ports = ports,
			errors = errors,
		)
	}

	private fun prepareProfile(profile: Profile): PreparedProfile {
		val config = MobileProfileConfig.normalize(JSONObject(File(profile.typed.path).readText()))
		val prefix = "p${profile.id}-"

		val rawOutbounds = config.optJSONArray("outbounds").toObjectList()
			.filter { it.optString("type") !in IGNORED_OUTBOUND_TYPES }
		val rawEndpoints = config.optJSONArray("endpoints").toObjectList()

		// Нормализуем теги (у outbound тег может отсутствовать).
		rawOutbounds.forEachIndexed { index, outbound ->
			if (outbound.optString("tag").isEmpty()) outbound.put("tag", "outbound-$index")
		}
		rawEndpoints.forEachIndexed { index, endpoint ->
			if (endpoint.optString("tag").isEmpty()) endpoint.put("tag", "endpoint-$index")
		}

        val nodes = (rawOutbounds + rawEndpoints).associateBy { it.getString("tag") }
        require(nodes.size == rawOutbounds.size + rawEndpoints.size) { "повторяется тег outbound/endpoint" }
        val knownTags = nodes.keys
		val finalTag = config.optJSONObject("route")?.optString("final").orEmpty()
		val mainTag = when {
            "proxy" in knownTags -> "proxy"
			finalTag.isNotEmpty() && finalTag in knownTags && nodes[finalTag]?.optString("type") !in setOf("direct", "block", "dns") -> finalTag
            rawEndpoints.isNotEmpty() -> rawEndpoints.first().getString("tag")
            rawOutbounds.any { it.optString("type") != "direct" } -> rawOutbounds.first { it.optString("type") != "direct" }.getString("tag")
			else -> throw IllegalStateException("в профиле нет outbounds")
		}

        val reachable = linkedSetOf<String>()
        val active = mutableSetOf<String>()
        fun visit(tag: String) {
            require(tag !in active) { "циклическая зависимость: $tag" }
            if (tag in reachable) return
            val item = nodes[tag] ?: error("не найдена зависимость: $tag")
            active.add(tag)
            listOf("detour", "default").map { item.optString(it) }.filter { it.isNotEmpty() }.forEach(::visit)
            item.optJSONArray("outbounds")?.let { list -> (0 until list.length()).forEach { visit(list.getString(it)) } }
            active.remove(tag)
            reachable.add(tag)
        }
        visit(mainTag)
        rawEndpoints.filter { it.optString("tag") in reachable && it.optString("type") == "wireguard" }.forEach { endpoint ->
            val peers = endpoint.optJSONArray("peers").toObjectList()
            require(peers.isNotEmpty() && peers.all { it.optString("address").isNotBlank() && it.optInt("port") in 1..65535 }) {
                "нет адреса или порта peer WireGuard/AmneziaWG; обновите конфиги из админки"
            }
            endpoint.put("system", false).put("listen_port", 0)
            endpoint.remove("name")
        }

		fun rewrite(item: JSONObject): JSONObject {
			item.put("tag", prefix + item.getString("tag"))
			// DNS-серверы профиля не переносим — используется общий резолвер.
			item.remove("domain_resolver")
			item.optString("detour").takeIf { it.isNotEmpty() }?.let { item.put("detour", prefix + it) }
			item.optString("default").takeIf { it.isNotEmpty() }?.let { item.put("default", prefix + it) }
			item.optJSONArray("outbounds")?.let { tags ->
				item.put("outbounds", JSONArray((0 until tags.length()).map { prefix + tags.getString(it) }))
			}
			return item
		}

		return PreparedProfile(
			outbounds = rawOutbounds.filter { it.optString("tag") in reachable }.map(::rewrite),
			endpoints = rawEndpoints.filter { it.optString("tag") in reachable }.map(::rewrite),
			mainTag = prefix + mainTag,
		)
	}

	private fun assemble(prepared: Map<Long, PreparedProfile>, ports: Map<Long, Int>): JSONObject {
		val inbounds = JSONArray()
		val outbounds = JSONArray()
		val endpoints = JSONArray()
		val rules = JSONArray()

		outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))

		for ((profileId, item) in prepared) {
			val inboundTag = "check-in-$profileId"
			inbounds.put(
				JSONObject()
					.put("type", "mixed")
					.put("tag", inboundTag)
					.put("listen", "127.0.0.1")
					.put("listen_port", ports.getValue(profileId)),
			)
			item.outbounds.forEach { outbounds.put(it) }
			item.endpoints.forEach { endpoints.put(it) }
			rules.put(
				JSONObject()
					.put("inbound", JSONArray().put(inboundTag))
					.put("action", "route")
					.put("outbound", item.mainTag),
			)
		}

		return JSONObject().apply {
			put("log", JSONObject().put("level", "warn"))
			put(
				"dns",
				JSONObject().put("strategy", "ipv4_only").put(
					"servers",
					JSONArray().put(JSONObject().put("type", "udp").put("server", "1.1.1.1").put("tag", DNS_TAG)),
				),
			)
			put("inbounds", inbounds)
			put("outbounds", outbounds)
			if (endpoints.length() > 0) put("endpoints", endpoints)
			put(
				"route",
				JSONObject()
					.put("rules", rules)
					.put("final", DIRECT_TAG)
					.put("auto_detect_interface", true)
					.put("default_domain_resolver", JSONObject().put("server", DNS_TAG).put("strategy", "ipv4_only")),
			)
		}
	}

	private fun JSONArray?.toObjectList(): List<JSONObject> {
		if (this == null) return emptyList()
		return (0 until length()).mapNotNull { optJSONObject(it) }
	}
}
