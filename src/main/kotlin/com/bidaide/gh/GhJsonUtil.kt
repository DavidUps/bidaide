package com.bidaide.gh

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Null-tolerant accessors for hand-parsed JSON (GraphQL answers and loosely typed REST payloads).

internal fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
internal fun JsonObject.string(key: String): String? = value(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.int(key: String): Int? = value(key)?.takeIf { it.isJsonPrimitive }?.asInt
internal fun JsonObject.bool(key: String): Boolean = value(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
internal fun JsonObject.obj(key: String): JsonObject? = value(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(key: String): List<JsonObject> =
    value(key)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
internal fun JsonObject.nodes(key: String): List<JsonObject> = obj(key)?.array("nodes").orEmpty()

internal fun parseObject(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

/** Flattens the outer array `gh api --paginate --slurp` wraps REST pages in. */
internal fun slurpedObjects(slurped: String): List<JsonObject> =
    JsonParser.parseString(slurped).asJsonArray.flatMap { page ->
        if (page.isJsonArray) page.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } else listOf(page.asJsonObject)
    }
