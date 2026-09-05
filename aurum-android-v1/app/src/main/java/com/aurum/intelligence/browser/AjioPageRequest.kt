package com.aurum.intelligence.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AjioPageRequest(val json: String, val pathname: String)

object AjioPageRequestParser {
    fun parseEvaluateResult(value: String?): AjioPageRequest? = runCatching {
        val decoded = Json.parseToJsonElement(value ?: return null)
        val stored = if (decoded is JsonObject) decoded else {
            Json.parseToJsonElement(decoded.jsonPrimitive.content).jsonObject
        }
        val request = stored["request"] as? JsonObject ?: return null
        val pathname = request["pathname"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return null
        if (request["query"] !is JsonObject) return null
        AjioPageRequest(request.toString(), pathname)
    }.getOrNull()

    fun injectionPrefix(request: AjioPageRequest?): String = request?.let {
        "globalThis.__AURUM_AJIO_REQUEST__=${it.json};delete globalThis.__AURUM_AJIO_PAGE0__;\n"
    }.orEmpty()
}