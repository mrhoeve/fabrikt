package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceSecurityRequirements(
    val location: String,
    val node: JsonNode,
    val values: List<SourceSecurityRequirement>,
)

internal data class SourceSecurityRequirement(
    val location: String,
    val node: JsonNode,
    val schemes: Map<String, List<String>>,
)
