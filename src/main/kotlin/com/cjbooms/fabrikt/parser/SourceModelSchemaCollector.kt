package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal object SourceModelSchemaCollector {
    fun collect(
        root: JsonNode,
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
        }

    private fun String.toJsonPointerToken(): String = replace("~", "~0").replace("/", "~1")
}
