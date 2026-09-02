package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.parser.GeneratorBooleanSchema
import com.cjbooms.fabrikt.parser.GeneratorObjectSchema
import com.cjbooms.fabrikt.parser.GeneratorSchema
import com.cjbooms.fabrikt.parser.GeneratorSchemaDocument
import com.cjbooms.fabrikt.parser.GeneratorSchemaIdentity
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassifier
import com.cjbooms.fabrikt.parser.SourceSchemaConstraints
import com.cjbooms.fabrikt.parser.SourceSchemaDiscriminator
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.fasterxml.jackson.databind.JsonNode

internal data class GeneratorModelDescriptor(
    val name: String,
    val schemaIdentity: GeneratorSchemaIdentity,
    val classification: GeneratorSchemaTypeClassification,
    val kotlinType: GeneratorKotlinTypeResolution,
    val description: String?,
    val properties: List<GeneratorPropertyDescriptor>,
    val oneOfMembers: List<GeneratorUnionMemberDescriptor>,
    val anyOfMembers: List<GeneratorUnionMemberDescriptor>,
    val discriminator: SourceSchemaDiscriminator?,
    val additionalPropertiesType: GeneratorKotlinTypeResolution.Resolved?,
)

internal data class GeneratorUnionMemberDescriptor(
    val schemaIdentity: GeneratorSchemaIdentity,
    val kotlinType: GeneratorKotlinTypeResolution.Resolved,
    val canonicalReference: String?,
)

internal data class GeneratorPropertyDescriptor(
    val name: String,
    val schemaIdentity: GeneratorSchemaIdentity,
    val classification: GeneratorSchemaTypeClassification,
    val kotlinType: GeneratorKotlinTypeResolution,
    val required: Boolean,
    val readOnly: Boolean,
    val writeOnly: Boolean,
    val deprecated: Boolean,
    val description: String?,
    val defaultValue: JsonNode?,
    val constraints: SourceSchemaConstraints?,
)

internal object GeneratorModelDescriptorBuilder {
    fun build(document: GeneratorSchemaDocument): List<GeneratorModelDescriptor> {
        val modelSchemas = collectModelSchemas(document)
        val typeResolver = GeneratorKotlinTypeResolver(document, modelSchemas.mapValues { it.value.first })
        return modelSchemas.values.map { (name, schema) -> schema.toDescriptor(name, document, typeResolver) }
    }

    private fun GeneratorSchema.toDescriptor(
        name: String,
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
    ): GeneratorModelDescriptor {
        val resolvedSchema = document.resolve(this)
        return GeneratorModelDescriptor(
            name = name,
            schemaIdentity = resolvedSchema.identity,
            classification = GeneratorSchemaTypeClassifier.classify(resolvedSchema),
            kotlinType = typeResolver.resolve(resolvedSchema),
            description = (resolvedSchema as? GeneratorObjectSchema)?.metadata?.description,
            properties = resolvedSchema.properties(document, typeResolver),
            oneOfMembers = resolvedSchema.unionMembers(document, typeResolver) { it.oneOf },
            anyOfMembers = resolvedSchema.unionMembers(document, typeResolver) { it.anyOf },
            discriminator = (resolvedSchema as? GeneratorObjectSchema)?.discriminator,
            additionalPropertiesType =
                (resolvedSchema as? GeneratorObjectSchema)
                    ?.additionalProperties
                    ?.takeUnless { it is GeneratorBooleanSchema && !it.allowsAnyValue }
                    ?.let(typeResolver::resolve)
                    ?.let { it as? GeneratorKotlinTypeResolution.Resolved }
                    ?.let { resolution ->
                        if (resolution.typeInfo is KotlinTypeInfo.UntypedObject) {
                            resolution.copy(typeInfo = KotlinTypeInfo.AnyType)
                        } else {
                            resolution
                        }
                    },
        )
    }

    private fun collectModelSchemas(document: GeneratorSchemaDocument): Map<GeneratorSchemaIdentity, Pair<String, GeneratorSchema>> {
        val models = linkedMapOf<GeneratorSchemaIdentity, Pair<String, GeneratorSchema>>()
        val visited = mutableSetOf<GeneratorSchemaIdentity>()

        fun visit(
            schema: GeneratorSchema,
            suggestedName: String,
        ) {
            val resolved = document.resolve(schema)
            val objectSchema = resolved as? GeneratorObjectSchema ?: return
            val name =
                if ((schema as? GeneratorObjectSchema)?.reference != null) {
                    objectSchema.canonicalReference.substringAfterLast('/').toModelClassName()
                } else {
                    suggestedName
                }
            if (objectSchema.requiresGeneratedModel()) models.putIfAbsent(resolved.identity, name to resolved)
            if (!visited.add(resolved.identity)) return

            val parentName = models[resolved.identity]?.first ?: suggestedName
            objectSchema.properties.forEach { (propertyName, property) ->
                visit(property, parentName + propertyName.toModelClassName())
            }
            objectSchema.items?.let { visit(it, parentName) }
            objectSchema.prefixItems.forEachIndexed { index, item -> visit(item, parentName + "Item${index + 1}") }
            listOf(objectSchema.allOf, objectSchema.oneOf, objectSchema.anyOf).forEach { members ->
                members.forEachIndexed { index, member -> visit(member, parentName + "Option${index + 1}") }
            }
            objectSchema.additionalProperties?.let { additionalProperties ->
                val containerName =
                    objectSchema.location
                        .substringBeforeLast("/additionalProperties")
                        .substringAfterLast('/')
                        .replace("~1", "-")
                        .replace("~0", "~")
                        .toModelClassName()
                visit(additionalProperties, containerName + "Value")
            }
        }

        document.componentSchemas.forEach { (name, schema) ->
            val resolved = document.resolve(schema)
            if ((resolved as? GeneratorObjectSchema)?.requiresGeneratedModel() == true) {
                models.putIfAbsent(resolved.identity, name to resolved)
            }
        }
        document.componentSchemas.forEach { (name, schema) -> visit(schema, name) }
        return models
    }

    private fun GeneratorObjectSchema.requiresGeneratedModel(): Boolean =
        metadata.enumValues.isNotEmpty() || properties.isNotEmpty() || allOf.isNotEmpty() || oneOf.isNotEmpty() || anyOf.isNotEmpty()

    private fun GeneratorSchema.unionMembers(
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
        selector: (GeneratorObjectSchema) -> List<GeneratorSchema>,
    ): List<GeneratorUnionMemberDescriptor> {
        val objectSchema = this as? GeneratorObjectSchema ?: return emptyList()
        return selector(objectSchema).mapNotNull { member ->
            val resolvedMember = document.resolve(member)
            val type = typeResolver.resolve(resolvedMember) as? GeneratorKotlinTypeResolution.Resolved ?: return@mapNotNull null
            GeneratorUnionMemberDescriptor(
                schemaIdentity = resolvedMember.identity,
                kotlinType = type,
                canonicalReference = (resolvedMember as? GeneratorObjectSchema)?.canonicalReference,
            )
        }
    }

    private fun GeneratorSchema.properties(
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
    ): List<GeneratorPropertyDescriptor> = properties(document, typeResolver, mutableSetOf())

    private fun GeneratorSchema.properties(
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
        visited: MutableSet<GeneratorSchemaIdentity>,
    ): List<GeneratorPropertyDescriptor> {
        val resolvedSchema = document.resolve(this)
        if (!visited.add(resolvedSchema.identity)) return emptyList()
        val objectSchema = resolvedSchema as? GeneratorObjectSchema ?: return emptyList()
        val properties = linkedMapOf<String, GeneratorPropertyDescriptor>()
        objectSchema.allOf.forEach { member ->
            member.properties(document, typeResolver, visited).forEach { properties[it.name] = it }
        }
        objectSchema.ownProperties(document, typeResolver).forEach { properties[it.name] = it }
        return properties.values.toList()
    }

    private fun GeneratorObjectSchema.ownProperties(
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
    ): List<GeneratorPropertyDescriptor> =
        properties.map { (name, propertySchema) ->
            val resolvedProperty = document.resolve(propertySchema)
            val property = resolvedProperty as? GeneratorObjectSchema
            GeneratorPropertyDescriptor(
                name = name,
                schemaIdentity = resolvedProperty.identity,
                classification = GeneratorSchemaTypeClassifier.classify(resolvedProperty),
                kotlinType = typeResolver.resolve(resolvedProperty),
                required = name in requiredProperties,
                readOnly = property?.metadata?.readOnly == true,
                writeOnly = property?.metadata?.writeOnly == true,
                deprecated = property?.metadata?.deprecated == true,
                description = property?.metadata?.description,
                defaultValue = property?.metadata?.defaultValue,
                constraints = property?.constraints,
            )
        }
}
