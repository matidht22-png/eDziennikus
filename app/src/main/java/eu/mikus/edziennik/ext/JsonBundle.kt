/*
 * Copyright (c) Mikołaj Olszewski 2026-10-14.
 */

package eu.mikus.edziennik.ext

import android.os.Bundle
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

fun JsonObject.toBundle(): Bundle {
    return Bundle().also {
        for ((key, value) in this.entrySet()) {
            when (value) {
                is JsonObject -> it.putBundle(key, value.toBundle())
                is JsonPrimitive -> when {
                    value.isString -> it.putString(key, value.asString)
                    value.isBoolean -> it.putBoolean(key, value.asBoolean)
                    value.isNumber -> it.putInt(key, value.asInt)
                }
            }
        }
    }
}
