package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceEncoding(
    val location: String,
    val key: String?,
    val node: JsonNode,
    val contentType: String?,
    val headers: Map<String, SourceHeader>,
    val style: String?,
    val explode: Boolean?,
    val allowReserved: Boolean?,
    val extensions: Map<String, JsonNode>,
)
