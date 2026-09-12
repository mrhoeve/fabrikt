package com.cjbooms.fabrikt.parser

internal enum class SchemaGenerationMode {
    LEGACY,
    NATIVE,
}

internal class GeneratorSchemaDocument(
    val version: OpenApiVersion?,
    val componentSchemas: Map<String, GeneratorSchema>,
    private val referencedSchemas: Map<GeneratorSchemaIdentity, GeneratorSchema>,
) {
    fun resolve(schema: GeneratorSchema): GeneratorSchema = referencedSchemas[schema.identity] ?: schema
}

internal fun ParsedOpenApiDocument.toGeneratorSchemaDocument(mode: SchemaGenerationMode): GeneratorSchemaDocument {
    val (schemas, referencedSchemas) =
        when (mode) {
            SchemaGenerationMode.LEGACY -> {
                val adapter = LegacyGeneratorSchemaAdapter()
                kaizenModel.schemas.mapValues { (_, schema) -> adapter.adapt(schema) } to emptyMap()
            }
            SchemaGenerationMode.NATIVE ->
                source.componentSchemas to
                    source.schemaReferenceResolutions
                        .mapNotNull { (location, resolution) ->
                            val sourceSchema = source.schemasByLocation[location] ?: return@mapNotNull null
                            val target = (resolution as? SourceSchemaReferenceResolution.Resolved)?.target ?: return@mapNotNull null
                            sourceSchema.identity to target
                        }.toMap()
        }

    return GeneratorSchemaDocument(version, schemas, referencedSchemas)
}
