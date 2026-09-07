package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceTag(
    val location: String,
    val node: JsonNode,
    val name: String?,
    val summary: String?,
    val description: String?,
    val externalDocumentation: SourceExternalDocumentation?,
    val parent: String?,
    val kind: String?,
    val extensions: Map<String, JsonNode>,
)
