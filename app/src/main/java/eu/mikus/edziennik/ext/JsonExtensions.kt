/*
 * Copyright (c) Kuba Szczodrzyński 2021-10-17.
 */

package eu.mikus.edziennik.ext

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun JsonObject?.get(key: String): JsonElement? = this?.get(key)

/*
 * `isJsonPrimitive` is not a type check - a JSON string is a primitive too, so it only ever
 * excluded objects, arrays and JSON null. These narrow to JsonPrimitive first, and the numeric
 * three branch on the number variant as well.
 *
 * `asInt`/`asLong`/`asFloat` are reached only under `isNumber`. Gson's own fallback for the other
 * case is `Integer.parseInt(getAsString())`, which is an implementation detail rather than a
 * documented contract; going through Kotlin's `toIntOrNull` instead keeps this file's behaviour
 * ours. A quoted integer still coerces, which `Config.boundProfileId` depends on.
 */
private fun JsonElement.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.asBoolean
private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.asString
private fun JsonElement.asIntOrNull(): Int? = (this as? JsonPrimitive)?.let {
    if (it.isNumber) it.asInt else it.asString.toIntOrNull()
}
private fun JsonElement.asLongOrNull(): Long? = (this as? JsonPrimitive)?.let {
    if (it.isNumber) it.asLong else it.asString.toLongOrNull()
}
private fun JsonElement.asFloatOrNull(): Float? = (this as? JsonPrimitive)?.let {
    if (it.isNumber) it.asFloat else it.asString.toFloatOrNull()
}
private fun JsonElement.asCharOrNull(): Char? = (this as? JsonPrimitive)?.asString?.firstOrNull()

fun JsonObject?.getBoolean(key: String): Boolean? = get(key)?.asBooleanOrNull()
fun JsonObject?.getString(key: String): String? = get(key)?.asStringOrNull()
fun JsonObject?.getInt(key: String): Int? = get(key)?.asIntOrNull()
fun JsonObject?.getLong(key: String): Long? = get(key)?.asLongOrNull()
fun JsonObject?.getFloat(key: String): Float? = get(key)?.asFloatOrNull()
fun JsonObject?.getChar(key: String): Char? = get(key)?.asCharOrNull()
fun JsonObject?.getJsonObject(key: String): JsonObject? = get(key)?.let { if (it.isJsonObject) it.asJsonObject else null }
fun JsonObject?.getJsonArray(key: String): JsonArray? = get(key)?.let { if (it.isJsonArray) it.asJsonArray else null }

fun JsonObject?.getBoolean(key: String, defaultValue: Boolean): Boolean = get(key)?.asBooleanOrNull() ?: defaultValue
fun JsonObject?.getString(key: String, defaultValue: String): String = get(key)?.asStringOrNull() ?: defaultValue
fun JsonObject?.getInt(key: String, defaultValue: Int): Int = get(key)?.asIntOrNull() ?: defaultValue
fun JsonObject?.getLong(key: String, defaultValue: Long): Long = get(key)?.asLongOrNull() ?: defaultValue
fun JsonObject?.getFloat(key: String, defaultValue: Float): Float = get(key)?.asFloatOrNull() ?: defaultValue
fun JsonObject?.getChar(key: String, defaultValue: Char): Char = get(key)?.asCharOrNull() ?: defaultValue
fun JsonObject?.getJsonObject(key: String, defaultValue: JsonObject): JsonObject = get(key)?.let { if (it.isJsonObject) it.asJsonObject else defaultValue } ?: defaultValue
fun JsonObject?.getJsonArray(key: String, defaultValue: JsonArray): JsonArray = get(key)?.let { if (it.isJsonArray) it.asJsonArray else defaultValue } ?: defaultValue

inline fun <reified E : Enum<E>> JsonObject?.getEnum(key: String) = this?.getInt(key)?.toEnum<E>()
fun JsonObject.putEnum(key: String, value: Enum<*>) = addProperty(key, value.toInt())

fun String.toJsonObject(): JsonObject? = try { JsonParser.parseString(this).asJsonObject } catch (ignore: Exception) { null }
fun String.toJsonArray(): JsonArray? = try { JsonParser.parseString(this).asJsonArray } catch (ignore: Exception) { null }

fun JsonArray.asJsonObjectList() = this.mapNotNull { it as? JsonObject }

@OptIn(ExperimentalContracts::class)
fun JsonArray?.isNullOrEmpty(): Boolean {
    contract {
        returns(false) implies (this@isNullOrEmpty != null)
    }
    return this == null || this.isEmpty
}
