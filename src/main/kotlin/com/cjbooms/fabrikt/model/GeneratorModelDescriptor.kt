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
    ): List<GeneratorPropertyDescriptor> {
        val objectSchema = this as? GeneratorObjectSchema ?: return emptyList()
        return objectSchema.properties.map { (name, propertySchema) ->
            val resolvedProperty = document.resolve(propertySchema)
            val property = resolvedProperty as? GeneratorObjectSchema
            GeneratorPropertyDescriptor(
                name = name,
                schemaIdentity = resolvedProperty.identity,
                classification = GeneratorSchemaTypeClassifier.classify(resolvedProperty),
                kotlinType = typeResolver.resolve(resolvedProperty),
                required = name in objectSchema.requiredProperties,
                readOnly = property?.metadata?.readOnly == true,
                writeOnly = property?.metadata?.writeOnly == true,
                deprecated = property?.metadata?.deprecated == true,
                description = property?.metadata?.description,
                defaultValue = property?.metadata?.defaultValue,
                constraints = property?.constraints,
            )
        }
    }
}
