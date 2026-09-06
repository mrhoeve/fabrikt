package com.cjbooms.fabrikt.parser

internal object GeneratorSchemaReferenceResolver {
    fun resolve(
        version: OpenApiVersion?,
        componentSchemas: Collection<GeneratorSchema>,
        referencedSchemas: Map<GeneratorSchemaIdentity, GeneratorSchema>,
    ): Map<GeneratorSchemaIdentity, GeneratorSchema> {
        val schemasByIdentity =
            componentSchemas
                .asSequence()
                .flatMap { it.withDescendants() }
                .associateBy(GeneratorSchema::identity)
        return buildMap {
            fun resolveReference(
                identity: GeneratorSchemaIdentity,
                resolving: Set<GeneratorSchemaIdentity>,
            ): GeneratorSchema? {
                get(identity)?.let { return it }
                val target = referencedSchemas[identity] ?: return schemasByIdentity[identity]
                if (identity in resolving) return schemasByIdentity[identity]
                val resolvedTarget = resolveReference(target.identity, resolving + identity) ?: target
                val sibling = schemasByIdentity[identity] as? GeneratorObjectSchema
                val resolved =
                    if (version?.isAtLeast(3, 1) == true && sibling?.hasReferenceSiblings() == true) {
                        ReferenceSiblingSchema(sibling, resolvedTarget)
                    } else {
                        resolvedTarget
                    }
                put(identity, resolved)
                return resolved
            }

            referencedSchemas.keys.forEach { resolveReference(it, emptySet()) }
        }
    }

    private fun GeneratorSchema.withDescendants(): Sequence<GeneratorSchema> =
        sequence {
            yield(this@withDescendants)
            if (this@withDescendants is SourceSchema) {
                yieldAll(childSchemas().flatMap { it.withDescendants() })
            }
        }

    private class ReferenceSiblingSchema(
        override val siblingSchema: GeneratorObjectSchema,
        override val referencedSchema: GeneratorSchema,
    ) : GeneratorReferenceSiblingSchema,
        GeneratorObjectSchema by siblingSchema {
        private val referencedObjectSchema = referencedSchema as? GeneratorObjectSchema

        override val reference: String? = null
        override val allOf: List<GeneratorSchema> = listOf(referencedSchema) + siblingSchema.allOf
        override val changesGeneratedShape: Boolean = siblingSchema.changesGeneratedShape()
        override val metadata: SourceSchemaMetadata = siblingSchema.metadata.merge(referencedObjectSchema?.metadata)
        override val constraints: SourceSchemaConstraints = siblingSchema.constraints.intersect(referencedObjectSchema?.constraints)
        override val prefixItems: List<GeneratorSchema> =
            siblingSchema.prefixItems.ifEmpty { referencedObjectSchema?.prefixItems.orEmpty() }
        override val items: GeneratorSchema? = siblingSchema.items ?: referencedObjectSchema?.items
        override val additionalProperties: GeneratorSchema? =
            siblingSchema.additionalProperties ?: referencedObjectSchema?.additionalProperties
    }

    private fun SourceSchemaMetadata.merge(referenced: SourceSchemaMetadata?): SourceSchemaMetadata =
        if (referenced == null) {
            this
        } else {
            copy(
                title = title ?: referenced.title,
                description = description ?: referenced.description,
                format = format ?: referenced.format,
                defaultValue = defaultValue ?: referenced.defaultValue,
                examples = (referenced.examples + examples).distinct(),
                enumValues = if (enumValues.isEmpty()) referenced.enumValues else enumValues,
                constValue = constValue ?: referenced.constValue,
                readOnly = readOnly || referenced.readOnly,
                writeOnly = writeOnly || referenced.writeOnly,
                deprecated = deprecated || referenced.deprecated,
                contentEncoding = contentEncoding ?: referenced.contentEncoding,
                contentMediaType = contentMediaType ?: referenced.contentMediaType,
                extensions = referenced.extensions + extensions,
            )
        }

    private fun SourceSchemaConstraints.intersect(referenced: SourceSchemaConstraints?): SourceSchemaConstraints =
        if (referenced == null) {
            this
        } else {
            copy(
                multipleOf = multipleOf ?: referenced.multipleOf,
                minimum =
                    listOfNotNull(referenced.minimum, minimum)
                        .maxWithOrNull(compareBy<SourceSchemaBound> { it.value }.thenBy { it.exclusive }),
                maximum =
                    listOfNotNull(referenced.maximum, maximum)
                        .minWithOrNull(compareBy<SourceSchemaBound> { it.value }.thenByDescending { it.exclusive }),
                minLength = listOfNotNull(referenced.minLength, minLength).maxOrNull(),
                maxLength = listOfNotNull(referenced.maxLength, maxLength).minOrNull(),
                pattern = intersectPatterns(referenced.pattern, pattern),
                minItems = listOfNotNull(referenced.minItems, minItems).maxOrNull(),
                maxItems = listOfNotNull(referenced.maxItems, maxItems).minOrNull(),
                uniqueItems = uniqueItems || referenced.uniqueItems,
                minContains = listOfNotNull(referenced.minContains, minContains).maxOrNull(),
                maxContains = listOfNotNull(referenced.maxContains, maxContains).minOrNull(),
                minProperties = listOfNotNull(referenced.minProperties, minProperties).maxOrNull(),
                maxProperties = listOfNotNull(referenced.maxProperties, maxProperties).minOrNull(),
            )
        }

    private fun intersectPatterns(
        referenced: String?,
        sibling: String?,
    ): String? =
        when {
            referenced == null -> sibling
            sibling == null || sibling == referenced -> referenced
            else -> "(?=(?:$referenced)\\z)(?:$sibling)"
        }

    private fun GeneratorObjectSchema.hasReferenceSiblings(): Boolean =
        types.isNotEmpty() ||
            metadata.hasReferenceSiblings() ||
            constraints.hasConstraints() ||
            requiredProperties.isNotEmpty() ||
            dependentRequired.isNotEmpty() ||
            discriminator != null ||
            definitions.isNotEmpty() ||
            properties.isNotEmpty() ||
            patternProperties.isNotEmpty() ||
            dependentSchemas.isNotEmpty() ||
            prefixItems.isNotEmpty() ||
            items != null ||
            contains != null ||
            propertyNames != null ||
            ifSchema != null ||
            thenSchema != null ||
            elseSchema != null ||
            allOf.isNotEmpty() ||
            anyOf.isNotEmpty() ||
            oneOf.isNotEmpty() ||
            not != null ||
            additionalProperties != null ||
            unevaluatedItems != null ||
            unevaluatedProperties != null ||
            contentSchema != null

    private fun GeneratorObjectSchema.changesGeneratedShape(): Boolean =
        metadata.enumValues.isNotEmpty() ||
            metadata.constValue != null ||
            requiredProperties.isNotEmpty() ||
            discriminator != null ||
            properties.isNotEmpty() ||
            allOf.isNotEmpty() ||
            anyOf.isNotEmpty() ||
            oneOf.isNotEmpty() ||
            additionalProperties != null

    private fun SourceSchemaMetadata.hasReferenceSiblings(): Boolean =
        title != null ||
            description != null ||
            format != null ||
            defaultValue != null ||
            examples.isNotEmpty() ||
            enumValues.isNotEmpty() ||
            constValue != null ||
            readOnly ||
            writeOnly ||
            deprecated ||
            contentEncoding != null ||
            contentMediaType != null ||
            extensions.isNotEmpty()

    private fun SourceSchemaConstraints.hasConstraints(): Boolean =
        multipleOf != null ||
            minimum != null ||
            maximum != null ||
            minLength != null ||
            maxLength != null ||
            pattern != null ||
            minItems != null ||
            maxItems != null ||
            uniqueItems ||
            minContains != null ||
            maxContains != null ||
            minProperties != null ||
            maxProperties != null
}
