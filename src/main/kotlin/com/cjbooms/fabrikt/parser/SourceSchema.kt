package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

internal sealed interface SourceSchema : GeneratorSchema {
    override val location: String
    val node: JsonNode

    override val identity: GeneratorSchemaIdentity
}

internal data class SourceBooleanSchema(
    override val location: String,
    override val node: JsonNode,
    override val allowsAnyValue: Boolean,
) : SourceSchema,
    GeneratorBooleanSchema {
    override val identity = GeneratorSchemaIdentity()
}

internal data class SourceObjectSchema(
    override val location: String,
    override val node: JsonNode,
    override val identifier: String?,
    override val anchor: String?,
    override val types: Set<SourceSchemaType>,
    override val reference: String?,
    override val metadata: SourceSchemaMetadata,
    override val constraints: SourceSchemaConstraints,
    override val requiredProperties: Set<String>,
    override val dependentRequired: Map<String, Set<String>>,
    override val discriminator: SourceSchemaDiscriminator?,
    override val definitions: Map<String, SourceSchema>,
    override val properties: Map<String, SourceSchema>,
    override val patternProperties: Map<String, SourceSchema>,
    override val dependentSchemas: Map<String, SourceSchema>,
    override val prefixItems: List<SourceSchema>,
    override val items: SourceSchema?,
    override val contains: SourceSchema?,
    override val propertyNames: SourceSchema?,
    override val ifSchema: SourceSchema?,
    override val thenSchema: SourceSchema?,
    override val elseSchema: SourceSchema?,
    override val allOf: List<SourceSchema>,
    override val anyOf: List<SourceSchema>,
    override val oneOf: List<SourceSchema>,
    override val not: SourceSchema?,
    override val additionalProperties: SourceSchema?,
    override val unevaluatedItems: SourceSchema?,
    override val unevaluatedProperties: SourceSchema?,
    override val contentSchema: SourceSchema?,
) : SourceSchema,
    GeneratorObjectSchema {
    override val identity = GeneratorSchemaIdentity()
}

internal data class SourceSchemaMetadata(
    val title: String?,
    val description: String?,
    val format: String?,
    val defaultValue: JsonNode?,
    val examples: List<JsonNode>,
    val enumValues: List<JsonNode>,
    val constValue: JsonNode?,
    val readOnly: Boolean,
    val writeOnly: Boolean,
    val deprecated: Boolean,
    val contentEncoding: String?,
    val contentMediaType: String?,
)

internal data class SourceSchemaConstraints(
    val multipleOf: BigDecimal?,
    val minimum: SourceSchemaBound?,
    val maximum: SourceSchemaBound?,
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: String?,
    val minItems: Int?,
    val maxItems: Int?,
    val uniqueItems: Boolean,
    val minContains: Int?,
    val maxContains: Int?,
    val minProperties: Int?,
    val maxProperties: Int?,
)

internal data class SourceSchemaBound(
    val value: BigDecimal,
    val exclusive: Boolean,
)

internal data class SourceSchemaDiscriminator(
    val propertyName: String,
    val mapping: Map<String, String>,
)

internal fun SourceSchema.childSchemas(): Sequence<SourceSchema> =
    when (this) {
        is SourceBooleanSchema -> emptySequence()
        is SourceObjectSchema ->
            sequence {
                yieldAll(definitions.values)
                yieldAll(properties.values)
                yieldAll(patternProperties.values)
                yieldAll(dependentSchemas.values)
                yieldAll(prefixItems)
                items?.let { yield(it) }
                contains?.let { yield(it) }
                propertyNames?.let { yield(it) }
                ifSchema?.let { yield(it) }
                thenSchema?.let { yield(it) }
                elseSchema?.let { yield(it) }
                yieldAll(allOf)
                yieldAll(anyOf)
                yieldAll(oneOf)
                not?.let { yield(it) }
                additionalProperties?.let { yield(it) }
                unevaluatedItems?.let { yield(it) }
                unevaluatedProperties?.let { yield(it) }
                contentSchema?.let { yield(it) }
            }
    }

internal sealed interface SourceSchemaType {
    val value: String

    enum class Recognised(
        override val value: String,
    ) : SourceSchemaType {
        ARRAY("array"),
        BOOLEAN("boolean"),
        INTEGER("integer"),
        NULL("null"),
        NUMBER("number"),
        OBJECT("object"),
        STRING("string"),
    }

    data class Unrecognised(
        override val value: String,
    ) : SourceSchemaType

    companion object {
        val ARRAY: SourceSchemaType = Recognised.ARRAY
        val BOOLEAN: SourceSchemaType = Recognised.BOOLEAN
        val INTEGER: SourceSchemaType = Recognised.INTEGER
        val NULL: SourceSchemaType = Recognised.NULL
        val NUMBER: SourceSchemaType = Recognised.NUMBER
        val OBJECT: SourceSchemaType = Recognised.OBJECT
        val STRING: SourceSchemaType = Recognised.STRING

        fun from(value: String): SourceSchemaType = Recognised.entries.firstOrNull { it.value == value } ?: Unrecognised(value)
    }
}
