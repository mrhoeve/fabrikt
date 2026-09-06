package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal sealed interface GeneratorSchemaValueConstraint {
    data object Unconstrained : GeneratorSchemaValueConstraint

    data object Impossible : GeneratorSchemaValueConstraint

    data class Allowed(
        val values: List<JsonNode>,
    ) : GeneratorSchemaValueConstraint
}

internal fun GeneratorObjectSchema.valueConstraint(): GeneratorSchemaValueConstraint {
    if (this is GeneratorReferenceSiblingSchema) {
        val referenced =
            (referencedSchema as? GeneratorObjectSchema)?.valueConstraint()
                ?: GeneratorSchemaValueConstraint.Unconstrained
        return referenced.intersect(siblingSchema.valueConstraint())
    }
    val enumValues = metadata.enumValues.takeIf { it.isNotEmpty() }
    val constrainedValues =
        when (val constValue = metadata.constValue) {
            null -> enumValues ?: return GeneratorSchemaValueConstraint.Unconstrained
            else ->
                if (enumValues == null || enumValues.any { it.isJsonValueEqualTo(constValue) }) {
                    listOf(constValue)
                } else {
                    return GeneratorSchemaValueConstraint.Impossible
                }
        }
    val compatibleValues = mutableListOf<JsonNode>()
    constrainedValues.forEach { value ->
        if (
            (types.isEmpty() || types.any { type -> value.matchesSourceSchemaType(type) }) &&
            compatibleValues.none { it.isJsonValueEqualTo(value) }
        ) {
            compatibleValues.add(value)
        }
    }
    return if (compatibleValues.isEmpty()) {
        GeneratorSchemaValueConstraint.Impossible
    } else {
        GeneratorSchemaValueConstraint.Allowed(compatibleValues)
    }
}

private fun GeneratorSchemaValueConstraint.intersect(other: GeneratorSchemaValueConstraint): GeneratorSchemaValueConstraint =
    when {
        this is GeneratorSchemaValueConstraint.Impossible || other is GeneratorSchemaValueConstraint.Impossible ->
            GeneratorSchemaValueConstraint.Impossible
        this is GeneratorSchemaValueConstraint.Unconstrained -> other
        other is GeneratorSchemaValueConstraint.Unconstrained -> this
        this is GeneratorSchemaValueConstraint.Allowed && other is GeneratorSchemaValueConstraint.Allowed -> {
            val intersection = values.filter { value -> other.values.any { it.isJsonValueEqualTo(value) } }
            if (intersection.isEmpty()) GeneratorSchemaValueConstraint.Impossible else GeneratorSchemaValueConstraint.Allowed(intersection)
        }
        else -> GeneratorSchemaValueConstraint.Impossible
    }

internal fun JsonNode.sourceSchemaType(): SourceSchemaType =
    when {
        isNull -> SourceSchemaType.NULL
        isTextual -> SourceSchemaType.STRING
        isBoolean -> SourceSchemaType.BOOLEAN
        isNumber && decimalValue().stripTrailingZeros().scale() <= 0 -> SourceSchemaType.INTEGER
        isNumber -> SourceSchemaType.NUMBER
        isArray -> SourceSchemaType.ARRAY
        isObject -> SourceSchemaType.OBJECT
        else -> SourceSchemaType.Unrecognised(nodeType.name.lowercase())
    }

internal fun JsonNode.matchesSourceSchemaType(type: SourceSchemaType): Boolean =
    when (type) {
        SourceSchemaType.NUMBER -> isNumber
        SourceSchemaType.INTEGER -> isNumber && decimalValue().stripTrailingZeros().scale() <= 0
        else -> sourceSchemaType() == type
    }

internal fun JsonNode.isJsonValueEqualTo(other: JsonNode): Boolean =
    if (isNumber && other.isNumber) {
        decimalValue().compareTo(other.decimalValue()) == 0
    } else {
        this == other
    }
