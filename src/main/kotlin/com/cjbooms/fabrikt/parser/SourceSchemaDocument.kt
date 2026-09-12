package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.YamlObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import java.net.URI

internal data class SourceSchemaDocument(
    val documentUri: URI,
    val content: String,
    val root: JsonNode,
    val version: OpenApiVersion?,
    val supportsSchemaResources: Boolean,
    val schemaEntryPoints: Map<String, SourceSchema>,
    val schemasByLocation: Map<String, SourceSchema>,
    val schemaReferenceIndex: SourceSchemaReferenceIndex,
) {
    fun materializeReferencedSchema(targetUri: URI): SourceSchemaDocument {
        val targetBaseUri = targetUri.withoutFragment()
        val referencedResourceRoot =
            if (targetBaseUri == documentUri) {
                "#"
            } else {
                schemaReferenceIndex.resourceUrisByRootLocation.entries
                    .firstOrNull { (_, resourceUri) -> resourceUri == targetBaseUri }
                    ?.key
                    ?: return this
            }
        val pointer = targetUri.fragment?.takeIf { it.startsWith('/') } ?: return this
        val location = if (referencedResourceRoot == "#") "#$pointer" else "$referencedResourceRoot$pointer"
        if (location in schemasByLocation) return this
        val node = runCatching { root.at(location.removePrefix("#")) }.getOrNull()
        if (node == null || node.isMissingNode || (!node.isObject && !node.isBoolean)) return this

        val entryPoints =
            (schemaEntryPoints + (location to SourceSchemaParser.parse(node, location, version)))
                .toList()
                .sortedWith(compareBy({ (entryLocation, _) -> entryLocation.count { it == '/' } }, { it.first }))
        val indexedSchemas = indexSchemas(entryPoints.map(Pair<String, SourceSchema>::second))
        val canonicalEntryPoints = entryPoints.associate { (entryLocation, _) -> entryLocation to indexedSchemas.getValue(entryLocation) }
        val firstReferenceIndex = indexReferences(canonicalEntryPoints, schemaReferenceIndex.resourceUrisByRootLocation)
        return copy(
            schemaEntryPoints = canonicalEntryPoints,
            schemasByLocation = indexedSchemas,
            schemaReferenceIndex = indexReferences(canonicalEntryPoints, firstReferenceIndex.resourceUrisByRootLocation),
        )
    }

    private fun indexReferences(
        entryPoints: Map<String, SourceSchema>,
        resourceUrisByRootLocation: Map<String, URI>,
    ): SourceSchemaReferenceIndex =
        SourceSchemaReferenceResolver.index(
            baseUri = documentUri,
            version = version,
            schemaEntryPoints = entryPoints.values,
            supportsSchemaResources = supportsSchemaResources,
            entryPointScopes =
                entryPoints.keys
                    .mapNotNull { location ->
                        resourceUrisByRootLocation
                            .filterKeys { rootLocation -> location.isWithin(rootLocation) }
                            .maxByOrNull { (rootLocation, _) -> rootLocation.count { it == '/' } }
                            ?.let { (rootLocation, resourceUri) ->
                                location to SourceSchemaResourceScope(resourceUri, rootLocation)
                            }
                    }.toMap(),
        )

    private fun String.isWithin(rootLocation: String): Boolean = this == rootLocation || startsWith("$rootLocation/")
}

internal fun SourceOpenApiDocument.asSchemaDocument(): SourceSchemaDocument =
    SourceSchemaDocument(
        documentUri = baseUri.withoutFragment().normalize().toAsciiUri(),
        content = content,
        root = root,
        version = version,
        supportsSchemaResources = version?.isAtLeast(3, 1) == true,
        schemaEntryPoints = schemaEntryPoints,
        schemasByLocation = schemasByLocation,
        schemaReferenceIndex = schemaReferenceIndex,
    )

internal object SourceSchemaDocumentParser {
    fun parse(
        input: String,
        documentUri: URI,
    ): SourceSchemaDocument {
        val canonicalDocumentUri = documentUri.withoutFragment().normalize().toAsciiUri()
        val root = YamlObjectMapper.instance.readTree(input)
        if (OpenApiVersion.parse(root["openapi"]?.asText()) != null && root["info"]?.isObject == true) {
            return SourceOpenApiDocumentParser.parse(input, canonicalDocumentUri).asSchemaDocument()
        }
        require(root.isObject || root.isBoolean) { "External schema document must contain an object or boolean schema: $documentUri" }

        val rootSchema = SourceSchemaParser.parse(root, "#", null)
        val schemasByLocation = indexSchemas(listOf(rootSchema))
        return SourceSchemaDocument(
            documentUri = canonicalDocumentUri,
            content = input,
            root = root,
            version = null,
            supportsSchemaResources = true,
            schemaEntryPoints = mapOf("#" to rootSchema),
            schemasByLocation = schemasByLocation,
            schemaReferenceIndex =
                SourceSchemaReferenceResolver.index(
                    baseUri = canonicalDocumentUri,
                    version = null,
                    schemaEntryPoints = listOf(rootSchema),
                    supportsSchemaResources = true,
                ),
        )
    }
}

private fun indexSchemas(schemaEntryPoints: Collection<SourceSchema>): Map<String, SourceSchema> =
    buildMap {
        fun index(schema: SourceSchema) {
            putIfAbsent(schema.location, schema)
            schema.childSchemas().forEach(::index)
        }
        schemaEntryPoints.forEach(::index)
    }
