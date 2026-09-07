package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceOperationDocument(
    val paths: List<SourcePathItem>,
    val webhooks: List<SourcePathItem>,
    val reusablePathItems: Map<String, SourcePathItem>,
    val reusableCallbacks: Map<String, SourceCallback>,
)

internal data class SourcePathItem(
    val location: String,
    val key: String,
    val kind: SourcePathItemKind,
    val node: JsonNode,
    val reference: String?,
    val summary: String?,
    val description: String?,
    val operations: List<SourceOperation>,
)

internal enum class SourcePathItemKind {
    PATH,
    WEBHOOK,
    CALLBACK,
    REUSABLE_PATH_ITEM,
    REUSABLE_CALLBACK,
}

internal data class SourceOperation(
    val location: String,
    val method: SourceOperationMethod,
    val node: JsonNode,
    val operationId: String?,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    val callbacks: List<SourceCallback>,
)

internal sealed interface SourceOperationMethod {
    val wireName: String

    data class Fixed(
        val value: SourceFixedOperationMethod,
    ) : SourceOperationMethod {
        override val wireName: String = value.wireName
    }

    data class Additional(
        override val wireName: String,
    ) : SourceOperationMethod
}

internal enum class SourceFixedOperationMethod(
    val fieldName: String,
    val wireName: String,
) {
    GET("get", "GET"),
    PUT("put", "PUT"),
    POST("post", "POST"),
    DELETE("delete", "DELETE"),
    OPTIONS("options", "OPTIONS"),
    HEAD("head", "HEAD"),
    PATCH("patch", "PATCH"),
    TRACE("trace", "TRACE"),
    QUERY("query", "QUERY"),
}

internal data class SourceCallback(
    val location: String,
    val name: String,
    val node: JsonNode,
    val reference: String?,
    val pathItems: List<SourcePathItem>,
)

internal object SourceOperationDocumentParser {
    fun parse(
        root: JsonNode,
        version: OpenApiVersion?,
    ): SourceOperationDocument = Collector(version).collect(root)

    private class Collector(
        private val version: OpenApiVersion?,
    ) {
        fun collect(root: JsonNode): SourceOperationDocument =
            SourceOperationDocument(
                paths = collectPathItemMap(root["paths"], "#/paths", SourcePathItemKind.PATH, pathsOnly = true),
                webhooks =
                    if (version?.isAtLeast(3, 1) == true) {
                        collectPathItemMap(root["webhooks"], "#/webhooks", SourcePathItemKind.WEBHOOK)
                    } else {
                        emptyList()
                    },
                reusablePathItems =
                    if (version?.isAtLeast(3, 1) == true) {
                        collectNamedPathItems(
                            root.path("components").path("pathItems"),
                            "#/components/pathItems",
                            SourcePathItemKind.REUSABLE_PATH_ITEM,
                        )
                    } else {
                        emptyMap()
                    },
                reusableCallbacks =
                    collectNamedCallbacks(
                        root.path("components").path("callbacks"),
                        "#/components/callbacks",
                        SourcePathItemKind.REUSABLE_CALLBACK,
                    ),
            )

        private fun collectNamedPathItems(
            pathItems: JsonNode,
            location: String,
            kind: SourcePathItemKind,
        ): Map<String, SourcePathItem> {
            if (!pathItems.isObject) return emptyMap()

            return pathItems.properties().associate { (name, pathItem) ->
                name to collectPathItem(pathItem, "$location/${name.toJsonPointerToken()}", name, kind)
            }
        }

        private fun collectNamedCallbacks(
            callbacks: JsonNode,
            location: String,
            pathItemKind: SourcePathItemKind,
        ): Map<String, SourceCallback> {
            if (!callbacks.isObject) return emptyMap()

            return callbacks.properties().associate { (name, callback) ->
                name to collectCallback(callback, "$location/${name.toJsonPointerToken()}", name, pathItemKind)
            }
        }

        private fun collectPathItemMap(
            pathItems: JsonNode?,
            location: String,
            kind: SourcePathItemKind,
            pathsOnly: Boolean = false,
            skipSpecificationExtensions: Boolean = false,
        ): List<SourcePathItem> {
            if (pathItems?.isObject != true) return emptyList()

            return pathItems
                .properties()
                .filter { (key, _) -> !pathsOnly || key.startsWith('/') }
                .filterNot { (key, _) -> skipSpecificationExtensions && key.isSpecificationExtension() }
                .map { (key, pathItem) ->
                    collectPathItem(pathItem, "$location/${key.toJsonPointerToken()}", key, kind)
                }.toList()
        }

        private fun collectPathItem(
            pathItem: JsonNode,
            location: String,
            key: String,
            kind: SourcePathItemKind,
        ): SourcePathItem =
            SourcePathItem(
                location = location,
                key = key,
                kind = kind,
                node = pathItem,
                reference = pathItem.text("\$ref"),
                summary = pathItem.text("summary"),
                description = pathItem.text("description"),
                operations = collectFixedOperations(pathItem, location),
            )

        private fun collectFixedOperations(
            pathItem: JsonNode,
            location: String,
        ): List<SourceOperation> =
            pathItem
                .properties()
                .mapNotNull { (fieldName, operation) ->
                    val method = FIXED_METHODS_BY_FIELD[fieldName] ?: return@mapNotNull null
                    if (method == SourceFixedOperationMethod.QUERY && version?.isAtLeast(3, 2) != true) return@mapNotNull null
                    operation
                        .takeIf(JsonNode::isObject)
                        ?.let { collectOperation(it, "$location/$fieldName", method) }
                }.toList()

        private fun collectOperation(
            operation: JsonNode,
            location: String,
            method: SourceFixedOperationMethod,
        ): SourceOperation =
            SourceOperation(
                location = location,
                method = SourceOperationMethod.Fixed(method),
                node = operation,
                operationId = operation.text("operationId"),
                summary = operation.text("summary"),
                description = operation.text("description"),
                tags = operation["tags"]?.takeIf(JsonNode::isArray)?.mapNotNull { it.takeIf(JsonNode::isTextual)?.textValue() }.orEmpty(),
                deprecated = operation["deprecated"]?.takeIf(JsonNode::isBoolean)?.booleanValue() ?: false,
                callbacks = collectOperationCallbacks(operation, location),
            )

        private fun collectOperationCallbacks(
            operation: JsonNode,
            location: String,
        ): List<SourceCallback> {
            val callbacks = operation["callbacks"]?.takeIf(JsonNode::isObject) ?: return emptyList()
            return callbacks
                .properties()
                .map { (name, callback) ->
                    collectCallback(
                        callback = callback,
                        location = "$location/callbacks/${name.toJsonPointerToken()}",
                        name = name,
                        pathItemKind = SourcePathItemKind.CALLBACK,
                    )
                }.toList()
        }

        private fun collectCallback(
            callback: JsonNode,
            location: String,
            name: String,
            pathItemKind: SourcePathItemKind,
        ): SourceCallback {
            val reference = callback.text("\$ref")
            return SourceCallback(
                location = location,
                name = name,
                node = callback,
                reference = reference,
                pathItems =
                    if (reference == null) {
                        collectPathItemMap(
                            callback,
                            location,
                            pathItemKind,
                            skipSpecificationExtensions = true,
                        )
                    } else {
                        emptyList()
                    },
            )
        }

        private fun JsonNode.text(fieldName: String): String? = this[fieldName]?.takeIf(JsonNode::isTextual)?.textValue()

        private fun String.toJsonPointerToken(): String = replace("~", "~0").replace("/", "~1")

        private fun String.isSpecificationExtension(): Boolean = startsWith("x-", ignoreCase = true)

        private companion object {
            val FIXED_METHODS_BY_FIELD = SourceFixedOperationMethod.entries.associateBy(SourceFixedOperationMethod::fieldName)
        }
    }
}
