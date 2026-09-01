package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.parser.GeneratorObjectSchema
import com.cjbooms.fabrikt.parser.GeneratorSchema
import com.cjbooms.fabrikt.parser.GeneratorSchemaDocument
import com.cjbooms.fabrikt.parser.GeneratorSchemaIdentity
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassifier
import com.cjbooms.fabrikt.parser.SourceSchemaConstraints
import com.fasterxml.jackson.databind.JsonNode

internal data class GeneratorModelDescriptor(
    val name: String,
    val schemaIdentity: GeneratorSchemaIdentity,
    val classification: GeneratorSchemaTypeClassification,
    val kotlinType: GeneratorKotlinTypeResolution,
    val description: String?,
    val properties: List<GeneratorPropertyDescriptor>,
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
        val typeResolver = GeneratorKotlinTypeResolver(document)
        return build(document, typeResolver)
    }

    private fun build(
        document: GeneratorSchemaDocument,
        typeResolver: GeneratorKotlinTypeResolver,
    ): List<GeneratorModelDescriptor> =
        document.componentSchemas.map { (name, schema) ->
            val resolvedSchema = document.resolve(schema)
            GeneratorModelDescriptor(
                name = name,
                schemaIdentity = resolvedSchema.identity,
                classification = GeneratorSchemaTypeClassifier.classify(resolvedSchema),
                kotlinType = typeResolver.resolve(resolvedSchema),
                description = (resolvedSchema as? GeneratorObjectSchema)?.metadata?.description,
                properties = resolvedSchema.properties(document, typeResolver),
            )
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
