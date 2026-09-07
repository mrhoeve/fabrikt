package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceServers(
    val location: String,
    val node: JsonNode,
    val values: List<SourceServer>,
)

internal data class SourceServer(
    val location: String,
    val node: JsonNode,
    val url: String?,
    val description: String?,
    val name: String?,
    val variables: Map<String, SourceServerVariable>,
    val extensions: Map<String, JsonNode>,
)

internal data class SourceServerVariable(
    val location: String,
    val name: String,
    val node: JsonNode,
    val enumValues: List<String>,
    val defaultValue: String?,
    val description: String?,
    val extensions: Map<String, JsonNode>,
)
