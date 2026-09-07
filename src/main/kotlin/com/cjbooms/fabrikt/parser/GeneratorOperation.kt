package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class GeneratorOperationDocument(
    val basePath: String,
    val security: GeneratorSecurityRequirements?,
    val paths: List<GeneratorPathItem>,
)

internal data class GeneratorPathItem(
    val path: String,
    val parameters: List<GeneratorParameter>,
    val operations: List<GeneratorOperation>,
)

internal data class GeneratorOperation(
    val method: String,
    val operationId: String?,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    val parameters: List<GeneratorParameter>,
    val requestBody: GeneratorRequestBody?,
    val responses: List<GeneratorResponse>,
    val security: GeneratorSecurityRequirements?,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorParameter(
    val name: String?,
    val placement: String?,
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
    val explode: Boolean?,
    val schema: GeneratorSchema?,
    val content: List<GeneratorMediaType>,
)

internal data class GeneratorRequestBody(
    val description: String?,
    val required: Boolean,
    val content: List<GeneratorMediaType>,
)

internal data class GeneratorResponse(
    val status: String,
    val description: String?,
    val headers: Map<String, GeneratorHeader>,
    val content: List<GeneratorMediaType>,
)

internal data class GeneratorHeader(
    val name: String,
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
    val explode: Boolean?,
    val schema: GeneratorSchema?,
    val content: List<GeneratorMediaType>,
)

internal data class GeneratorMediaType(
    val key: String,
    val schema: GeneratorSchema?,
    val itemSchema: GeneratorSchema?,
)

internal data class GeneratorSecurityRequirements(
    val values: List<GeneratorSecurityRequirement>,
)

internal data class GeneratorSecurityRequirement(
    val schemes: Map<String, List<String>>,
)
