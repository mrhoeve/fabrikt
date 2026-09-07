package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceLink(
    val location: String,
    val name: String,
    val node: JsonNode,
    val reference: String?,
    val operationReference: String?,
    val operationId: String?,
    val parameters: Map<String, JsonNode>,
    val requestBody: JsonNode?,
    val description: String?,
    val server: SourceServer?,
    val extensions: Map<String, JsonNode>,
)
