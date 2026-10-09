// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.bikesched.JsonMapper

/**
 * Implements [JsonMapper] using kotlinx-serialization.
 *
 * Payloads and step results must be `@Serializable` classes (or types with a built-in serializer
 * such as [String]). The serializer is looked up from the runtime [Class] bikesched passes.
 */
class KotlinxSerializationJsonMapper(private val json: Json = ShopJson) : JsonMapper {

  override fun toJson(value: Any): String =
    json.encodeToString(json.serializersModule.serializer(value.javaClass), value)

  @Suppress("UNCHECKED_CAST")
  override fun <T> fromJson(jsonStr: String, type: Class<T>): T =
    json.decodeFromString(json.serializersModule.serializer(type), jsonStr) as T
}
