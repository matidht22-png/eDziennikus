/*
 * Copyright (c) Mikołaj Olszewski 2026-10-14.
 */

package eu.mikus.edziennik.ext

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

fun Any?.toJsonElement(): JsonElement = when (this) {
    is Collection<*> -> JsonArray(this)
    else -> Gson().toJsonTree(this)
}

operator fun JsonObject.set(key: String, value: JsonElement) = this.add(key, value)
operator fun JsonObject.set(key: String, value: Boolean) = this.addProperty(key, value)
operator fun JsonObject.set(key: String, value: String?) = this.addProperty(key, value)
operator fun JsonObject.set(key: String, value: Number) = this.addProperty(key, value)
operator fun JsonObject.set(key: String, value: Char) = this.addProperty(key, value)

fun JsonObject(vararg properties: Pair<String, Any?>): JsonObject {
    return JsonObject().apply {
        for (property in properties) {
            val (key, value) = property
            when (value) {
                is JsonElement -> add(key, value)
                is String -> addProperty(key, value)
                is Char -> addProperty(key, value)
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                is Enum<*> -> addProperty(key, value.toInt())
                null -> add(key, null)
                else -> add(key, value.toJsonElement())
            }
        }
    }
}

fun JsonArray(vararg properties: Any?) = JsonArray(properties.toList())

fun JsonArray(properties: Collection<Any?>): JsonArray {
    return JsonArray().apply {
        for (property in properties) {
            when (property) {
                is JsonElement -> add(property as JsonElement?)
                is String -> add(property as String?)
                is Char -> add(property as Char?)
                is Number -> add(property as Number?)
                is Boolean -> add(property as Boolean?)
                is Enum<*> -> add(property.toInt())
                else -> add(property.toJsonElement())
            }
        }
    }
}

operator fun JsonArray.plusAssign(o: JsonElement) = this.add(o)
operator fun JsonArray.plusAssign(o: String) = this.add(o)
operator fun JsonArray.plusAssign(o: Char) = this.add(o)
operator fun JsonArray.plusAssign(o: Number) = this.add(o)
operator fun JsonArray.plusAssign(o: Boolean) = this.add(o)

fun JsonObject.mergeWith(other: JsonObject): JsonObject {
    for ((key, value) in other.entrySet()) {
        when (value) {
            is JsonObject -> when {
                this.has(key) -> this.getJsonObject(key)?.mergeWith(value)
                else -> this.add(key, value)
            }
            is JsonArray -> when {
                this.has(key) -> this.getJsonArray(key)?.addAll(value)
                else -> this.add(key, value)
            }
            else -> this.add(key, value)
        }
    }
    return this
}
