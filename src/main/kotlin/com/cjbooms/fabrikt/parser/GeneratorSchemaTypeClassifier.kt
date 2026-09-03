package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType

internal sealed interface GeneratorSchemaTypeClassification {
    data object Uninhabitable : GeneratorSchemaTypeClassification

    data class Resolved(
        val type: OasType,
        val nullable: Boolean,
    ) : GeneratorSchemaTypeClassification

    data class MultiType(
        val types: Set<OasType>,
        val nullable: Boolean,
    ) : GeneratorSchemaTypeClassification

    data class Unsupported(
        val reason: UnsupportedReason,
    ) : GeneratorSchemaTypeClassification

    enum class UnsupportedReason {
        INCONSISTENT_COMPOSITION_TYPES,
    }
}

internal object GeneratorSchemaTypeClassifier {
    fun classify(
        schema: GeneratorSchema,
        resolve: (GeneratorSchema) -> GeneratorSchema = { it },
    ): GeneratorSchemaTypeClassification =
        when (schema) {
            is GeneratorBooleanSchema ->
                if (schema.allowsAnyValue) {
                    GeneratorSchemaTypeClassification.Resolved(OasType.Any, false)
                } else {
                    GeneratorSchemaTypeClassification.Uninhabitable
                }
            is GeneratorObjectSchema -> classifyObjectSchema(schema, resolve)
            else -> error("Unknown generator schema implementation: ${schema::class.qualifiedName}")
        }

    private fun classifyObjectSchema(
        schema: GeneratorObjectSchema,
        resolve: (GeneratorSchema) -> GeneratorSchema,
    ): GeneratorSchemaTypeClassification {
        if (schema.allOf.any { classify(resolve(it), resolve) is GeneratorSchemaTypeClassification.Uninhabitable }) {
            return GeneratorSchemaTypeClassification.Uninhabitable
        }
        if (schema.anyOf.isNotEmpty() &&
            schema.anyOf.all { classify(resolve(it), resolve) is GeneratorSchemaTypeClassification.Uninhabitable }
        ) {
            return GeneratorSchemaTypeClassification.Uninhabitable
        }
        if (schema.oneOf.isNotEmpty() &&
            schema.oneOf.all { classify(resolve(it), resolve) is GeneratorSchemaTypeClassification.Uninhabitable }
        ) {
            return GeneratorSchemaTypeClassification.Uninhabitable
        }

        val nullable = SourceSchemaType.NULL in schema.types
        val nonNullTypes = schema.types - SourceSchemaType.NULL
        if (nonNullTypes.size > 1) {
            return GeneratorSchemaTypeClassification.MultiType(
                types = nonNullTypes.mapTo(linkedSetOf()) { schema.toOasType(it) },
                nullable = nullable,
            )
        }

        val type = nonNullTypes.singleOrNull() ?: inferType(schema, resolve)
        if (type == null && schema.hasInconsistentCompositionTypes(resolve)) {
            return GeneratorSchemaTypeClassification.Unsupported(
                GeneratorSchemaTypeClassification.UnsupportedReason.INCONSISTENT_COMPOSITION_TYPES,
            )
        }

        return GeneratorSchemaTypeClassification.Resolved(schema.toOasType(type), nullable)
    }

    private fun inferType(
        schema: GeneratorObjectSchema,
        resolve: (GeneratorSchema) -> GeneratorSchema,
    ): SourceSchemaType? {
        if (schema.properties.isNotEmpty() || schema.hasAdditionalProperties()) return SourceSchemaType.OBJECT
        if (schema.items != null || schema.prefixItems.isNotEmpty()) return SourceSchemaType.ARRAY

        return schema
            .compositionSchemas()
            .mapNotNull { (classify(resolve(it), resolve) as? GeneratorSchemaTypeClassification.Resolved)?.type?.type }
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

    private fun GeneratorObjectSchema.hasInconsistentCompositionTypes(resolve: (GeneratorSchema) -> GeneratorSchema): Boolean {
        val schemas = compositionSchemas().toList()
        if (schemas.isEmpty()) return false
        val types =
            schemas
                .mapNotNull { (classify(resolve(it), resolve) as? GeneratorSchemaTypeClassification.Resolved)?.type?.type }
                .distinct()
        return types.size > 1
    }

    private fun GeneratorObjectSchema.compositionSchemas(): Sequence<GeneratorSchema> =
        sequence {
            yieldAll(allOf)
            yieldAll(anyOf)
            yieldAll(oneOf)
        }
}
