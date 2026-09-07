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
            listOf("parameters", "headers").forEach { componentType ->
                collectComponentSchemaOrContent(components.path(componentType), "#/components/$componentType", schemaEntryPoints)
            }
            listOf("requestBodies", "responses").forEach { componentType ->
                collectComponentContent(components.path(componentType), "#/components/$componentType", schemaEntryPoints)
            }
            if (version?.isAtLeast(3, 2) == true) {
                val mediaTypes = components.path("mediaTypes")
                if (mediaTypes.isObject) {
                    mediaTypes.properties().forEach { (name, mediaType) ->
                        val mediaTypeLocation = "#/components/mediaTypes/${name.toJsonPointerToken()}"
                        schemaEntryPoints["$mediaTypeLocation/schema"]?.let { register(name, it) }
                        schemaEntryPoints["$mediaTypeLocation/itemSchema"]?.let { register("$name Item".toModelClassName(), it) }
                        collectMediaTypeEncodingHeaders(mediaType, mediaTypeLocation, schemaEntryPoints)
                    }
                }
            }
            val componentResponses = components.path("responses")
            if (componentResponses.isObject) {
                componentResponses.properties().forEach { (name, response) ->
                    collectHeaders(
                        response.path("headers"),
                        "#/components/responses/${name.toJsonPointerToken()}/headers",
                        schemaEntryPoints,
                    )
                }
            }
            val componentCallbacks = components.path("callbacks")
            if (componentCallbacks.isObject) {
                componentCallbacks.properties().forEach { (name, callback) ->
                    if (callback.isObject && !callback.path("${'$'}ref").isTextual) {
                        collectPathItems(
                            callback,
                            "#/components/callbacks/${name.toJsonPointerToken()}",
                            version,
                            schemaEntryPoints,
                            pathsOnly = false,
                        )
                    }
                }
            }
            collectPathItems(root.path("paths"), "#/paths", version, schemaEntryPoints, pathsOnly = true)
            if (version?.isAtLeast(3, 1) == true) {
                collectPathItems(root.path("webhooks"), "#/webhooks", version, schemaEntryPoints, pathsOnly = false)
            }
        }

    private fun MutableMap<String, SourceSchema>.collectComponentSchemaOrContent(
        components: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!components.isObject) return
        components.properties().forEach { (name, component) ->
            val componentLocation = "$location/${name.toJsonPointerToken()}"
            val schema = schemaEntryPoints["$componentLocation/schema"]
            if (schema != null) {
                register(name, schema)
            } else {
                collectContentSchemas(component.path("content"), "$componentLocation/content", schemaEntryPoints) { mediaType, multiple ->
                    listOfNotNull(name, mediaType.takeIf { multiple }).joinToString(" ").toModelClassName()
                }
            }
        }
    }

    private fun MutableMap<String, SourceSchema>.collectComponentContent(
        components: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!components.isObject) return
        components.properties().forEach { (name, component) ->
            val componentLocation = "$location/${name.toJsonPointerToken()}"
            collectContentSchemas(component.path("content"), "$componentLocation/content", schemaEntryPoints) { mediaType, multiple ->
                listOfNotNull(name, mediaType.takeIf { multiple }).joinToString(" ").toModelClassName()
            }
        }
    }

    private fun MutableMap<String, SourceSchema>.collectPathItems(
        pathItems: JsonNode,
        location: String,
        version: OpenApiVersion?,
        schemaEntryPoints: Map<String, SourceSchema>,
        pathsOnly: Boolean,
    ) {
        if (!pathItems.isObject) return
        pathItems.properties().filter { (path, _) -> !pathsOnly || path.startsWith('/') }.forEach { (path, pathItem) ->
            val pathLocation = "$location/${path.toJsonPointerToken()}"
            collectParameters(pathItem.path("parameters"), "$pathLocation/parameters", schemaEntryPoints)
            operationNames(version).forEach { method ->
                collectOperation(pathItem.path(method), "$pathLocation/$method", method, path, version, schemaEntryPoints)
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
                            version,
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
        version: OpenApiVersion?,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!operation.isObject) return
        val operationName =
            operation
                .path("operationId")
                .takeIf(JsonNode::isTextual)
                ?.textValue()
                ?.takeIf(String::isNotBlank)
                ?.toModelClassName()
                ?: "$method $path".toModelClassName()
        collectParameters(operation.path("parameters"), "$location/parameters", schemaEntryPoints)
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
                collectHeaders(
                    response.path("headers"),
                    "$location/responses/${status.toJsonPointerToken()}/headers",
                    schemaEntryPoints,
                )
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

        val callbacks = operation.path("callbacks")
        if (callbacks.isObject) {
            callbacks.properties().forEach { (callbackName, callback) ->
                if (callback.isObject && !callback.path("${'$'}ref").isTextual) {
                    collectPathItems(
                        pathItems = callback,
                        location = "$location/callbacks/${callbackName.toJsonPointerToken()}",
                        version = version,
                        schemaEntryPoints = schemaEntryPoints,
                        pathsOnly = false,
                    )
                }
            }
        }
    }

    private fun MutableMap<String, SourceSchema>.collectHeaders(
        headers: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!headers.isObject) return
        headers
            .properties()
            .filterNot { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            .forEach { (name, header) ->
                if (header.isObject && !header.path("${'$'}ref").isTextual) {
                    val headerLocation = "$location/${name.toJsonPointerToken()}"
                    val schema = schemaEntryPoints["$headerLocation/schema"]
                    if (schema != null) {
                        register(name.toModelClassName(), schema)
                    } else {
                        collectContentSchemas(header.path("content"), "$headerLocation/content", schemaEntryPoints) { mediaType, multiple ->
                            listOfNotNull(name, mediaType.takeIf { multiple }).joinToString(" ").toModelClassName()
                        }
                    }
                }
            }
    }

    private fun MutableMap<String, SourceSchema>.collectParameters(
        parameters: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!parameters.isArray) return
        parameters.forEachIndexed { index, parameter ->
            if (parameter.isObject && !parameter.path("${'$'}ref").isTextual) {
                val name =
                    parameter
                        .path("name")
                        .takeIf(JsonNode::isTextual)
                        ?.textValue()
                        ?.takeIf(String::isNotBlank)
                if (name != null) {
                    val parameterLocation = "$location/$index"
                    val schema = schemaEntryPoints["$parameterLocation/schema"]
                    if (schema != null) {
                        register(name.toModelClassName(), schema)
                    } else {
                        collectContentSchemas(
                            parameter.path("content"),
                            "$parameterLocation/content",
                            schemaEntryPoints,
                        ) { mediaType, multiple ->
                            listOfNotNull(name, mediaType.takeIf { multiple })
                                .joinToString(" ")
                                .toModelClassName()
                        }
                    }
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
        val mediaTypes = content.properties().toList()
        mediaTypes.forEach { (mediaType, mediaTypeObject) ->
            val mediaTypeLocation = "$location/${mediaType.toJsonPointerToken()}"
            val modelName = name(mediaType, mediaTypes.size > 1)
            schemaEntryPoints["$mediaTypeLocation/schema"]?.let { register(modelName, it) }
            schemaEntryPoints["$mediaTypeLocation/itemSchema"]?.let { register("$modelName Item".toModelClassName(), it) }
            collectMediaTypeEncodingHeaders(mediaTypeObject, mediaTypeLocation, schemaEntryPoints)
        }
    }

    private fun MutableMap<String, SourceSchema>.collectMediaTypeEncodingHeaders(
        mediaType: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        collectEncodingMap(mediaType.path("encoding"), "$location/encoding", schemaEntryPoints)
        collectEncodingArray(mediaType.path("prefixEncoding"), "$location/prefixEncoding", schemaEntryPoints)
        collectEncoding(mediaType.path("itemEncoding"), "$location/itemEncoding", schemaEntryPoints)
    }

    private fun MutableMap<String, SourceSchema>.collectEncodingMap(
        encodings: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!encodings.isObject) return
        encodings.properties().forEach { (name, encoding) ->
            collectEncoding(encoding, "$location/${name.toJsonPointerToken()}", schemaEntryPoints)
        }
    }

    private fun MutableMap<String, SourceSchema>.collectEncodingArray(
        encodings: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!encodings.isArray) return
        encodings.forEachIndexed { index, encoding -> collectEncoding(encoding, "$location/$index", schemaEntryPoints) }
    }

    private fun MutableMap<String, SourceSchema>.collectEncoding(
        encoding: JsonNode,
        location: String,
        schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        if (!encoding.isObject) return
        collectHeaders(encoding.path("headers"), "$location/headers", schemaEntryPoints)
        collectEncodingMap(encoding.path("encoding"), "$location/encoding", schemaEntryPoints)
        collectEncodingArray(encoding.path("prefixEncoding"), "$location/prefixEncoding", schemaEntryPoints)
        collectEncoding(encoding.path("itemEncoding"), "$location/itemEncoding", schemaEntryPoints)
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
