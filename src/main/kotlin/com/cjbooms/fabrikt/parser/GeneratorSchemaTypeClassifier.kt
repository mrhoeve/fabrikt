package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType
import com.fasterxml.jackson.databind.JsonNode

internal sealed interface GeneratorSchemaTypeClassification {
    sealed interface Fallback : GeneratorSchemaTypeClassification {
        val types: Set<OasType>
        val nullable: Boolean
    }

    data class Resolved(
        val type: OasType,
        val nullable: Boolean,
    ) : GeneratorSchemaTypeClassification

    data class MultiType(
        override val types: Set<OasType>,
        override val nullable: Boolean,
    ) : Fallback

    data class CompositionUnion(
        override val types: Set<OasType>,
        override val nullable: Boolean,
    ) : Fallback

    data class ValueUnion(
        override val types: Set<OasType>,
        override val nullable: Boolean,
    ) : Fallback

    data class Unsupported(
        val reason: Reason,
    ) : GeneratorSchemaTypeClassification

    enum class Reason {
        NEVER_SCHEMA,
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
                    GeneratorSchemaTypeClassification.Unsupported(GeneratorSchemaTypeClassification.Reason.NEVER_SCHEMA)
                }
            is GeneratorObjectSchema -> classifyObjectSchema(schema, resolve)
            else -> error("Unknown generator schema implementation: ${schema::class.qualifiedName}")
        }

    private fun classifyObjectSchema(
        schema: GeneratorObjectSchema,
        resolve: (GeneratorSchema) -> GeneratorSchema,
    ): GeneratorSchemaTypeClassification {
        val valueConstraint = schema.valueConstraint()
        if (valueConstraint is GeneratorSchemaValueConstraint.Impossible) {
            return GeneratorSchemaTypeClassification.Unsupported(GeneratorSchemaTypeClassification.Reason.NEVER_SCHEMA)
        }
        val allowedValues = (valueConstraint as? GeneratorSchemaValueConstraint.Allowed)?.values
        val constrainedTypes = allowedValues?.mapTo(linkedSetOf(), JsonNode::sourceSchemaType)?.normaliseNumericTypes()
        val effectiveTypes =
            if (allowedValues != null && schema.types.isNotEmpty()) {
                schema.types
                    .filterTo(linkedSetOf()) { type -> allowedValues.any { it.matchesSourceSchemaType(type) } }
                    .normaliseNumericTypes()
            } else {
                constrainedTypes ?: schema.types
            }
        val nullable = SourceSchemaType.NULL in effectiveTypes
        val nonNullTypes = effectiveTypes - SourceSchemaType.NULL
        if (nonNullTypes.size > 1) {
            val types = nonNullTypes.mapTo(linkedSetOf()) { schema.toOasType(it, constrainedStringAsEnum = false) }
            return if (constrainedTypes == null) {
                GeneratorSchemaTypeClassification.MultiType(types, nullable)
            } else {
                GeneratorSchemaTypeClassification.ValueUnion(types, nullable)
            }
        }

        if (nonNullTypes.isEmpty()) {
            schema.classifyCompositionUnion(resolve)?.let { return it }
        }

        val type = nonNullTypes.singleOrNull() ?: inferType(schema, resolve)
        if (type == null && schema.hasInconsistentCompositionTypes(resolve)) {
            return GeneratorSchemaTypeClassification.Unsupported(
                GeneratorSchemaTypeClassification.Reason.INCONSISTENT_COMPOSITION_TYPES,
            )
        }

        return GeneratorSchemaTypeClassification.Resolved(schema.toOasType(type), nullable)
    }

    private fun GeneratorObjectSchema.classifyCompositionUnion(
        resolve: (GeneratorSchema) -> GeneratorSchema,
    ): GeneratorSchemaTypeClassification? {
        if (allOf.isNotEmpty() || properties.isNotEmpty() || items != null || prefixItems.isNotEmpty()) return null
        val members =
            when {
                oneOf.isNotEmpty() && anyOf.isEmpty() -> oneOf
                anyOf.isNotEmpty() && oneOf.isEmpty() -> anyOf
                else -> return null
            }
        val types = linkedSetOf<OasType>()
        var nullable = false
        members.forEach { member ->
            when (val memberClassification = classify(resolve(member), resolve)) {
                is GeneratorSchemaTypeClassification.Resolved -> {
                    if (memberClassification.type != OasType.Any || !memberClassification.nullable) {
                        types.add(memberClassification.type)
                    }
                    nullable = nullable || memberClassification.nullable
                }
                is GeneratorSchemaTypeClassification.Fallback -> {
                    types.addAll(memberClassification.types)
                    nullable = nullable || memberClassification.nullable
                }
                is GeneratorSchemaTypeClassification.Unsupported ->
                    if (memberClassification.reason != GeneratorSchemaTypeClassification.Reason.NEVER_SCHEMA) return null
            }
        }
        return when (types.size) {
            0 -> GeneratorSchemaTypeClassification.Resolved(OasType.Any, nullable)
            1 -> GeneratorSchemaTypeClassification.Resolved(types.single(), nullable)
            else -> GeneratorSchemaTypeClassification.CompositionUnion(types, nullable)
        }
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

    private fun GeneratorObjectSchema.toOasType(
        type: SourceSchemaType?,
        constrainedStringAsEnum: Boolean = true,
    ): OasType =
        when (type) {
            SourceSchemaType.STRING -> if (constrainedStringAsEnum) classifyString() else OasType.Text
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
            valueConstraint() is GeneratorSchemaValueConstraint.Allowed -> OasType.Enum
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

    private fun Set<SourceSchemaType>.normaliseNumericTypes(): Set<SourceSchemaType> =
        if (SourceSchemaType.NUMBER in this) {
            this - SourceSchemaType.INTEGER
        } else {
            this
        }
}
