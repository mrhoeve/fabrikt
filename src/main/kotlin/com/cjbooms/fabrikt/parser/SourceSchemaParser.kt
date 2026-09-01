package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

internal object SourceSchemaParser {
    fun parse(
        node: JsonNode,
        location: String,
        version: OpenApiVersion?,
    ): SourceSchema =
        if (node.isBoolean) {
            SourceBooleanSchema(location, node, node.booleanValue())
        } else {
            SourceObjectSchema(
                location = location,
                node = node,
                identifier = node["\$id"]?.takeIf(JsonNode::isTextual)?.textValue(),
                anchor = node["\$anchor"]?.takeIf(JsonNode::isTextual)?.textValue(),
                types = readTypes(node, version),
                reference = node["\$ref"]?.takeIf(JsonNode::isTextual)?.textValue(),
                metadata = readMetadata(node),
                constraints = readConstraints(node, version),
                requiredProperties = readStringSet(node["required"]),
                dependentRequired = readDependentRequired(node["dependentRequired"]),
                discriminator = readDiscriminator(node["discriminator"]),
                definitions = readNamedSchemas(node["\$defs"], "$location/\$defs", version),
                properties = readNamedSchemas(node["properties"], "$location/properties", version),
                patternProperties =
                    readNamedSchemas(
                        node["patternProperties"],
                        "$location/patternProperties",
                        version,
                    ),
                dependentSchemas =
                    readNamedSchemas(
                        node["dependentSchemas"],
                        "$location/dependentSchemas",
                        version,
                    ),
                prefixItems = readSchemaList(node["prefixItems"], "$location/prefixItems", version),
                items = readOptionalSchema(node["items"], "$location/items", version),
                contains = readOptionalSchema(node["contains"], "$location/contains", version),
                propertyNames = readOptionalSchema(node["propertyNames"], "$location/propertyNames", version),
                ifSchema = readOptionalSchema(node["if"], "$location/if", version),
                thenSchema = readOptionalSchema(node["then"], "$location/then", version),
                elseSchema = readOptionalSchema(node["else"], "$location/else", version),
                allOf = readSchemaList(node["allOf"], "$location/allOf", version),
                anyOf = readSchemaList(node["anyOf"], "$location/anyOf", version),
                oneOf = readSchemaList(node["oneOf"], "$location/oneOf", version),
                not = readOptionalSchema(node["not"], "$location/not", version),
                additionalProperties =
                    readOptionalSchema(
                        node["additionalProperties"],
                        "$location/additionalProperties",
                        version,
                    ),
                unevaluatedItems =
                    readOptionalSchema(
                        node["unevaluatedItems"],
                        "$location/unevaluatedItems",
                        version,
                    ),
                unevaluatedProperties =
                    readOptionalSchema(
                        node["unevaluatedProperties"],
                        "$location/unevaluatedProperties",
                        version,
                    ),
                contentSchema = readOptionalSchema(node["contentSchema"], "$location/contentSchema", version),
            )
        }

    private fun readNamedSchemas(
        node: JsonNode?,
        location: String,
        version: OpenApiVersion?,
    ): Map<String, SourceSchema> {
        if (node?.isObject != true) return emptyMap()

        return node.properties().associate { (name, schema) ->
            name to parse(schema, "$location/${name.toJsonPointerToken()}", version)
        }
    }

    private fun readOptionalSchema(
        node: JsonNode?,
        location: String,
        version: OpenApiVersion?,
    ): SourceSchema? = node?.takeIf { it.isObject || it.isBoolean }?.let { parse(it, location, version) }

    private fun readSchemaList(
        node: JsonNode?,
        location: String,
        version: OpenApiVersion?,
    ): List<SourceSchema> {
        if (node?.isArray != true) return emptyList()

        return node.mapIndexedNotNull { index, schema ->
            schema.takeIf { it.isObject || it.isBoolean }?.let { parse(it, "$location/$index", version) }
        }
    }

    private fun readTypes(
        node: JsonNode,
        version: OpenApiVersion?,
    ): Set<SourceSchemaType> {
        val typeNode = node["type"]
        val declaredTypes =
            when {
                typeNode?.isTextual == true -> sequenceOf(typeNode.textValue())
                typeNode?.isArray == true -> typeNode.asSequence().filter(JsonNode::isTextual).map(JsonNode::textValue)
                else -> emptySequence()
            }.map(SourceSchemaType::from)
                .toCollection(linkedSetOf())

        if (version?.major == 3 && version.minor == 0 && declaredTypes.isNotEmpty() && node["nullable"]?.asBoolean() == true) {
            declaredTypes.add(SourceSchemaType.NULL)
        }

        return declaredTypes
    }

    private fun readMetadata(node: JsonNode): SourceSchemaMetadata =
        SourceSchemaMetadata(
            title = node.textValue("title"),
            description = node.textValue("description"),
            format = node.textValue("format"),
            defaultValue = node["default"],
            examples = readExamples(node),
            enumValues = node["enum"]?.takeIf(JsonNode::isArray)?.toList().orEmpty(),
            constValue = node["const"],
            readOnly = node["readOnly"]?.asBoolean() == true,
            writeOnly = node["writeOnly"]?.asBoolean() == true,
            deprecated = node["deprecated"]?.asBoolean() == true,
            contentEncoding = node.textValue("contentEncoding"),
            contentMediaType = node.textValue("contentMediaType"),
        )

    private fun readExamples(node: JsonNode): List<JsonNode> =
        node["examples"]?.takeIf(JsonNode::isArray)?.toList()
            ?: node["example"]?.let(::listOf)
            ?: emptyList()

    private fun readConstraints(
        node: JsonNode,
        version: OpenApiVersion?,
    ): SourceSchemaConstraints =
        SourceSchemaConstraints(
            multipleOf = node.decimalValue("multipleOf"),
            minimum = readMinimum(node, version),
            maximum = readMaximum(node, version),
            minLength = node.intValue("minLength"),
            maxLength = node.intValue("maxLength"),
            pattern = node.textValue("pattern"),
            minItems = node.intValue("minItems"),
            maxItems = node.intValue("maxItems"),
            uniqueItems = node["uniqueItems"]?.asBoolean() == true,
            minContains = node.intValue("minContains"),
            maxContains = node.intValue("maxContains"),
            minProperties = node.intValue("minProperties"),
            maxProperties = node.intValue("maxProperties"),
        )

    private fun readMinimum(
        node: JsonNode,
        version: OpenApiVersion?,
    ): SourceSchemaBound? =
        if (version.isOpenApi30()) {
            node.decimalValue("minimum")?.let { SourceSchemaBound(it, node["exclusiveMinimum"]?.asBoolean() == true) }
        } else {
            strongestLowerBound(
                node.decimalValue("minimum")?.let { SourceSchemaBound(it, false) },
                node.decimalValue("exclusiveMinimum")?.let { SourceSchemaBound(it, true) },
            )
        }

    private fun readMaximum(
        node: JsonNode,
        version: OpenApiVersion?,
    ): SourceSchemaBound? =
        if (version.isOpenApi30()) {
            node.decimalValue("maximum")?.let { SourceSchemaBound(it, node["exclusiveMaximum"]?.asBoolean() == true) }
        } else {
            strongestUpperBound(
                node.decimalValue("maximum")?.let { SourceSchemaBound(it, false) },
                node.decimalValue("exclusiveMaximum")?.let { SourceSchemaBound(it, true) },
            )
        }

    private fun strongestLowerBound(
        inclusive: SourceSchemaBound?,
        exclusive: SourceSchemaBound?,
    ): SourceSchemaBound? =
        listOfNotNull(inclusive, exclusive).maxWithOrNull(compareBy<SourceSchemaBound> { it.value }.thenBy { it.exclusive })

    private fun strongestUpperBound(
        inclusive: SourceSchemaBound?,
        exclusive: SourceSchemaBound?,
    ): SourceSchemaBound? =
        listOfNotNull(inclusive, exclusive).minWithOrNull(compareBy<SourceSchemaBound> { it.value }.thenByDescending { it.exclusive })

    private fun readDependentRequired(node: JsonNode?): Map<String, Set<String>> =
        node
            ?.takeIf(JsonNode::isObject)
            ?.properties()
            ?.asSequence()
            ?.associate { (name, required) ->
                name to readStringSet(required)
            }.orEmpty()

    private fun readDiscriminator(node: JsonNode?): SourceSchemaDiscriminator? {
        val propertyName = node?.textValue("propertyName") ?: return null
        val mapping =
            node["mapping"]
                ?.takeIf(JsonNode::isObject)
                ?.properties()
                ?.asSequence()
                ?.mapNotNull { (name, target) ->
                    target.takeIf(JsonNode::isTextual)?.textValue()?.let { name to it }
                }?.toMap()
                .orEmpty()
        return SourceSchemaDiscriminator(propertyName, mapping)
    }

    private fun readStringSet(node: JsonNode?): Set<String> =
        node
            ?.takeIf(JsonNode::isArray)
            ?.asSequence()
            ?.filter(JsonNode::isTextual)
            ?.map(JsonNode::textValue)
            ?.toCollection(linkedSetOf())
            ?: emptySet()

    private fun JsonNode.textValue(fieldName: String): String? = get(fieldName)?.takeIf(JsonNode::isTextual)?.textValue()

    private fun JsonNode.decimalValue(fieldName: String): BigDecimal? = get(fieldName)?.takeIf(JsonNode::isNumber)?.decimalValue()

    private fun JsonNode.intValue(fieldName: String): Int? =
        get(fieldName)?.takeIf(JsonNode::isIntegralNumber)?.takeIf(JsonNode::canConvertToInt)?.intValue()

    private fun OpenApiVersion?.isOpenApi30(): Boolean = this?.major == 3 && minor == 0

    private fun String.toJsonPointerToken(): String = replace("~", "~0").replace("/", "~1")
}
