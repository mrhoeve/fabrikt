package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceResponses(
    val location: String,
    val node: JsonNode,
    val values: List<SourceResponse>,
    val extensions: Map<String, JsonNode>,
)

internal data class SourceResponse(
    val location: String,
    val key: String,
    val node: JsonNode,
    val reference: String?,
    val description: String?,
    val content: List<SourceMediaType>,
    val extensions: Map<String, JsonNode>,
)
