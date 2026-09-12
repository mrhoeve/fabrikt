package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.YamlUtils
import com.fasterxml.jackson.databind.JsonNode
import com.reprezen.jsonoverlay.Overlay
import com.reprezen.kaizen.oasparser.model3.Schema
import java.math.BigDecimal
import java.util.IdentityHashMap

internal class LegacyGeneratorSchemaAdapter {
    private val schemas = IdentityHashMap<Schema, LegacyObjectSchema>()

    fun adapt(schema: Schema): GeneratorObjectSchema = schemas.getOrPut(schema) { LegacyObjectSchema(schema) }

    private inner class LegacyObjectSchema(
        private val schema: Schema,
    ) : GeneratorObjectSchema {
        override val location: String = Overlay.of(schema).pathFromRoot
        override val identity = GeneratorSchemaIdentity()
        override val identifier: String? = null
        override val anchor: String? = null
        override val types: Set<SourceSchemaType>
            get() =
                buildSet {
                    schema.type?.let { add(SourceSchemaType.from(it)) }
                    if (schema.isNullable) add(SourceSchemaType.NULL)
                }
        override val reference: String? = null
        override val canonicalReference: String = Overlay.of(schema).jsonReference
        override val metadata: SourceSchemaMetadata
            get() =
                SourceSchemaMetadata(
                    title = schema.title,
                    description = schema.description,
                    format = schema.format,
                    defaultValue = schema.default?.toJsonNode(),
                    examples =
                        schema.example
                            ?.toJsonNode()
                            ?.let(::listOf)
                            .orEmpty(),
                    enumValues = schema.enums.map { it.toJsonNode() },
                    constValue = null,
                    readOnly = schema.isReadOnly,
                    writeOnly = schema.isWriteOnly,
                    deprecated = schema.isDeprecated,
                    contentEncoding = null,
                    contentMediaType = null,
                    extensions = schema.extensions.mapValues { (_, value) -> value.toJsonNode() },
                )
        override val constraints: SourceSchemaConstraints
            get() =
                SourceSchemaConstraints(
                    multipleOf = schema.multipleOf?.toBigDecimal(),
                    minimum = schema.minimum?.toBigDecimal()?.let { SourceSchemaBound(it, schema.isExclusiveMinimum) },
                    maximum = schema.maximum?.toBigDecimal()?.let { SourceSchemaBound(it, schema.isExclusiveMaximum) },
                    minLength = schema.minLength,
                    maxLength = schema.maxLength,
                    pattern = schema.pattern,
                    minItems = schema.minItems,
                    maxItems = schema.maxItems,
                    uniqueItems = schema.isUniqueItems,
                    minContains = null,
                    maxContains = null,
                    minProperties = schema.minProperties,
                    maxProperties = schema.maxProperties,
                )
        override val requiredProperties: Set<String>
            get() = schema.requiredFields.toCollection(linkedSetOf())
        override val dependentRequired: Map<String, Set<String>> = emptyMap()
        override val discriminator: SourceSchemaDiscriminator?
            get() =
                schema.discriminator?.propertyName?.let { propertyName ->
                    SourceSchemaDiscriminator(propertyName, schema.discriminator.mappings.orEmpty())
                }
        override val definitions: Map<String, GeneratorSchema> = emptyMap()
        override val properties: Map<String, GeneratorSchema>
            get() = schema.properties.mapValues { (_, child) -> adapt(child) }
        override val patternProperties: Map<String, GeneratorSchema> = emptyMap()
        override val dependentSchemas: Map<String, GeneratorSchema> = emptyMap()
        override val prefixItems: List<GeneratorSchema> = emptyList()
        override val items: GeneratorSchema?
            get() = schema.itemsSchema.takeIfPresent()?.let(::adapt)
        override val contains: GeneratorSchema? = null
        override val propertyNames: GeneratorSchema? = null
        override val ifSchema: GeneratorSchema? = null
        override val thenSchema: GeneratorSchema? = null
        override val elseSchema: GeneratorSchema? = null
        override val allOf: List<GeneratorSchema>
            get() = schema.allOfSchemas.map(::adapt)
        override val anyOf: List<GeneratorSchema>
            get() = schema.anyOfSchemas.map(::adapt)
        override val oneOf: List<GeneratorSchema>
            get() = schema.oneOfSchemas.map(::adapt)
        override val not: GeneratorSchema?
            get() = schema.notSchema.takeIfPresent()?.let(::adapt)
        override val additionalProperties: GeneratorSchema?
            get() =
                schema.additionalProperties?.let(::LegacyBooleanSchema)
                    ?: schema.additionalPropertiesSchema.takeIfPresent()?.let(::adapt)
        override val unevaluatedItems: GeneratorSchema? = null
        override val unevaluatedProperties: GeneratorSchema? = null
        override val contentSchema: GeneratorSchema? = null
    }

    private class LegacyBooleanSchema(
        override val allowsAnyValue: Boolean,
    ) : GeneratorBooleanSchema {
        override val location: String = ""
        override val identity = GeneratorSchemaIdentity()
    }

    private fun Schema?.takeIfPresent(): Schema? = this?.takeIf { Overlay.of(it).isPresent }

    private fun Number.toBigDecimal(): BigDecimal = this as? BigDecimal ?: BigDecimal(toString())

    private fun Any?.toJsonNode(): JsonNode = YamlUtils.objectMapper.valueToTree(this)
}
