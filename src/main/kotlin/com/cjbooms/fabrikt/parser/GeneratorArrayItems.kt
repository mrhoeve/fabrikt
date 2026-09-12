package com.cjbooms.fabrikt.parser

internal sealed interface GeneratorArrayItems {
    data object Unconstrained : GeneratorArrayItems

    data class Homogeneous(
        val items: GeneratorSchema,
    ) : GeneratorArrayItems

    data class Tuple(
        val prefixItems: List<GeneratorSchema>,
        val additionalItems: GeneratorSchema?,
    ) : GeneratorArrayItems
}

internal fun GeneratorObjectSchema.arrayItems(): GeneratorArrayItems =
    when {
        prefixItems.isNotEmpty() -> GeneratorArrayItems.Tuple(prefixItems, items ?: unevaluatedItems)
        items != null -> GeneratorArrayItems.Homogeneous(requireNotNull(items))
        unevaluatedItems != null -> GeneratorArrayItems.Homogeneous(requireNotNull(unevaluatedItems))
        else -> GeneratorArrayItems.Unconstrained
    }
