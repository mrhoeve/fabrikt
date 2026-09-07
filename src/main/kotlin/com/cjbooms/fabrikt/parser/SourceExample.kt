package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceExample(
    val location: String,
    val name: String,
    val node: JsonNode,
    val reference: String?,
    val summary: String?,
    val description: String?,
    val value: JsonNode?,
    val externalValue: String?,
    val dataValue: JsonNode?,
    val serializedValue: String?,
    val extensions: Map<String, JsonNode>,
)
