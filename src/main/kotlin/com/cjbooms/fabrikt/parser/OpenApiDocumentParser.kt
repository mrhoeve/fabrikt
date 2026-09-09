package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.OpenApi31Downgrader
import com.fasterxml.jackson.databind.JsonNode
import com.reprezen.jsonoverlay.JsonLoader
import com.reprezen.kaizen.oasparser.model3.OpenApi3
import java.net.URI
import java.nio.file.Paths

internal class ParsedOpenApiDocument(
    val sourceGraph: SourceOpenApiDocumentGraph,
    kaizenModelProvider: () -> OpenApi3,
) {
    val kaizenModel: OpenApi3 by lazy(kaizenModelProvider)
    val source: SourceOpenApiDocument = sourceGraph.rootDocument
    val version: OpenApiVersion? = source.version
    val operations: SourceOperationDocument = source.operations
}

internal object OpenApiDocumentParser {
    fun parse(
        input: String,
        baseUri: URI = Paths.get("").toAbsolutePath().toUri(),
        jsonLoader: JsonLoader? = null,
        documentUri: URI = baseUri,
        sourceDocumentLoader: SourceDocumentLoader? = null,
    ): ParsedOpenApiDocument = parse(input, baseUri, jsonLoader, documentUri, sourceDocumentLoader, true)

    fun parseSource(
        input: String,
        baseUri: URI = Paths.get("").toAbsolutePath().toUri(),
        jsonLoader: JsonLoader? = null,
        documentUri: URI = baseUri,
        sourceDocumentLoader: SourceDocumentLoader? = null,
    ): ParsedOpenApiDocument = parse(input, baseUri, jsonLoader, documentUri, sourceDocumentLoader, false)

    private fun parse(
        input: String,
        baseUri: URI,
        jsonLoader: JsonLoader?,
        documentUri: URI,
        sourceDocumentLoader: SourceDocumentLoader?,
        initialiseKaizenModel: Boolean,
    ): ParsedOpenApiDocument =
        try {
            val sourceGraph =
                SourceOpenApiDocumentGraphParser.parse(
                    input = input,
                    documentUri = documentUri,
                    documentLoader = sourceDocumentLoader ?: jsonLoader.asSourceDocumentLoader(),
                )
            val parsedDocument =
                ParsedOpenApiDocument(sourceGraph) {
                    try {
                        parseKaizenModel(sourceGraph.rootDocument, baseUri, jsonLoader)
                    } catch (ex: NullPointerException) {
                        throw kaizenParserException(ex)
                    }
                }
            if (initialiseKaizenModel) parsedDocument.kaizenModel
            parsedDocument
        } catch (ex: NullPointerException) {
            throw kaizenParserException(ex)
        }

    private fun parseKaizenModel(
        source: SourceOpenApiDocument,
        baseUri: URI,
        jsonLoader: JsonLoader?,
    ): OpenApi3 {
        val kaizenInput = source.root.deepCopy<JsonNode>()
        OpenApi31Downgrader.downgradeIncompatibleElements(kaizenInput)
        OpenApiInputCleaner.cleanEmptyTypes(kaizenInput)
        return KaizenParserAdapter.parse(kaizenInput, baseUri.toURL(), jsonLoader)
    }

    private fun kaizenParserException(cause: NullPointerException): IllegalArgumentException =
        IllegalArgumentException(
            "The Kaizen openapi-parser library threw a NPE exception when parsing this API. " +
                "This is commonly due to an external schema reference that is unresolvable, " +
                "possibly due to a lack of internet connection",
            cause,
        )

    private fun JsonLoader?.asSourceDocumentLoader(): SourceDocumentLoader =
        this?.let { loader -> SourceDocumentLoader { documentUri -> loader.load(documentUri.toURL()).toString() } }
            ?: DefaultSourceDocumentLoader()
}
