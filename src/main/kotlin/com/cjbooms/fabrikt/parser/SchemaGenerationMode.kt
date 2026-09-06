package com.cjbooms.fabrikt.parser

internal enum class SchemaGenerationMode {
    LEGACY,
    NATIVE,
}

internal class GeneratorSchemaDocument(
    val version: OpenApiVersion?,
    val componentSchemas: Map<String, GeneratorSchema>,
    private val referencedSchemas: Map<GeneratorSchemaIdentity, GeneratorSchema>,
    schemaEntryPoints: Collection<GeneratorSchema> = componentSchemas.values,
    private val externalSchemaIdentities: Set<GeneratorSchemaIdentity> = emptySet(),
) {
    private val resolvedSchemas = GeneratorSchemaReferenceResolver.resolve(version, schemaEntryPoints, referencedSchemas)

    fun resolve(schema: GeneratorSchema): GeneratorSchema = resolvedSchemas[schema.identity] ?: schema

    fun isExternal(schema: GeneratorSchema): Boolean = schema.identity in externalSchemaIdentities
}

internal fun ParsedOpenApiDocument.toGeneratorSchemaDocument(mode: SchemaGenerationMode): GeneratorSchemaDocument {
    return when (mode) {
        SchemaGenerationMode.LEGACY -> {
            val adapter = LegacyGeneratorSchemaAdapter()
            GeneratorSchemaDocument(
                version = version,
                componentSchemas = kaizenModel.schemas.mapValues { (_, schema) -> adapter.adapt(schema) },
                referencedSchemas = emptyMap(),
            )
        }
        SchemaGenerationMode.NATIVE ->
            GeneratorSchemaDocument(
                version = version,
                componentSchemas = source.componentSchemas,
                referencedSchemas =
                    sourceGraph.schemaReferenceResolutions
                        .mapNotNull { (location, resolution) ->
                            val document = sourceGraph.documentsByUri[location.documentUri] ?: return@mapNotNull null
                            val sourceSchema = document.schemasByLocation[location.schemaLocation] ?: return@mapNotNull null
                            val target = (resolution as? SourceSchemaReferenceResolution.Resolved)?.target ?: return@mapNotNull null
                            sourceSchema.identity to target
                        }.toMap(),
                schemaEntryPoints = sourceGraph.documentsByUri.values.flatMap { it.schemaEntryPoints.values },
                externalSchemaIdentities =
                    sourceGraph.documentsByUri
                        .filterKeys { it != source.baseUri }
                        .values
                        .flatMap { document -> document.schemasByLocation.values.map { it.identity } }
                        .toSet(),
            )
    }
}
