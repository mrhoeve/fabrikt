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
        val registeredModelNames =
            buildMap {
                modelSchemas.forEach { model -> putIfAbsent(model.schema.identity, model.name) }
            }
        val typeResolver = GeneratorKotlinTypeResolver(document, registeredModelNames)
        return modelSchemas.map { model -> model.schema.toDescriptor(model.name, document, typeResolver) }
    }

    private fun GeneratorSchema.toDescriptor(
        name: String,
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
    ): GeneratorModelDescriptor {
        val resolvedSchema = document.resolve(this)
        val kotlinType =
            when (val resolution = typeResolver.resolve(resolvedSchema)) {
                is GeneratorKotlinTypeResolution.Resolved ->
                    if (resolution.typeInfo is KotlinTypeInfo.Enum) {
                        resolution.copy(typeInfo = resolution.typeInfo.copy(enumClassName = name))
                    } else {
                        resolution
                    }
                is GeneratorKotlinTypeResolution.Unsupported -> resolution
            }
        return GeneratorModelDescriptor(
            name = name,
            schemaIdentity = resolvedSchema.identity,
            classification = GeneratorSchemaTypeClassifier.classify(resolvedSchema),
            kotlinType = kotlinType,
            description = (resolvedSchema as? GeneratorObjectSchema)?.metadata?.description,
            properties = resolvedSchema.properties(name, document, typeResolver),
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

    private fun collectModelSchemas(document: GeneratorSchemaDocument): List<RegisteredModel> {
        val models = mutableListOf<RegisteredModel>()
        val visited = mutableSetOf<VisitKey>()

        fun register(
            name: String,
            schema: GeneratorSchema,
        ) {
            if (models.none { it.name == name && it.schema.identity == schema.identity }) {
                models.add(RegisteredModel(name, schema))
            }
        }

        fun visit(
            schema: GeneratorSchema,
            suggestedName: String,
            rootName: String,
        ) {
            val resolved = document.resolve(schema)
            val objectSchema = resolved as? GeneratorObjectSchema ?: return
            val componentName =
                objectSchema.canonicalReference
                    .substringAfter("#/components/schemas/", missingDelimiterValue = "")
                    .takeIf { it.isNotEmpty() && '/' !in it }
            val name =
                if ((schema as? GeneratorObjectSchema)?.reference != null || componentName != null) {
                    (componentName ?: objectSchema.canonicalReference.substringAfterLast('/')).toModelClassName()
                } else {
                    suggestedName
                }
            if (objectSchema.requiresGeneratedModel()) register(name, resolved)
            if (!visited.add(VisitKey(resolved.identity, name, rootName))) return

            val parentName =
                if ((schema as? GeneratorObjectSchema)?.reference != null) {
                    name
                } else {
                    models.firstOrNull { it.schema.identity == resolved.identity && it.name == name }?.name ?: suggestedName
                }
            objectSchema.properties.forEach { (propertyName, property) ->
                visit(property, rootName + propertyName.toModelClassName(), rootName)
            }
            objectSchema.items?.let { items ->
                val itemType =
                    GeneratorSchemaTypeClassifier.classify(
                        document.resolve(items),
                    ) as? GeneratorSchemaTypeClassification.Resolved
                val itemName =
                    if ((schema as? GeneratorObjectSchema)?.reference != null && itemType?.type == OasType.Enum && name != rootName) {
                        rootName + name
                    } else {
                        parentName
                    }
                visit(items, itemName, rootName)
            }
            objectSchema.prefixItems.forEachIndexed { index, item -> visit(item, parentName + "Item${index + 1}", rootName) }
            listOf(objectSchema.allOf, objectSchema.oneOf, objectSchema.anyOf).forEach { members ->
                members.forEachIndexed { index, member -> visit(member, parentName + "Option${index + 1}", rootName) }
            }
            objectSchema.additionalProperties?.let { additionalProperties ->
                val containerName =
                    objectSchema.location
                        .substringBeforeLast("/additionalProperties")
                        .substringAfterLast('/')
                        .replace("~1", "-")
                        .replace("~0", "~")
                        .toModelClassName()
                visit(additionalProperties, containerName + "Value", rootName)
            }
        }

        document.componentSchemas.forEach { (name, schema) ->
            val resolved = document.resolve(schema)
            if ((resolved as? GeneratorObjectSchema)?.requiresGeneratedModel() == true) {
                register(name, resolved)
            }
        }
        document.componentSchemas.forEach { (name, schema) -> visit(schema, name, name) }
        return models
    }

    private data class RegisteredModel(
        val name: String,
        val schema: GeneratorSchema,
    )

    private data class VisitKey(
        val identity: GeneratorSchemaIdentity,
        val name: String,
        val rootName: String,
    )

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
        modelName: String,
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
    ): List<GeneratorPropertyDescriptor> = properties(modelName, document, typeResolver, mutableSetOf())

    private fun GeneratorSchema.properties(
        modelName: String,
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
        visited: MutableSet<GeneratorSchemaIdentity>,
    ): List<GeneratorPropertyDescriptor> {
        val resolvedSchema = document.resolve(this)
        if (!visited.add(resolvedSchema.identity)) return emptyList()
        val objectSchema = resolvedSchema as? GeneratorObjectSchema ?: return emptyList()
        val properties = linkedMapOf<String, GeneratorPropertyDescriptor>()
        objectSchema.allOf.forEach { member ->
            member.properties(modelName, document, typeResolver, visited).forEach { properties[it.name] = it }
        }
        objectSchema.ownProperties(modelName, document, typeResolver).forEach { properties[it.name] = it }
        return properties.values.toList()
    }

    private fun GeneratorObjectSchema.ownProperties(
        modelName: String,
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
                kotlinType = typeResolver.resolveProperty(propertySchema, modelName),
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
