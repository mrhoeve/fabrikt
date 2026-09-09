package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class GeneratorOperationDocument(
    val serverUrl: String?,
    val basePath: String,
    val security: GeneratorSecurityRequirements?,
    val securitySchemes: Map<String, GeneratorSecurityScheme>,
    val paths: List<GeneratorPathItem>,
    val webhooks: List<GeneratorPathItem>,
)

internal data class GeneratorSecurityScheme(
    val name: String,
    val type: String?,
    val description: String?,
    val parameterName: String?,
    val placement: String?,
    val scheme: String?,
    val bearerFormat: String?,
    val flows: List<GeneratorOAuthFlow>,
    val openIdConnectUrl: String?,
    val oauth2MetadataUrl: String?,
    val deprecated: Boolean,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorOAuthFlow(
    val type: String,
    val authorizationUrl: String?,
    val deviceAuthorizationUrl: String?,
    val tokenUrl: String?,
    val refreshUrl: String?,
    val scopes: Map<String, String>,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorPathItem(
    val path: String,
    val kind: GeneratorPathItemKind,
    val parameters: List<GeneratorParameter>,
    val operations: List<GeneratorOperation>,
)

internal enum class GeneratorPathItemKind {
    PATH,
    WEBHOOK,
    CALLBACK,
}

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
    val callbacks: List<GeneratorCallback>,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorCallback(
    val name: String,
    val pathItems: List<GeneratorPathItem>,
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
    val encoding: Map<String, GeneratorEncoding>,
    val prefixEncoding: List<GeneratorEncoding>,
    val itemEncoding: GeneratorEncoding?,
)

internal data class GeneratorEncoding(
    val contentType: String?,
    val headers: Map<String, GeneratorHeader>,
    val style: String?,
    val explode: Boolean?,
    val allowReserved: Boolean?,
    val encoding: Map<String, GeneratorEncoding>,
    val prefixEncoding: List<GeneratorEncoding>,
    val itemEncoding: GeneratorEncoding?,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorSecurityRequirements(
    val values: List<GeneratorSecurityRequirement>,
)

internal data class GeneratorSecurityRequirement(
    val schemes: Map<String, List<String>>,
)
