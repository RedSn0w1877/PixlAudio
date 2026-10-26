package com.theveloper.pixelplay.data.ai.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Checks a tool input against the subset of JSON Schema our tool definitions use: `type`
 * (object, array, string, integer, number, boolean), `properties`, `required`,
 * `additionalProperties: false`, `items` and `enum`. A streamed tool input is never trusted
 * before this passes (eager input streaming skips the API's own validation). Pure
 * (ToolInputValidatorTest).
 */
object ToolInputValidator {

    /** Null when [value] matches [schema]; otherwise what is wrong, for the tool_result. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "input"): String? {
        val type = schema.string("type")
        when (type) {
            "object" -> {
                val obj = value as? JsonObject ?: return "$path must be an object"
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                for (name in required) {
                    if (!obj.containsKey(name) || obj[name] is JsonNull) return "$path.$name is required"
                }
                val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
                for ((name, child) in obj) {
                    val childSchema = properties[name] as? JsonObject
                    if (childSchema == null) {
                        if (closed) return "$path.$name is not an allowed property"
                        continue
                    }
                    validate(childSchema, child, "$path.$name")?.let { return it }
                }
            }
            "array" -> {
                val array = value as? JsonArray ?: return "$path must be an array"
                val items = schema["items"] as? JsonObject
                if (items != null) {
                    array.forEachIndexed { i, item -> validate(items, item, "$path[$i]")?.let { return it } }
                }
            }
            "string" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) return "$path must be a string"
            }
            "integer" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.longOrNull == null) return "$path must be an integer"
            }
            "number" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.doubleOrNull == null) return "$path must be a number"
            }
            "boolean" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) return "$path must be true or false"
            }
        }
        val allowed = schema["enum"] as? JsonArray
        if (allowed != null && allowed.none { it == value }) {
            return "$path must be one of ${allowed.joinToString(", ")}"
        }
        return null
    }
}
