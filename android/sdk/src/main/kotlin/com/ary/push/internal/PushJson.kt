package com.ary.push.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal JSON helpers built on `org.json`, which ships with Android.
 *
 * The SDK deliberately does not pull in Moshi, Gson or kotlinx-serialization: the payloads it
 * exchanges are small and fixed, and every extra dependency is one the host application's build
 * has to reconcile.
 */
internal object PushJson {

    /** Encodes a request body. Accepts maps, lists, primitives, and pre-encoded JSON strings. */
    fun encode(body: Any?): String = when (body) {
        null -> ""
        is String -> body
        is JSONObject -> body.toString()
        is JSONArray -> body.toString()
        is Map<*, *> -> toJsonObject(body).toString()
        is Iterable<*> -> toJsonArray(body).toString()
        else -> JSONObject.quote(body.toString())
    }

    /** Converts a map to a [JSONObject], dropping null values and recursing into nested maps. */
    fun toJsonObject(map: Map<*, *>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            if (key == null) continue
            val encoded = encodeValue(value) ?: continue
            json.put(key.toString(), encoded)
        }
        return json
    }

    private fun toJsonArray(values: Iterable<*>): JSONArray {
        val array = JSONArray()
        for (value in values) {
            array.put(encodeValue(value) ?: JSONObject.NULL)
        }
        return array
    }

    private fun encodeValue(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> toJsonObject(value)
        is Iterable<*> -> toJsonArray(value)
        is Array<*> -> toJsonArray(value.asIterable())
        is Number, is Boolean, is String, is JSONObject, is JSONArray -> value
        else -> value.toString()
    }

    /** Parses an object body, returning null rather than throwing on malformed input. */
    fun parseObject(raw: String?): JSONObject? = runCatching {
        if (raw.isNullOrBlank()) null else JSONObject(raw)
    }.getOrNull()

    /** Reads a nested string, tolerating absent keys and JSON nulls. */
    fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    /** Flattens a JSON object into a string map, as used for notification data payloads. */
    fun flattenToStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = LinkedHashMap<String, String>(json.length())
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (json.isNull(key)) continue
            result[key] = when (val value = json.get(key)) {
                is JSONObject, is JSONArray -> value.toString()
                else -> value.toString()
            }
        }
        return result
    }
}
