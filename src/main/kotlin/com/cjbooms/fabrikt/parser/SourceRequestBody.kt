package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceRequestBody(
    val location: String,
    val node: JsonNode,
    val reference: String?,
    val description: String?,
    val required: Boolean,
    val content: List<SourceMediaType>,
    val extensions: Map<String, JsonNode>,
)
