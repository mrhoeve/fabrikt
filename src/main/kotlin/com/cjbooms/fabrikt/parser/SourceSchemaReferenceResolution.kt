package com.cjbooms.fabrikt.parser

import java.net.URI

internal sealed interface SourceSchemaReferenceResolution {
    val value: String

    data class Resolved(
        override val value: String,
        val uri: URI,
        val target: SourceSchema,
        val dynamic: Boolean = false,
    ) : SourceSchemaReferenceResolution

    data class Missing(
        override val value: String,
        val uri: URI,
        val dynamic: Boolean = false,
    ) : SourceSchemaReferenceResolution

    data class External(
        override val value: String,
        val uri: URI,
        val dynamic: Boolean = false,
    ) : SourceSchemaReferenceResolution

    data class Invalid(
        override val value: String,
    ) : SourceSchemaReferenceResolution
}

internal data class SourceSchemaReferenceIndex(
    val documentUri: URI,
    val schemasByUri: Map<URI, SourceSchema>,
    val dynamicSchemasByUri: Map<URI, SourceSchema>,
    val resourceUris: Set<URI>,
    val resourceUrisByRootLocation: Map<String, URI>,
    val resolutionsByLocation: Map<String, SourceSchemaReferenceResolution>,
)
