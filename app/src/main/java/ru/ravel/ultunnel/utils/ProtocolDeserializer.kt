package ru.ravel.ultunnel.utils

import com.google.gson.*
import java.lang.reflect.Type


class ProtocolDeserializer : JsonDeserializer<List<String>> {
	override fun deserialize(
		json: JsonElement,
		typeOfT: Type,
		context: JsonDeserializationContext
	): List<String> {

		return when {
			json.isJsonArray -> json.asJsonArray.map { it.asString }
			json.isJsonPrimitive -> listOf(json.asString)
			json.isJsonNull -> emptyList()
			else -> emptyList()
		}
	}
}
