package ru.ravel.ultunnel.utils

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class StrictTypeAdapterFactory : TypeAdapterFactory {
	override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
		val delegate = gson.getDelegateAdapter(this, type)
		return object : TypeAdapter<T>() {
			override fun write(out: JsonWriter, value: T) {
				delegate.write(out, value)
			}

			override fun read(reader: JsonReader): T {
				reader.isLenient = false
				val obj = reader.peek()
				if (obj == JsonToken.BEGIN_OBJECT) {
					reader.beginObject()
					while (reader.hasNext()) {
						val name = reader.nextName()
						val peek = reader.peek()
						if (peek == JsonToken.NULL) {
							reader.nextNull()
						} else {
							reader.skipValue()
						}
					}
					reader.endObject()
				}
				return delegate.read(reader)
			}
		}
	}
}
