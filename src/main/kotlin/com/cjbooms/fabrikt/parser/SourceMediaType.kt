package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceMediaType(
    val location: String,
    val key: String,
    val node: JsonNode,
    val reference: String?,
    val schema: SourceSchema?,
    val itemSchema: SourceSchema?,
    val encoding: Map<String, SourceEncoding>,
    val prefixEncoding: List<SourceEncoding>,
    val itemEncoding: SourceEncoding?,
    val extensions: Map<String, JsonNode>,
)
