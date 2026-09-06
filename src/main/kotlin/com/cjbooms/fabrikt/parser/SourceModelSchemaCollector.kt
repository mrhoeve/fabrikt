package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.fasterxml.jackson.databind.JsonNode

internal object SourceModelSchemaCollector {
    fun collect(
        root: JsonNode,
        version: OpenApiVersion?,
        schemaEntryPoints: Map<String, SourceSchema>,
        componentSchemas: Map<String, SourceSchema>,
    ): Map<String, SourceSchema> =
        buildMap {
            putAll(componentSchemas)
            val components = root.path("components")
            listOf("parameters", "requestBodies", "responses").forEach { componentType ->
                val entries = components.path(componentType)
                if (entries.isObject) {
                    entries.properties().forEach { (name, _) ->
                        val prefix = "#/components/$componentType/${name.toJsonPointerToken()}"
                        schemaEntryPoints.entries
                            .firstOrNull { (location, _) ->
                                location == "$prefix/schema" ||
                                    (location.startsWith("$prefix/content/") && location.endsWith("/schema"))
                            }?.value
                            ?.let { putIfAbsent(name, it) }
                    }
                }
            }
            collectPathItems(root.path("paths"), "#/paths", version, schemaEntryPoints)
        }

    private fun MutableMap<String, SourceSchema>.collectPathItems(
        pathItems: JsonNode,
        location: String,
        version: OpenApiVersion?,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!pathItems.isObject) return
        pathItems.properties().filter { (path, _) -> path.startsWith('/') }.forEach { (path, pathItem) ->
            val pathLocation = "$location/${path.toJsonPointerToken()}"
            operationNames(version).forEach { method ->
                collectOperation(pathItem.path(method), "$pathLocation/$method", method, path, schemaEntryPoints)
            }
            if (version?.isAtLeast(3, 2) == true) {
                val additionalOperations = pathItem.path("additionalOperations")
                if (additionalOperations.isObject) {
                    additionalOperations.properties().forEach { (method, operation) ->
                        collectOperation(
                            operation,
                            "$pathLocation/additionalOperations/${method.toJsonPointerToken()}",
                            method,
                            path,
                            schemaEntryPoints,
                        )
                    }
                }
            }
        }
    }

    private fun MutableMap<String, SourceSchema>.collectOperation(
        operation: JsonNode,
        location: String,
        method: String,
        path: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!operation.isObject) return
        val operationName =
            operation.path("operationId").takeIf(JsonNode::isTextual)?.textValue()?.takeIf(String::isNotBlank)?.toModelClassName()
                ?: "$method $path".toModelClassName()
        collectContentSchemas(
            content = operation.path("requestBody").path("content"),
            location = "$location/requestBody/content",
            schemaEntryPoints = schemaEntryPoints,
        ) { mediaType, hasMultipleMediaTypes ->
            listOfNotNull(operationName, mediaType.takeIf { hasMultipleMediaTypes }, "Request").joinToString(" ").toModelClassName()
        }

        val responses = operation.path("responses")
        if (responses.isObject) {
            responses.properties().filterNot { (status, _) -> status.startsWith("x-", ignoreCase = true) }.forEach { (status, response) ->
                collectContentSchemas(
                    content = response.path("content"),
                    location = "$location/responses/${status.toJsonPointerToken()}/content",
                    schemaEntryPoints = schemaEntryPoints,
                ) { mediaType, hasMultipleMediaTypes ->
                    listOfNotNull(operationName, status, mediaType.takeIf { hasMultipleMediaTypes }, "Response")
                        .joinToString(" ")
                        .toModelClassName()
                }
            }
        }
    }

    private fun MutableMap<String, SourceSchema>.collectContentSchemas(
        content: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
        name: (String, Boolean) -> String,
    ) {
        if (!content.isObject) return
        val schemas =
            content.properties().mapNotNull { (mediaType, _) ->
                schemaEntryPoints["$location/${mediaType.toJsonPointerToken()}/schema"]?.let { mediaType to it }
            }
        schemas.forEach { (mediaType, schema) -> register(name(mediaType, schemas.size > 1), schema) }
    }

    private fun MutableMap<String, SourceSchema>.register(
        preferredName: String,
        schema: SourceSchema,
    ) {
        if (this[preferredName]?.identity == schema.identity) return
        if (preferredName !in this) {
            this[preferredName] = schema
            return
        }
        var collisionIndex = 1
        while (true) {
            val numericSuffix = if (collisionIndex == 1) "" else collisionIndex.toString()
            val suggestion = "${preferredName}Extra$numericSuffix"
            if (suggestion !in this) {
                this[suggestion] = schema
                return
            }
            collisionIndex++
        }
    }

    private fun operationNames(version: OpenApiVersion?): List<String> =
        if (version?.isAtLeast(3, 2) == true) OPERATION_NAMES + "query" else OPERATION_NAMES

    private fun String.toJsonPointerToken(): String = replace("~", "~0").replace("/", "~1")

    private val OPERATION_NAMES = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
}
