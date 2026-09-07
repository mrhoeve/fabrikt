package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceHeader(
    val location: String,
    val name: String,
    val node: JsonNode,
    val reference: String?,
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
    val allowEmptyValue: Boolean?,
    val style: String?,
    val explode: Boolean?,
    val schema: SourceSchema?,
    val content: List<SourceMediaType>,
    val extensions: Map<String, JsonNode>,
)
