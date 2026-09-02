package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType

internal sealed interface GeneratorSchemaTypeClassification {
    data class Resolved(
        val type: OasType,
        val nullable: Boolean,
    ) : GeneratorSchemaTypeClassification

    data class Unsupported(
        val reason: Reason,
    ) : GeneratorSchemaTypeClassification

    enum class Reason {
        NEVER_SCHEMA,
        MULTIPLE_NON_NULL_TYPES,
        INCONSISTENT_COMPOSITION_TYPES,
    }
}

internal object GeneratorSchemaTypeClassifier {
    fun classify(schema: GeneratorSchema): GeneratorSchemaTypeClassification =
        when (schema) {
            is GeneratorBooleanSchema ->
                if (schema.allowsAnyValue) {
                    GeneratorSchemaTypeClassification.Resolved(OasType.Any, false)
                } else {
                    GeneratorSchemaTypeClassification.Unsupported(GeneratorSchemaTypeClassification.Reason.NEVER_SCHEMA)
                }
            is GeneratorObjectSchema -> classifyObjectSchema(schema)
            else -> error("Unknown generator schema implementation: ${schema::class.qualifiedName}")
        }

    private fun classifyObjectSchema(schema: GeneratorObjectSchema): GeneratorSchemaTypeClassification {
        val nullable = SourceSchemaType.NULL in schema.types
        val nonNullTypes = schema.types - SourceSchemaType.NULL
        if (nonNullTypes.size > 1) {
            return GeneratorSchemaTypeClassification.Unsupported(
                GeneratorSchemaTypeClassification.Reason.MULTIPLE_NON_NULL_TYPES,
            )
        }

        val type = nonNullTypes.singleOrNull() ?: inferType(schema)
        if (type == null && schema.hasInconsistentCompositionTypes()) {
            return GeneratorSchemaTypeClassification.Unsupported(
                GeneratorSchemaTypeClassification.Reason.INCONSISTENT_COMPOSITION_TYPES,
            )
        }

        return GeneratorSchemaTypeClassification.Resolved(schema.toOasType(type), nullable)
    }

    private fun inferType(schema: GeneratorObjectSchema): SourceSchemaType? {
        if (schema.properties.isNotEmpty() || schema.hasAdditionalProperties()) return SourceSchemaType.OBJECT
        if (schema.items != null || schema.prefixItems.isNotEmpty()) return SourceSchemaType.ARRAY

        return schema
            .compositionSchemas()
            .mapNotNull { (classify(it) as? GeneratorSchemaTypeClassification.Resolved)?.type?.type }
            .distinct()
            .singleOrNull()
            ?.let(SourceSchemaType::from)
    }

    private fun GeneratorObjectSchema.toOasType(type: SourceSchemaType?): OasType =
        when (type) {
            SourceSchemaType.STRING -> classifyString()
            SourceSchemaType.NUMBER ->
                when (metadata.format?.lowercase()) {
                    "float" -> OasType.Float
                    "double" -> OasType.Double
                    else -> OasType.Number
                }
            SourceSchemaType.INTEGER ->
                when (metadata.format?.lowercase()) {
                    "int32" -> OasType.Int32
                    "int64" -> OasType.Int64
                    else -> OasType.Integer
                }
            SourceSchemaType.BOOLEAN -> OasType.Boolean
            SourceSchemaType.ARRAY -> if (constraints.uniqueItems) OasType.Set else OasType.Array
            SourceSchemaType.OBJECT -> classifyObject()
            SourceSchemaType.NULL, null -> OasType.Any
            else -> OasType.Any
        }

    private fun GeneratorObjectSchema.classifyString(): OasType =
        when {
            metadata.enumValues.isNotEmpty() -> OasType.Enum
            metadata.format.equals("date", ignoreCase = true) -> OasType.Date
            metadata.format.equals("date-time", ignoreCase = true) -> OasType.DateTime
            metadata.format.equals("uuid", ignoreCase = true) -> OasType.Uuid
            metadata.format.equals("uri", ignoreCase = true) -> OasType.Uri
            metadata.format.equals("byte", ignoreCase = true) -> OasType.Base64String
            metadata.format.equals("binary", ignoreCase = true) -> OasType.Binary
            else -> OasType.Text
        }

    private fun GeneratorObjectSchema.classifyObject(): OasType =
        when {
            properties.isEmpty() && hasAdditionalProperties() -> OasType.Map
            properties.isEmpty() && additionalProperties == null && compositionSchemas().none() -> OasType.UntypedObject
            else -> OasType.Object
        }

    private fun GeneratorObjectSchema.hasAdditionalProperties(): Boolean =
        when (val value = additionalProperties) {
            null -> false
            is GeneratorBooleanSchema -> value.allowsAnyValue
            else -> true
        }

    private fun GeneratorObjectSchema.hasInconsistentCompositionTypes(): Boolean {
        val schemas = compositionSchemas().toList()
        if (schemas.isEmpty()) return false
        val types = schemas.mapNotNull { (classify(it) as? GeneratorSchemaTypeClassification.Resolved)?.type?.type }.distinct()
        return types.size > 1
    }

    private fun GeneratorObjectSchema.compositionSchemas(): Sequence<GeneratorSchema> =
        sequence {
            yieldAll(allOf)
            yieldAll(anyOf)
            yieldAll(oneOf)
        }
}
