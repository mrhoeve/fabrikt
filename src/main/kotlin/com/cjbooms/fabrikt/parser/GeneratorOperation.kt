package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class GeneratorOperationDocument(
    val serverUrl: String?,
    val basePath: String,
    val security: GeneratorSecurityRequirements?,
    val securitySchemes: Map<String, GeneratorSecurityScheme>,
    val paths: List<GeneratorPathItem>,
    val webhooks: List<GeneratorPathItem>,
    val externalDocumentation: GeneratorExternalDocumentation? = null,
    val tags: List<GeneratorTag> = emptyList(),
    val servers: List<GeneratorServer> = emptyList(),
    val reusableExamples: Map<String, GeneratorExample> = emptyMap(),
    val reusableLinks: Map<String, GeneratorLink> = emptyMap(),
    val reusableHeaders: Map<String, GeneratorHeader> = emptyMap(),
    val reusableMediaTypes: Map<String, GeneratorMediaType> = emptyMap(),
)

internal data class GeneratorTag(
    val name: String?,
    val summary: String?,
    val description: String?,
    val externalDocumentation: GeneratorExternalDocumentation?,
    val parent: String?,
    val kind: String?,
    val extensions: Map<String, JsonNode>,
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
    val summary: String? = null,
    val description: String? = null,
    val servers: List<GeneratorServer> = emptyList(),
    val extensions: Map<String, JsonNode> = emptyMap(),
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
    val externalDocumentation: GeneratorExternalDocumentation? = null,
    val servers: List<GeneratorServer> = emptyList(),
)

internal data class GeneratorExternalDocumentation(
    val description: String?,
    val url: String?,
    val extensions: Map<String, JsonNode> = emptyMap(),
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
    val style: String?,
    val explode: Boolean?,
    val allowReserved: Boolean?,
    val schema: GeneratorSchema?,
    val content: List<GeneratorMediaType>,
    val allowEmptyValue: Boolean? = null,
    val example: JsonNode? = null,
    val examples: Map<String, GeneratorExample> = emptyMap(),
    val extensions: Map<String, JsonNode> = emptyMap(),
)

internal data class GeneratorRequestBody(
    val description: String?,
    val required: Boolean,
    val content: List<GeneratorMediaType>,
    val extensions: Map<String, JsonNode> = emptyMap(),
)

internal data class GeneratorResponse(
    val status: String,
    val description: String?,
    val headers: Map<String, GeneratorHeader>,
    val content: List<GeneratorMediaType>,
    val links: Map<String, GeneratorLink> = emptyMap(),
    val extensions: Map<String, JsonNode> = emptyMap(),
)

internal data class GeneratorHeader(
    val name: String,
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
    val explode: Boolean?,
    val schema: GeneratorSchema?,
    val content: List<GeneratorMediaType>,
    val allowEmptyValue: Boolean? = null,
    val style: String? = null,
    val example: JsonNode? = null,
    val examples: Map<String, GeneratorExample> = emptyMap(),
    val extensions: Map<String, JsonNode> = emptyMap(),
)

internal data class GeneratorMediaType(
    val key: String,
    val schema: GeneratorSchema?,
    val itemSchema: GeneratorSchema?,
    val encoding: Map<String, GeneratorEncoding>,
    val prefixEncoding: List<GeneratorEncoding>,
    val itemEncoding: GeneratorEncoding?,
    val example: JsonNode? = null,
    val examples: Map<String, GeneratorExample> = emptyMap(),
    val extensions: Map<String, JsonNode> = emptyMap(),
)

internal data class GeneratorExample(
    val name: String,
    val summary: String?,
    val description: String?,
    val value: JsonNode?,
    val externalValue: String?,
    val dataValue: JsonNode?,
    val serializedValue: String?,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorLink(
    val name: String,
    val operationReference: String?,
    val operationId: String?,
    val parameters: Map<String, JsonNode>,
    val requestBody: JsonNode?,
    val description: String?,
    val server: GeneratorServer?,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorServer(
    val url: String?,
    val description: String?,
    val name: String?,
    val variables: Map<String, GeneratorServerVariable>,
    val extensions: Map<String, JsonNode>,
)

internal data class GeneratorServerVariable(
    val name: String,
    val enumValues: List<String>,
    val defaultValue: String?,
    val description: String?,
    val extensions: Map<String, JsonNode>,
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

internal data class GeneratorOperationSecurity(
    val alternatives: List<GeneratorSecurityAlternative>,
)

internal data class GeneratorSecurityAlternative(
    val schemes: List<GeneratorSecuritySelection>,
)

internal data class GeneratorSecuritySelection(
    val name: String,
    val scopes: List<String>,
    val scheme: GeneratorSecurityScheme?,
)
