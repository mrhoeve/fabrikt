package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceOperationDocument(
    val externalDocumentation: SourceExternalDocumentation?,
    val tags: List<SourceTag>,
    val servers: SourceServers?,
    val security: SourceSecurityRequirements?,
    val paths: List<SourcePathItem>,
    val webhooks: List<SourcePathItem>,
    val reusablePathItems: Map<String, SourcePathItem>,
    val reusableCallbacks: Map<String, SourceCallback>,
    val reusableParameters: Map<String, SourceParameter>,
    val reusableRequestBodies: Map<String, SourceRequestBody>,
    val reusableResponses: Map<String, SourceResponse>,
    val reusableHeaders: Map<String, SourceHeader>,
    val reusableMediaTypes: Map<String, SourceMediaType>,
    val reusableExamples: Map<String, SourceExample>,
    val reusableLinks: Map<String, SourceLink>,
    val reusableSecuritySchemes: Map<String, SourceSecurityScheme>,
) {
    fun effectiveServersFor(
        pathItem: SourcePathItem,
        operation: SourceOperation,
    ): SourceServers? = operation.servers ?: pathItem.servers ?: servers

    fun effectiveSecurityFor(operation: SourceOperation): SourceSecurityRequirements? = operation.security ?: security
}

internal data class SourcePathItem(
    val location: String,
    val key: String,
    val kind: SourcePathItemKind,
    val node: JsonNode,
    val reference: String?,
    val summary: String?,
    val description: String?,
    val extensions: Map<String, JsonNode>,
    val servers: SourceServers?,
    val parameters: List<SourceParameter>,
    val operations: List<SourceOperation>,
)

internal enum class SourcePathItemKind {
    PATH,
    WEBHOOK,
    CALLBACK,
    REUSABLE_PATH_ITEM,
    REUSABLE_CALLBACK,
}

internal data class SourceOperation(
    val location: String,
    val method: SourceOperationMethod,
    val node: JsonNode,
    val operationId: String?,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    val externalDocumentation: SourceExternalDocumentation?,
    val servers: SourceServers?,
    val security: SourceSecurityRequirements?,
    val extensions: Map<String, JsonNode>,
    val parameters: List<SourceParameter>,
    val requestBody: SourceRequestBody?,
    val responses: SourceResponses?,
    val callbacks: List<SourceCallback>,
)

internal sealed interface SourceOperationMethod {
    val wireName: String

    data class Fixed(
        val value: SourceFixedOperationMethod,
    ) : SourceOperationMethod {
        override val wireName: String = value.wireName
    }

    data class Additional(
        override val wireName: String,
    ) : SourceOperationMethod
}

internal enum class SourceFixedOperationMethod(
    val fieldName: String,
    val wireName: String,
) {
    GET("get", "GET"),
    PUT("put", "PUT"),
    POST("post", "POST"),
    DELETE("delete", "DELETE"),
    OPTIONS("options", "OPTIONS"),
    HEAD("head", "HEAD"),
    PATCH("patch", "PATCH"),
    TRACE("trace", "TRACE"),
    QUERY("query", "QUERY"),
}

internal data class SourceCallback(
    val location: String,
    val name: String,
    val node: JsonNode,
    val reference: String?,
    val extensions: Map<String, JsonNode>,
    val pathItems: List<SourcePathItem>,
)

internal object SourceOperationDocumentParser {
    fun parse(
        root: JsonNode,
        version: OpenApiVersion?,
        schemaEntryPoints: Map<String, SourceSchema>,
    ): SourceOperationDocument = Collector(version, schemaEntryPoints).collect(root)

    private class Collector(
        private val version: OpenApiVersion?,
        private val schemaEntryPoints: Map<String, SourceSchema>,
    ) {
        fun collect(root: JsonNode): SourceOperationDocument =
            SourceOperationDocument(
                externalDocumentation = collectExternalDocumentation(root["externalDocs"], "#/externalDocs"),
                tags = collectTags(root["tags"], "#/tags"),
                servers = collectServers(root["servers"], "#/servers"),
                security = collectSecurityRequirements(root["security"], "#/security"),
                paths = collectPathItemMap(root["paths"], "#/paths", SourcePathItemKind.PATH, pathsOnly = true),
                webhooks =
                    if (version?.isAtLeast(3, 1) == true) {
                        collectPathItemMap(root["webhooks"], "#/webhooks", SourcePathItemKind.WEBHOOK)
                    } else {
                        emptyList()
                    },
                reusablePathItems =
                    if (version?.isAtLeast(3, 1) == true) {
                        collectNamedPathItems(
                            root.path("components").path("pathItems"),
                            "#/components/pathItems",
                            SourcePathItemKind.REUSABLE_PATH_ITEM,
                        )
                    } else {
                        emptyMap()
                    },
                reusableCallbacks =
                    collectNamedCallbacks(
                        root.path("components").path("callbacks"),
                        "#/components/callbacks",
                        SourcePathItemKind.REUSABLE_CALLBACK,
                    ),
                reusableParameters =
                    collectNamedParameters(
                        root.path("components").path("parameters"),
                        "#/components/parameters",
                    ),
                reusableRequestBodies =
                    collectNamedRequestBodies(
                        root.path("components").path("requestBodies"),
                        "#/components/requestBodies",
                    ),
                reusableResponses =
                    collectNamedResponses(
                        root.path("components").path("responses"),
                        "#/components/responses",
                    ),
                reusableHeaders =
                    collectNamedHeaders(
                        root.path("components").path("headers"),
                        "#/components/headers",
                    ),
                reusableMediaTypes =
                    if (supportsOpenApi32) {
                        collectNamedMediaTypes(
                            root.path("components").path("mediaTypes"),
                            "#/components/mediaTypes",
                        )
                    } else {
                        emptyMap()
                    },
                reusableExamples =
                    collectExamples(
                        root.path("components").path("examples"),
                        "#/components/examples",
                    ),
                reusableLinks =
                    collectLinks(
                        root.path("components").path("links"),
                        "#/components/links",
                    ),
                reusableSecuritySchemes =
                    collectNamedSecuritySchemes(
                        root.path("components").path("securitySchemes"),
                        "#/components/securitySchemes",
                    ),
            )

        private fun collectExternalDocumentation(
            externalDocumentation: JsonNode?,
            location: String,
        ): SourceExternalDocumentation? {
            if (externalDocumentation?.isObject != true) return null

            return SourceExternalDocumentation(
                location = location,
                node = externalDocumentation,
                description = externalDocumentation.text("description"),
                url = externalDocumentation.text("url"),
                extensions = externalDocumentation.extensions(),
            )
        }

        private fun collectTags(
            tags: JsonNode?,
            location: String,
        ): List<SourceTag> {
            if (tags?.isArray != true) return emptyList()

            return tags.mapIndexedNotNull { index, tag ->
                tag.takeIf(JsonNode::isObject)?.let { collectTag(it, "$location/$index") }
            }
        }

        private fun collectTag(
            tag: JsonNode,
            location: String,
        ): SourceTag =
            SourceTag(
                location = location,
                node = tag,
                name = tag.text("name"),
                summary = if (supportsOpenApi32) tag.text("summary") else null,
                description = tag.text("description"),
                externalDocumentation = collectExternalDocumentation(tag["externalDocs"], "$location/externalDocs"),
                parent = if (supportsOpenApi32) tag.text("parent") else null,
                kind = if (supportsOpenApi32) tag.text("kind") else null,
                extensions = tag.extensions(),
            )

        private fun collectServers(
            servers: JsonNode?,
            location: String,
        ): SourceServers? {
            if (servers?.isArray != true) return null

            return SourceServers(
                location = location,
                node = servers,
                values =
                    servers.mapIndexedNotNull { index, server ->
                        server
                            .takeIf(JsonNode::isObject)
                            ?.let { collectServer(it, "$location/$index") }
                    },
            )
        }

        private fun collectServer(
            server: JsonNode,
            location: String,
        ): SourceServer =
            SourceServer(
                location = location,
                node = server,
                url = server.text("url"),
                description = server.text("description"),
                name = if (supportsOpenApi32) server.text("name") else null,
                variables = collectServerVariables(server["variables"], "$location/variables"),
                extensions = server.extensions(),
            )

        private fun collectServerVariables(
            variables: JsonNode?,
            location: String,
        ): Map<String, SourceServerVariable> {
            if (variables?.isObject != true) return emptyMap()

            return variables.properties().associate { (name, variable) ->
                name to
                    SourceServerVariable(
                        location = "$location/${name.toJsonPointerToken()}",
                        name = name,
                        node = variable,
                        enumValues =
                            variable["enum"]
                                ?.takeIf(JsonNode::isArray)
                                ?.mapNotNull { it.takeIf(JsonNode::isTextual)?.textValue() }
                                .orEmpty(),
                        defaultValue = variable.text("default"),
                        description = variable.text("description"),
                        extensions = variable.extensions(),
                    )
            }
        }

        private fun collectNamedSecuritySchemes(
            securitySchemes: JsonNode,
            location: String,
        ): Map<String, SourceSecurityScheme> {
            if (!securitySchemes.isObject) return emptyMap()

            return securitySchemes.properties().associate { (name, securityScheme) ->
                name to collectSecurityScheme(securityScheme, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectSecurityScheme(
            securityScheme: JsonNode,
            location: String,
            key: String,
        ): SourceSecurityScheme =
            SourceSecurityScheme(
                location = location,
                key = key,
                node = securityScheme,
                reference = securityScheme.text("\$ref"),
                type = securityScheme.text("type")?.let(::parseSecuritySchemeType),
                description = securityScheme.text("description"),
                name = securityScheme.text("name"),
                placement = securityScheme.text("in")?.let(::parseApiKeyPlacement),
                scheme = securityScheme.text("scheme"),
                bearerFormat = securityScheme.text("bearerFormat"),
                flows = collectOAuthFlows(securityScheme["flows"], "$location/flows"),
                openIdConnectUrl = securityScheme.text("openIdConnectUrl"),
                oauth2MetadataUrl = if (supportsOpenApi32) securityScheme.text("oauth2MetadataUrl") else null,
                deprecated = supportsOpenApi32 && securityScheme.boolean("deprecated") == true,
                extensions = securityScheme.extensions(),
            )

        private fun collectOAuthFlows(
            flows: JsonNode?,
            location: String,
        ): SourceOAuthFlows? {
            if (flows?.isObject != true) return null

            return SourceOAuthFlows(
                location = location,
                node = flows,
                values =
                    flows
                        .properties()
                        .filterNot { (name, _) -> name.isSpecificationExtension() }
                        .mapNotNull { (name, flow) ->
                            flow
                                .takeIf(JsonNode::isObject)
                                ?.let { collectOAuthFlow(it, "$location/${name.toJsonPointerToken()}", name) }
                        }.toList(),
                extensions = flows.extensions(),
            )
        }

        private fun collectOAuthFlow(
            flow: JsonNode,
            location: String,
            key: String,
        ): SourceOAuthFlow =
            SourceOAuthFlow(
                location = location,
                key = key,
                type = parseOAuthFlowType(key),
                node = flow,
                authorizationUrl = flow.text("authorizationUrl"),
                deviceAuthorizationUrl = if (supportsOpenApi32) flow.text("deviceAuthorizationUrl") else null,
                tokenUrl = flow.text("tokenUrl"),
                refreshUrl = flow.text("refreshUrl"),
                scopes =
                    flow["scopes"]
                        ?.takeIf(JsonNode::isObject)
                        ?.properties()
                        ?.mapNotNull { (name, description) -> description.takeIf(JsonNode::isTextual)?.let { name to it.textValue() } }
                        ?.toMap()
                        .orEmpty(),
                extensions = flow.extensions(),
            )

        private fun parseOAuthFlowType(value: String): SourceOAuthFlowType {
            val fixed = FIXED_OAUTH_FLOW_TYPES_BY_VALUE[value]
            return if (fixed == null || fixed == SourceFixedOAuthFlowType.DEVICE_AUTHORIZATION && !supportsOpenApi32) {
                SourceOAuthFlowType.Unrecognised(value)
            } else {
                SourceOAuthFlowType.Fixed(fixed)
            }
        }

        private fun parseSecuritySchemeType(value: String): SourceSecuritySchemeType {
            val fixed = FIXED_SECURITY_SCHEME_TYPES_BY_VALUE[value]
            return if (fixed == null || fixed == SourceFixedSecuritySchemeType.MUTUAL_TLS && !supportsOpenApi31) {
                SourceSecuritySchemeType.Unrecognised(value)
            } else {
                SourceSecuritySchemeType.Fixed(fixed)
            }
        }

        private fun parseApiKeyPlacement(value: String): SourceApiKeyPlacement =
            FIXED_API_KEY_PLACEMENTS_BY_VALUE[value]
                ?.let(SourceApiKeyPlacement::Fixed)
                ?: SourceApiKeyPlacement.Unrecognised(value)

        private fun collectNamedHeaders(
            headers: JsonNode,
            location: String,
        ): Map<String, SourceHeader> = collectHeaders(headers, location)

        private fun collectNamedResponses(
            responses: JsonNode,
            location: String,
        ): Map<String, SourceResponse> {
            if (!responses.isObject) return emptyMap()

            return responses.properties().associate { (name, response) ->
                name to collectResponse(response, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectNamedRequestBodies(
            requestBodies: JsonNode,
            location: String,
        ): Map<String, SourceRequestBody> {
            if (!requestBodies.isObject) return emptyMap()

            return requestBodies.properties().associate { (name, requestBody) ->
                name to checkNotNull(collectRequestBody(requestBody, "$location/${name.toJsonPointerToken()}"))
            }
        }

        private fun collectNamedMediaTypes(
            mediaTypes: JsonNode,
            location: String,
        ): Map<String, SourceMediaType> {
            if (!mediaTypes.isObject) return emptyMap()

            return mediaTypes.properties().associate { (name, mediaType) ->
                name to collectMediaType(mediaType, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectNamedParameters(
            parameters: JsonNode,
            location: String,
        ): Map<String, SourceParameter> {
            if (!parameters.isObject) return emptyMap()

            return parameters.properties().associate { (name, parameter) ->
                name to collectParameter(parameter, "$location/${name.toJsonPointerToken()}")
            }
        }

        private fun collectExamples(
            examples: JsonNode?,
            location: String,
        ): Map<String, SourceExample> {
            if (examples?.isObject != true) return emptyMap()

            return examples.properties().associate { (name, example) ->
                name to collectExample(example, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectExample(
            example: JsonNode,
            location: String,
            name: String,
        ): SourceExample =
            SourceExample(
                location = location,
                name = name,
                node = example,
                reference = example.text("\$ref"),
                summary = example.text("summary"),
                description = example.text("description"),
                value = example["value"],
                externalValue = example.text("externalValue"),
                dataValue = if (supportsOpenApi32) example["dataValue"] else null,
                serializedValue = if (supportsOpenApi32) example.text("serializedValue") else null,
                extensions = example.extensions(),
            )

        private fun collectLinks(
            links: JsonNode?,
            location: String,
        ): Map<String, SourceLink> {
            if (links?.isObject != true) return emptyMap()

            return links.properties().associate { (name, link) ->
                name to collectLink(link, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectLink(
            link: JsonNode,
            location: String,
            name: String,
        ): SourceLink =
            SourceLink(
                location = location,
                name = name,
                node = link,
                reference = link.text("\$ref"),
                operationReference = link.text("operationRef"),
                operationId = link.text("operationId"),
                parameters =
                    link["parameters"]
                        ?.takeIf(JsonNode::isObject)
                        ?.properties()
                        ?.associate { (parameterName, value) -> parameterName to value }
                        .orEmpty(),
                requestBody = link["requestBody"],
                description = link.text("description"),
                server = link["server"]?.takeIf(JsonNode::isObject)?.let { collectServer(it, "$location/server") },
                extensions = link.extensions(),
            )

        private fun collectNamedPathItems(
            pathItems: JsonNode,
            location: String,
            kind: SourcePathItemKind,
        ): Map<String, SourcePathItem> {
            if (!pathItems.isObject) return emptyMap()

            return pathItems.properties().associate { (name, pathItem) ->
                name to collectPathItem(pathItem, "$location/${name.toJsonPointerToken()}", name, kind)
            }
        }

        private fun collectNamedCallbacks(
            callbacks: JsonNode,
            location: String,
            pathItemKind: SourcePathItemKind,
        ): Map<String, SourceCallback> {
            if (!callbacks.isObject) return emptyMap()

            return callbacks.properties().associate { (name, callback) ->
                name to collectCallback(callback, "$location/${name.toJsonPointerToken()}", name, pathItemKind)
            }
        }

        private fun collectPathItemMap(
            pathItems: JsonNode?,
            location: String,
            kind: SourcePathItemKind,
            pathsOnly: Boolean = false,
            skipSpecificationExtensions: Boolean = false,
        ): List<SourcePathItem> {
            if (pathItems?.isObject != true) return emptyList()

            return pathItems
                .properties()
                .filter { (key, _) -> !pathsOnly || key.startsWith('/') }
                .filterNot { (key, _) -> skipSpecificationExtensions && key.isSpecificationExtension() }
                .map { (key, pathItem) ->
                    collectPathItem(pathItem, "$location/${key.toJsonPointerToken()}", key, kind)
                }.toList()
        }

        private fun collectPathItem(
            pathItem: JsonNode,
            location: String,
            key: String,
            kind: SourcePathItemKind,
        ): SourcePathItem =
            SourcePathItem(
                location = location,
                key = key,
                kind = kind,
                node = pathItem,
                reference = pathItem.text("\$ref"),
                summary = pathItem.text("summary"),
                description = pathItem.text("description"),
                extensions = pathItem.extensions(),
                servers = collectServers(pathItem["servers"], "$location/servers"),
                parameters = collectParameters(pathItem["parameters"], "$location/parameters"),
                operations = collectOperations(pathItem, location),
            )

        private fun collectOperations(
            pathItem: JsonNode,
            location: String,
        ): List<SourceOperation> =
            pathItem
                .properties()
                .flatMap { (fieldName, value) ->
                    val method = FIXED_METHODS_BY_FIELD[fieldName]
                    when {
                        method == SourceFixedOperationMethod.QUERY && !supportsOpenApi32 -> emptySequence()
                        method != null && value.isObject ->
                            sequenceOf(collectOperation(value, "$location/$fieldName", SourceOperationMethod.Fixed(method)))
                        fieldName == "additionalOperations" && supportsOpenApi32 -> collectAdditionalOperations(value, location)
                        else -> emptySequence()
                    }
                }.toList()

        private fun collectAdditionalOperations(
            additionalOperations: JsonNode,
            location: String,
        ): Sequence<SourceOperation> {
            if (!additionalOperations.isObject) return emptySequence()

            return additionalOperations
                .properties()
                .asSequence()
                .mapNotNull { (method, operation) ->
                    operation
                        .takeIf(JsonNode::isObject)
                        ?.let {
                            collectOperation(
                                it,
                                "$location/additionalOperations/${method.toJsonPointerToken()}",
                                SourceOperationMethod.Additional(method),
                            )
                        }
                }
        }

        private fun collectOperation(
            operation: JsonNode,
            location: String,
            method: SourceOperationMethod,
        ): SourceOperation =
            SourceOperation(
                location = location,
                method = method,
                node = operation,
                operationId = operation.text("operationId"),
                summary = operation.text("summary"),
                description = operation.text("description"),
                tags = operation["tags"]?.takeIf(JsonNode::isArray)?.mapNotNull { it.takeIf(JsonNode::isTextual)?.textValue() }.orEmpty(),
                deprecated = operation["deprecated"]?.takeIf(JsonNode::isBoolean)?.booleanValue() ?: false,
                externalDocumentation = collectExternalDocumentation(operation["externalDocs"], "$location/externalDocs"),
                servers = collectServers(operation["servers"], "$location/servers"),
                security = collectSecurityRequirements(operation["security"], "$location/security"),
                extensions = operation.extensions(),
                parameters = collectParameters(operation["parameters"], "$location/parameters"),
                requestBody = collectRequestBody(operation["requestBody"], "$location/requestBody"),
                responses = collectResponses(operation["responses"], "$location/responses"),
                callbacks = collectOperationCallbacks(operation, location),
            )

        private fun collectSecurityRequirements(
            security: JsonNode?,
            location: String,
        ): SourceSecurityRequirements? {
            if (security?.isArray != true) return null

            return SourceSecurityRequirements(
                location = location,
                node = security,
                values =
                    security.mapIndexedNotNull { index, requirement ->
                        requirement
                            .takeIf(JsonNode::isObject)
                            ?.let { collectSecurityRequirement(it, "$location/$index") }
                    },
            )
        }

        private fun collectSecurityRequirement(
            requirement: JsonNode,
            location: String,
        ): SourceSecurityRequirement =
            SourceSecurityRequirement(
                location = location,
                node = requirement,
                schemes =
                    requirement.properties().associate { (name, scopes) ->
                        name to scopes.takeIf(JsonNode::isArray)?.mapNotNull { it.takeIf(JsonNode::isTextual)?.textValue() }.orEmpty()
                    },
            )

        private fun collectResponses(
            responses: JsonNode?,
            location: String,
        ): SourceResponses? {
            if (responses?.isObject != true) return null

            return SourceResponses(
                location = location,
                node = responses,
                values =
                    responses
                        .properties()
                        .filterNot { (key, _) -> key.isSpecificationExtension() }
                        .map { (key, response) -> collectResponse(response, "$location/${key.toJsonPointerToken()}", key) }
                        .toList(),
                extensions = responses.extensions(),
            )
        }

        private fun collectResponse(
            response: JsonNode,
            location: String,
            key: String,
        ): SourceResponse =
            SourceResponse(
                location = location,
                key = key,
                node = response,
                reference = response.text("\$ref"),
                description = response.text("description"),
                headers = collectHeaders(response["headers"], "$location/headers"),
                content = collectMediaTypes(response["content"], "$location/content"),
                links = collectLinks(response["links"], "$location/links"),
                extensions = response.extensions(),
            )

        private fun collectHeaders(
            headers: JsonNode?,
            location: String,
        ): Map<String, SourceHeader> {
            if (headers?.isObject != true) return emptyMap()

            return headers.properties().associate { (name, header) ->
                name to collectHeader(header, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectHeader(
            header: JsonNode,
            location: String,
            name: String,
        ): SourceHeader =
            SourceHeader(
                location = location,
                name = name,
                node = header,
                reference = header.text("\$ref"),
                description = header.text("description"),
                required = header.boolean("required") ?: false,
                deprecated = header.boolean("deprecated") ?: false,
                allowEmptyValue = header.boolean("allowEmptyValue"),
                style = header.text("style"),
                explode = header.boolean("explode"),
                schema = schemaEntryPoints["$location/schema"],
                content = collectMediaTypes(header["content"], "$location/content"),
                example = header["example"],
                examples = collectExamples(header["examples"], "$location/examples"),
                extensions = header.extensions(),
            )

        private fun collectRequestBody(
            requestBody: JsonNode?,
            location: String,
        ): SourceRequestBody? {
            if (requestBody?.isObject != true) return null

            return SourceRequestBody(
                location = location,
                node = requestBody,
                reference = requestBody.text("\$ref"),
                description = requestBody.text("description"),
                required = requestBody.boolean("required") ?: false,
                content = collectMediaTypes(requestBody["content"], "$location/content"),
                extensions = requestBody.extensions(),
            )
        }

        private fun collectParameters(
            parameters: JsonNode?,
            location: String,
        ): List<SourceParameter> {
            if (parameters?.isArray != true) return emptyList()

            return parameters.mapIndexed { index, parameter ->
                collectParameter(parameter, "$location/$index")
            }
        }

        private fun collectParameter(
            parameter: JsonNode,
            location: String,
        ): SourceParameter =
            SourceParameter(
                location = location,
                node = parameter,
                reference = parameter.text("\$ref"),
                name = parameter.text("name"),
                placement = parameter.text("in")?.let(::parseParameterPlacement),
                description = parameter.text("description"),
                required = parameter.boolean("required") ?: false,
                deprecated = parameter.boolean("deprecated") ?: false,
                allowEmptyValue = parameter.boolean("allowEmptyValue"),
                style = parameter.text("style"),
                explode = parameter.boolean("explode"),
                allowReserved = parameter.boolean("allowReserved"),
                schema = schemaEntryPoints["$location/schema"],
                content = collectMediaTypes(parameter["content"], "$location/content"),
                example = parameter["example"],
                examples = collectExamples(parameter["examples"], "$location/examples"),
                extensions = parameter.extensions(),
            )

        private fun collectMediaTypes(
            content: JsonNode?,
            location: String,
        ): List<SourceMediaType> {
            if (content?.isObject != true) return emptyList()

            return content.properties().map { (mediaType, mediaTypeObject) ->
                val mediaTypeLocation = "$location/${mediaType.toJsonPointerToken()}"
                collectMediaType(mediaTypeObject, mediaTypeLocation, mediaType)
            }
        }

        private fun collectMediaType(
            mediaType: JsonNode,
            location: String,
            key: String,
        ): SourceMediaType =
            SourceMediaType(
                location = location,
                key = key,
                node = mediaType,
                reference = mediaType.text("\$ref"),
                schema = schemaEntryPoints["$location/schema"],
                itemSchema = schemaEntryPoints["$location/itemSchema"],
                encoding = collectEncodings(mediaType["encoding"], "$location/encoding"),
                prefixEncoding =
                    if (supportsOpenApi32) {
                        collectEncodingArray(mediaType["prefixEncoding"], "$location/prefixEncoding")
                    } else {
                        emptyList()
                    },
                itemEncoding =
                    if (supportsOpenApi32) {
                        collectOptionalEncoding(mediaType["itemEncoding"], "$location/itemEncoding")
                    } else {
                        null
                    },
                example = mediaType["example"],
                examples = collectExamples(mediaType["examples"], "$location/examples"),
                extensions = mediaType.extensions(),
            )

        private fun collectEncodings(
            encodings: JsonNode?,
            location: String,
        ): Map<String, SourceEncoding> {
            if (encodings?.isObject != true) return emptyMap()

            return encodings.properties().associate { (name, encoding) ->
                name to collectEncoding(encoding, "$location/${name.toJsonPointerToken()}", name)
            }
        }

        private fun collectEncoding(
            encoding: JsonNode,
            location: String,
            key: String?,
        ): SourceEncoding =
            SourceEncoding(
                location = location,
                key = key,
                node = encoding,
                contentType = encoding.text("contentType"),
                headers = collectHeaders(encoding["headers"], "$location/headers"),
                style = encoding.text("style"),
                explode = encoding.boolean("explode"),
                allowReserved = encoding.boolean("allowReserved"),
                encoding =
                    if (supportsOpenApi32) {
                        collectEncodings(encoding["encoding"], "$location/encoding")
                    } else {
                        emptyMap()
                    },
                prefixEncoding =
                    if (supportsOpenApi32) {
                        collectEncodingArray(encoding["prefixEncoding"], "$location/prefixEncoding")
                    } else {
                        emptyList()
                    },
                itemEncoding =
                    if (supportsOpenApi32) {
                        collectOptionalEncoding(encoding["itemEncoding"], "$location/itemEncoding")
                    } else {
                        null
                    },
                extensions = encoding.extensions(),
            )

        private fun collectEncodingArray(
            encodings: JsonNode?,
            location: String,
        ): List<SourceEncoding> {
            if (encodings?.isArray != true) return emptyList()

            return encodings.mapIndexed { index, encoding ->
                collectEncoding(encoding, "$location/$index", null)
            }
        }

        private fun collectOptionalEncoding(
            encoding: JsonNode?,
            location: String,
        ): SourceEncoding? = encoding?.takeIf(JsonNode::isObject)?.let { collectEncoding(it, location, null) }

        private fun parseParameterPlacement(value: String): SourceParameterPlacement {
            val fixed = FIXED_PARAMETER_PLACEMENTS_BY_VALUE[value]
            return if (fixed == null || fixed == SourceFixedParameterPlacement.QUERYSTRING && !supportsOpenApi32) {
                SourceParameterPlacement.Unrecognised(value)
            } else {
                SourceParameterPlacement.Fixed(fixed)
            }
        }

        private fun collectOperationCallbacks(
            operation: JsonNode,
            location: String,
        ): List<SourceCallback> {
            val callbacks = operation["callbacks"]?.takeIf(JsonNode::isObject) ?: return emptyList()
            return callbacks
                .properties()
                .map { (name, callback) ->
                    collectCallback(
                        callback = callback,
                        location = "$location/callbacks/${name.toJsonPointerToken()}",
                        name = name,
                        pathItemKind = SourcePathItemKind.CALLBACK,
                    )
                }.toList()
        }

        private fun collectCallback(
            callback: JsonNode,
            location: String,
            name: String,
            pathItemKind: SourcePathItemKind,
        ): SourceCallback {
            val reference = callback.text("\$ref")
            return SourceCallback(
                location = location,
                name = name,
                node = callback,
                reference = reference,
                extensions = callback.extensions(),
                pathItems =
                    if (reference == null) {
                        collectPathItemMap(
                            callback,
                            location,
                            pathItemKind,
                            skipSpecificationExtensions = true,
                        )
                    } else {
                        emptyList()
                    },
            )
        }

        private fun JsonNode.text(fieldName: String): String? = this[fieldName]?.takeIf(JsonNode::isTextual)?.textValue()

        private fun JsonNode.boolean(fieldName: String): Boolean? = this[fieldName]?.takeIf(JsonNode::isBoolean)?.booleanValue()

        private fun JsonNode.extensions(): Map<String, JsonNode> =
            takeIf(JsonNode::isObject)
                ?.properties()
                ?.filter { (name, _) -> name.isSpecificationExtension() }
                ?.associate { (name, value) -> name to value }
                .orEmpty()

        private fun String.toJsonPointerToken(): String = replace("~", "~0").replace("/", "~1")

        private fun String.isSpecificationExtension(): Boolean = startsWith("x-", ignoreCase = true)

        private companion object {
            val FIXED_METHODS_BY_FIELD = SourceFixedOperationMethod.entries.associateBy(SourceFixedOperationMethod::fieldName)
            val FIXED_PARAMETER_PLACEMENTS_BY_VALUE =
                SourceFixedParameterPlacement.entries.associateBy(SourceFixedParameterPlacement::value)
            val FIXED_SECURITY_SCHEME_TYPES_BY_VALUE =
                SourceFixedSecuritySchemeType.entries.associateBy(SourceFixedSecuritySchemeType::value)
            val FIXED_API_KEY_PLACEMENTS_BY_VALUE =
                SourceFixedApiKeyPlacement.entries.associateBy(SourceFixedApiKeyPlacement::value)
            val FIXED_OAUTH_FLOW_TYPES_BY_VALUE =
                SourceFixedOAuthFlowType.entries.associateBy(SourceFixedOAuthFlowType::value)
        }

        private val supportsOpenApi31: Boolean
            get() = version?.isAtLeast(3, 1) == true

        private val supportsOpenApi32: Boolean
            get() = version?.isAtLeast(3, 2) == true
    }
}
