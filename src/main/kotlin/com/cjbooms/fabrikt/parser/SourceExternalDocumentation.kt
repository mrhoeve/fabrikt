package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceExternalDocumentation(
    val location: String,
    val node: JsonNode,
    val description: String?,
    val url: String?,
    val extensions: Map<String, JsonNode>,
)
