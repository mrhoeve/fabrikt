package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.generators.MutableSettings

internal class GeneratorDirectionalModelPlan private constructor(
    val descriptors: List<GeneratorModelDescriptor>,
    private val variantNames: Map<VariantKey, String>,
) {
    fun resolve(
        type: GeneratorKotlinTypeResolution.Resolved,
        direction: GeneratorModelDirection,
    ): GeneratorKotlinTypeResolution.Resolved = type.copy(typeInfo = type.typeInfo.forDirection(direction))

    private fun KotlinTypeInfo.forDirection(direction: GeneratorModelDirection): KotlinTypeInfo =
        when (this) {
            is KotlinTypeInfo.Object -> copy(simpleClassName = variantName(simpleClassName, direction))
            is KotlinTypeInfo.Array -> copy(parameterizedType = parameterizedType.forDirection(direction))
            is KotlinTypeInfo.Map -> copy(parameterizedType = parameterizedType.forDirection(direction))
            is KotlinTypeInfo.GeneratedTypedAdditionalProperties ->
                copy(simpleClassName = variantName(simpleClassName, direction))
            is KotlinTypeInfo.SimpleTypedAdditionalProperties -> copy(parameterizedType = parameterizedType.forDirection(direction))
            is KotlinTypeInfo.MapTypeAdditionalProperties -> copy(parameterizedType = parameterizedType.forDirection(direction))
            else -> this
        }

    private fun variantName(
        name: String,
        direction: GeneratorModelDirection,
    ): String = variantNames[VariantKey(name, direction)] ?: name

    companion object {
        fun create(descriptors: Collection<GeneratorModelDescriptor>): GeneratorDirectionalModelPlan {
            val original = descriptors.toList()
            val directionalNames = findDirectionalModelNames(original)
            if (directionalNames.isEmpty()) return GeneratorDirectionalModelPlan(original, emptyMap())

            val allocatedNames = original.mapTo(linkedSetOf(), GeneratorModelDescriptor::name)
            val variantNames =
                buildMap {
                    original
                        .filter { it.name in directionalNames }
                        .forEach { descriptor ->
                            GeneratorModelDirection.entries
                                .filterNot { it == GeneratorModelDirection.COMBINED }
                                .forEach { direction ->
                                    put(VariantKey(descriptor.name, direction), allocateName(descriptor.name, direction, allocatedNames))
                                }
                        }
                }
            val plan = GeneratorDirectionalModelPlan(emptyList(), variantNames)
            val expanded =
                original.flatMap { descriptor ->
                    if (descriptor.name !in directionalNames) {
                        listOf(descriptor)
                    } else {
                        listOf(descriptor) +
                            listOf(GeneratorModelDirection.REQUEST, GeneratorModelDirection.RESPONSE).map { direction ->
                                descriptor.forDirection(direction, plan)
                            }
                    }
                }
            return GeneratorDirectionalModelPlan(expanded, variantNames)
        }

        private fun findDirectionalModelNames(descriptors: List<GeneratorModelDescriptor>): Set<String> {
            val knownNames = descriptors.mapTo(hashSetOf(), GeneratorModelDescriptor::name)
            val directional =
                descriptors
                    .filter { descriptor -> descriptor.properties.any { it.readOnly || it.writeOnly } }
                    .mapTo(mutableSetOf(), GeneratorModelDescriptor::name)
            var changed: Boolean
            do {
                val referencedDirectionalModels =
                    descriptors
                        .filterNot { it.name in directional }
                        .filter { descriptor -> descriptor.referencedModelNames().any(directional::contains) }
                        .map(GeneratorModelDescriptor::name)
                val directionalUnionMembers =
                    descriptors
                        .filter { it.name in directional }
                        .flatMap(GeneratorModelDescriptor::oneOfModelNames)
                        .filter { it in knownNames && it !in directional }
                val discovered = referencedDirectionalModels + directionalUnionMembers
                changed = discovered.isNotEmpty()
                directional.addAll(discovered)
            } while (changed)
            return directional.intersect(knownNames)
        }

        private fun allocateName(
            baseName: String,
            direction: GeneratorModelDirection,
            allocatedNames: MutableSet<String>,
        ): String {
            val suffix = MutableSettings.modelSuffix.takeIf(baseName::endsWith).orEmpty()
            val stem = baseName.removeSuffix(suffix)
            val suggestion = stem + direction.name.lowercase().replaceFirstChar(Char::uppercase) + suffix
            if (allocatedNames.add(suggestion)) return suggestion
            var index = 2
            while (!allocatedNames.add("$suggestion$index")) index++
            return "$suggestion$index"
        }
    }

    private data class VariantKey(
        val name: String,
        val direction: GeneratorModelDirection,
    )
}

private fun GeneratorModelDescriptor.forDirection(
    direction: GeneratorModelDirection,
    plan: GeneratorDirectionalModelPlan,
): GeneratorModelDescriptor =
    copy(
        name =
            plan
                .resolve(GeneratorKotlinTypeResolution.Resolved(KotlinTypeInfo.Object(name), false), direction)
                .typeInfo.generatedModelClassName!!,
        properties =
            properties
                .filterNot { property ->
                    (direction == GeneratorModelDirection.REQUEST && property.readOnly) ||
                        (direction == GeneratorModelDirection.RESPONSE && property.writeOnly)
                }.map { property ->
                    property.copy(kotlinType = property.kotlinType.forDirection(direction, plan))
                },
        oneOfMembers = oneOfMembers.map { it.copy(kotlinType = plan.resolve(it.kotlinType, direction)) },
        anyOfMembers = anyOfMembers.map { it.copy(kotlinType = plan.resolve(it.kotlinType, direction)) },
        additionalPropertiesType = additionalPropertiesType?.let { plan.resolve(it, direction) },
        direction = direction,
    )

private fun GeneratorKotlinTypeResolution.forDirection(
    direction: GeneratorModelDirection,
    plan: GeneratorDirectionalModelPlan,
): GeneratorKotlinTypeResolution =
    when (this) {
        is GeneratorKotlinTypeResolution.Resolved -> plan.resolve(this, direction)
        is GeneratorKotlinTypeResolution.Fallback ->
            copy(
                typeInfo =
                    plan
                        .resolve(GeneratorKotlinTypeResolution.Resolved(typeInfo, nullable), direction)
                        .typeInfo,
            )
        is GeneratorKotlinTypeResolution.Unsupported -> this
    }

private fun GeneratorModelDescriptor.referencedModelNames(): Set<String> =
    buildSet {
        properties.forEach { property -> property.kotlinType.typeInfoOrNull()?.collectModelNames(this) }
        oneOfMembers.forEach { member -> member.kotlinType.typeInfo.collectModelNames(this) }
        anyOfMembers.forEach { member -> member.kotlinType.typeInfo.collectModelNames(this) }
        additionalPropertiesType?.typeInfo?.collectModelNames(this)
    }

private fun GeneratorModelDescriptor.oneOfModelNames(): Set<String> =
    buildSet {
        oneOfMembers.forEach { member -> member.kotlinType.typeInfo.collectModelNames(this) }
    }

private fun GeneratorKotlinTypeResolution.typeInfoOrNull(): KotlinTypeInfo? =
    when (this) {
        is GeneratorKotlinTypeResolution.Resolved -> typeInfo
        is GeneratorKotlinTypeResolution.Fallback -> typeInfo
        is GeneratorKotlinTypeResolution.Unsupported -> null
    }

private fun KotlinTypeInfo.collectModelNames(names: MutableSet<String>) {
    when (this) {
        is KotlinTypeInfo.Object -> names.add(simpleClassName)
        is KotlinTypeInfo.Array -> parameterizedType.collectModelNames(names)
        is KotlinTypeInfo.Map -> parameterizedType.collectModelNames(names)
        is KotlinTypeInfo.GeneratedTypedAdditionalProperties -> names.add(simpleClassName)
        is KotlinTypeInfo.SimpleTypedAdditionalProperties -> parameterizedType.collectModelNames(names)
        is KotlinTypeInfo.MapTypeAdditionalProperties -> parameterizedType.collectModelNames(names)
        else -> Unit
    }
}
