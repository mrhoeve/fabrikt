package com.cjbooms.fabrikt.parser

import java.net.URI

internal class NativeGeneratorOperationAdapter(
    private val document: SourceOperationDocument,
    private val resolveSchema: (GeneratorSchema) -> GeneratorSchema = { it },
) {
    fun adapt(): GeneratorOperationDocument =
        GeneratorOperationDocument(
            serverUrl =
                document.servers
                    ?.values
                    ?.firstOrNull()
                    ?.url,
            basePath =
                document.servers
                    ?.values
                    ?.firstOrNull()
                    ?.url
                    ?.let { url -> runCatching { URI.create(url).path }.getOrNull() }
                    .orEmpty()
                    .removeSuffix("/"),
            security = document.security?.toGeneratorSecurityRequirements(),
            paths = document.paths.map { path -> path.resolve().toGeneratorPathItem(path.key) },
            webhooks = document.webhooks.map { path -> path.resolve().toGeneratorPathItem(path.key) },
        )

    private fun SourcePathItem.toGeneratorPathItem(path: String): GeneratorPathItem =
        GeneratorPathItem(
            path = path,
            kind = kind.toGeneratorPathItemKind(),
            parameters = parameters.map { it.resolve().toGeneratorParameter() },
            operations = operations.map { it.toGeneratorOperation() },
        )

    private fun SourceOperation.toGeneratorOperation(): GeneratorOperation =
        GeneratorOperation(
            method = method.wireName.lowercase(),
            operationId = operationId,
            summary = summary,
            description = description,
            tags = tags,
            deprecated = deprecated,
            parameters = parameters.map { it.resolve().toGeneratorParameter() },
            requestBody = requestBody?.resolve()?.toGeneratorRequestBody(),
            responses = responses?.values.orEmpty().map { response -> response.resolve().toGeneratorResponse(response.key) },
            security = security?.toGeneratorSecurityRequirements(),
            callbacks = callbacks.map { callback -> callback.resolve().toGeneratorCallback(callback.name) },
            extensions = extensions,
        )

    private fun SourceCallback.toGeneratorCallback(name: String): GeneratorCallback =
        GeneratorCallback(
            name = name,
            pathItems = pathItems.map { path -> path.resolve().toGeneratorPathItem(path.key) },
            extensions = extensions,
        )

    private fun SourceParameter.toGeneratorParameter(): GeneratorParameter =
        GeneratorParameter(
            name = name,
            placement = placement?.value,
            description = description,
            required = required,
            deprecated = deprecated,
            explode = explode,
            schema = schema?.let(resolveSchema),
            content = content.map { mediaType -> mediaType.resolve().toGeneratorMediaType(mediaType.key) },
        )

    private fun SourceRequestBody.toGeneratorRequestBody(): GeneratorRequestBody =
        GeneratorRequestBody(
            description = description,
            required = required,
            content = content.map { mediaType -> mediaType.resolve().toGeneratorMediaType(mediaType.key) },
        )

    private fun SourceResponse.toGeneratorResponse(status: String): GeneratorResponse =
        GeneratorResponse(
            status = status,
            description = description,
            headers = headers.mapValues { (name, header) -> header.resolve().toGeneratorHeader(name) },
            content = content.map { mediaType -> mediaType.resolve().toGeneratorMediaType(mediaType.key) },
        )

    private fun SourceHeader.toGeneratorHeader(name: String): GeneratorHeader =
        GeneratorHeader(
            name = name,
            description = description,
            required = required,
            deprecated = deprecated,
            explode = explode,
            schema = schema?.let(resolveSchema),
            content = content.map { mediaType -> mediaType.resolve().toGeneratorMediaType(mediaType.key) },
        )

    private fun SourceMediaType.toGeneratorMediaType(key: String): GeneratorMediaType =
        GeneratorMediaType(
            key = key,
            schema = schema?.let(resolveSchema),
            itemSchema = itemSchema?.let(resolveSchema),
            encoding = encoding.mapValues { (_, value) -> value.toGeneratorEncoding() },
            prefixEncoding = prefixEncoding.map { it.toGeneratorEncoding() },
            itemEncoding = itemEncoding?.toGeneratorEncoding(),
        )

    private fun SourceEncoding.toGeneratorEncoding(): GeneratorEncoding =
        GeneratorEncoding(
            contentType = contentType,
            headers = headers.mapValues { (name, header) -> header.resolve().toGeneratorHeader(name) },
            style = style,
            explode = explode,
            allowReserved = allowReserved,
            encoding = encoding.mapValues { (_, value) -> value.toGeneratorEncoding() },
            prefixEncoding = prefixEncoding.map { it.toGeneratorEncoding() },
            itemEncoding = itemEncoding?.toGeneratorEncoding(),
            extensions = extensions,
        )

    private fun SourceSecurityRequirements.toGeneratorSecurityRequirements(): GeneratorSecurityRequirements =
        GeneratorSecurityRequirements(values.map { GeneratorSecurityRequirement(it.schemes) })

    private fun SourcePathItem.resolve(): SourcePathItem =
        resolveLocal(this, "#/components/pathItems/", SourcePathItem::reference, document.reusablePathItems)

    private fun SourceParameter.resolve(): SourceParameter =
        resolveLocal(this, "#/components/parameters/", SourceParameter::reference, document.reusableParameters)

    private fun SourceRequestBody.resolve(): SourceRequestBody =
        resolveLocal(this, "#/components/requestBodies/", SourceRequestBody::reference, document.reusableRequestBodies)

    private fun SourceResponse.resolve(): SourceResponse =
        resolveLocal(this, "#/components/responses/", SourceResponse::reference, document.reusableResponses)

    private fun SourceHeader.resolve(): SourceHeader =
        resolveLocal(this, "#/components/headers/", SourceHeader::reference, document.reusableHeaders)

    private fun SourceCallback.resolve(): SourceCallback =
        resolveLocal(this, "#/components/callbacks/", SourceCallback::reference, document.reusableCallbacks)

    private fun SourceMediaType.resolve(): SourceMediaType =
        resolveLocal(this, "#/components/mediaTypes/", SourceMediaType::reference, document.reusableMediaTypes)

    private fun <T> resolveLocal(
        value: T,
        referencePrefix: String,
        referenceOf: (T) -> String?,
        components: Map<String, T>,
    ): T {
        var current = value
        val visited = mutableSetOf<String>()
        while (true) {
            val reference = referenceOf(current)?.takeIf { it.startsWith(referencePrefix) } ?: return current
            if (!visited.add(reference)) return current
            val name = reference.removePrefix(referencePrefix).fromJsonPointerToken()
            current = components[name] ?: return current
        }
    }

    private fun String.fromJsonPointerToken(): String = replace("~1", "/").replace("~0", "~")

    private fun SourcePathItemKind.toGeneratorPathItemKind(): GeneratorPathItemKind =
        when (this) {
            SourcePathItemKind.PATH,
            SourcePathItemKind.REUSABLE_PATH_ITEM,
            -> GeneratorPathItemKind.PATH
            SourcePathItemKind.WEBHOOK -> GeneratorPathItemKind.WEBHOOK
            SourcePathItemKind.CALLBACK,
            SourcePathItemKind.REUSABLE_CALLBACK,
            -> GeneratorPathItemKind.CALLBACK
        }
}

internal fun ParsedOpenApiDocument.toGeneratorOperationDocument(mode: SchemaGenerationMode): GeneratorOperationDocument =
    when (mode) {
        SchemaGenerationMode.LEGACY -> LegacyGeneratorOperationAdapter().adapt(kaizenModel)
        SchemaGenerationMode.NATIVE -> {
            val schemas = toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
            NativeGeneratorOperationAdapter(operations, schemas::resolve).adapt()
        }
    }
