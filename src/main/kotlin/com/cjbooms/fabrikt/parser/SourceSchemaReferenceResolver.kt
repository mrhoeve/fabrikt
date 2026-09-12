package com.cjbooms.fabrikt.parser

import java.net.URI

internal object SourceSchemaReferenceResolver {
    fun index(
        baseUri: URI,
        version: OpenApiVersion?,
        schemaEntryPoints: Collection<SourceSchema>,
        supportsSchemaResources: Boolean = version?.isAtLeast(3, 1) == true,
        entryPointScopes: Map<String, SourceSchemaResourceScope> = emptyMap(),
    ): SourceSchemaReferenceIndex {
        val index = ReferenceIndex(baseUri.withoutFragment().toAsciiUri(), supportsSchemaResources)
        schemaEntryPoints.forEach { schema -> index.index(schema, entryPointScopes[schema.location]) }
        return index.build()
    }

    private class ReferenceIndex(
        private val documentUri: URI,
        private val supportsSchemaResources: Boolean,
    ) {
        private val schemasByUri = linkedMapOf<URI, SourceSchema>()
        private val dynamicSchemasByUri = linkedMapOf<URI, SourceSchema>()
        private val baseUrisByLocation = linkedMapOf<String, URI>()
        private val schemasByLocation = linkedMapOf<String, SourceSchema>()
        private val resourceUris = linkedSetOf(documentUri)
        private val resourceUrisByRootLocation = linkedMapOf("#" to documentUri)

        fun index(
            schema: SourceSchema,
            resourceScope: SourceSchemaResourceScope?,
        ) {
            val scope = resourceScope?.let { Scope(it.uri, it.uri, it.rootLocation) } ?: Scope(documentUri, documentUri, "#")
            index(schema, scope)
        }

        fun build(): SourceSchemaReferenceIndex =
            SourceSchemaReferenceIndex(
                documentUri = documentUri,
                schemasByUri = schemasByUri.toMap(),
                dynamicSchemasByUri = dynamicSchemasByUri.toMap(),
                resourceUris = resourceUris.toSet(),
                resourceUrisByRootLocation = resourceUrisByRootLocation.toMap(),
                resolutionsByLocation =
                    schemasByLocation.values
                        .asSequence()
                        .filterIsInstance<SourceObjectSchema>()
                        .mapNotNull { schema ->
                            schema.reference?.let { value ->
                                schema.location to resolve(schema, value, requireNotNull(schema.referenceKind))
                            }
                        }.toMap(linkedMapOf()),
            )

        private fun index(
            schema: SourceSchema,
            inheritedScope: Scope,
        ) {
            val scope = schema.scope(inheritedScope)
            schemasByLocation.putIfAbsent(schema.location, schema)
            baseUrisByLocation.putIfAbsent(schema.location, scope.baseUri)
            schemasByUri.putIfAbsent(documentUri.withFragment(schema.location.removePrefix("#")).withoutEmptyFragment(), schema)
            schemasByUri.putIfAbsent(scope.uriFor(schema.location), schema)

            if (schema is SourceObjectSchema && supportsSchemaResources) {
                schema.anchor
                    ?.takeIf(ANCHOR_PATTERN::matches)
                    ?.let { anchor -> schemasByUri.putIfAbsent(scope.resourceUri.withFragment(anchor), schema) }
                schema.dynamicAnchor
                    ?.takeIf(ANCHOR_PATTERN::matches)
                    ?.let { anchor -> dynamicSchemasByUri.putIfAbsent(scope.resourceUri.withFragment(anchor), schema) }
            }

            schema.childSchemas().forEach { child -> index(child, scope) }
        }

        private fun SourceSchema.scope(inherited: Scope): Scope {
            if (this !is SourceObjectSchema || !supportsSchemaResources) return inherited
            val resolvedIdentifier = identifier?.resolveAgainst(inherited.baseUri) ?: return inherited
            if (!resolvedIdentifier.hasEmptyFragment()) return inherited

            val resourceUri = resolvedIdentifier.withoutFragment()
            resourceUris.add(resourceUri)
            resourceUrisByRootLocation[location] = resourceUri
            return Scope(resourceUri, resourceUri, location)
        }

        private fun resolve(
            schema: SourceObjectSchema,
            value: String,
            kind: SourceSchemaReferenceKind,
        ): SourceSchemaReferenceResolution {
            val resolvedUri =
                value.resolveAgainst(baseUrisByLocation.getValue(schema.location))
                    ?: return SourceSchemaReferenceResolution.Invalid(value)
            val canonicalUri = resolvedUri.withoutEmptyFragment()
            val target =
                if (kind == SourceSchemaReferenceKind.DYNAMIC) {
                    dynamicSchemasByUri[canonicalUri] ?: schemasByUri[canonicalUri]
                } else {
                    schemasByUri[canonicalUri]
                }
            val dynamic = kind != SourceSchemaReferenceKind.STATIC
            if (target != null) return SourceSchemaReferenceResolution.Resolved(value, canonicalUri, target, dynamic)

            return if (canonicalUri.withoutFragment() in resourceUris) {
                SourceSchemaReferenceResolution.Missing(value, canonicalUri, dynamic)
            } else {
                SourceSchemaReferenceResolution.External(value, canonicalUri, dynamic)
            }
        }
    }

    private data class Scope(
        val baseUri: URI,
        val resourceUri: URI,
        val resourceRootLocation: String,
    ) {
        fun uriFor(location: String): URI {
            if (location == resourceRootLocation) return resourceUri
            val pointer =
                if (resourceRootLocation == "#") {
                    location.removePrefix("#")
                } else {
                    location.removePrefix(resourceRootLocation)
                }
            return resourceUri.withFragment(pointer)
        }
    }

    private fun URI.hasEmptyFragment(): Boolean = rawFragment.isNullOrEmpty()

    private fun URI.withoutEmptyFragment(): URI = if (rawFragment == "") withoutFragment() else this

    private val ANCHOR_PATTERN = Regex("^[A-Za-z_][-A-Za-z0-9._]*$")
}

internal data class SourceSchemaResourceScope(
    val uri: URI,
    val rootLocation: String,
)
