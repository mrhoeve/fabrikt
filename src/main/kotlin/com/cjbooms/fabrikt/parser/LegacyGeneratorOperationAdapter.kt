package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.YamlUtils
import com.reprezen.jsonoverlay.Overlay
import com.reprezen.kaizen.oasparser.model3.Callback
import com.reprezen.kaizen.oasparser.model3.Header
import com.reprezen.kaizen.oasparser.model3.MediaType
import com.reprezen.kaizen.oasparser.model3.OAuthFlow
import com.reprezen.kaizen.oasparser.model3.OpenApi3
import com.reprezen.kaizen.oasparser.model3.Operation
import com.reprezen.kaizen.oasparser.model3.Parameter
import com.reprezen.kaizen.oasparser.model3.Path
import com.reprezen.kaizen.oasparser.model3.RequestBody
import com.reprezen.kaizen.oasparser.model3.Response
import com.reprezen.kaizen.oasparser.model3.Schema
import com.reprezen.kaizen.oasparser.model3.SecurityRequirement
import com.reprezen.kaizen.oasparser.model3.SecurityScheme
import java.net.URI

internal class LegacyGeneratorOperationAdapter {
    private val schemaAdapter = LegacyGeneratorSchemaAdapter()

    fun adapt(api: OpenApi3): GeneratorOperationDocument =
        GeneratorOperationDocument(
            serverUrl = api.servers.firstOrNull()?.url,
            basePath =
                api.servers
                    .firstOrNull()
                    ?.url
                    ?.let { url -> runCatching { URI.create(url).path }.getOrNull() }
                    .orEmpty()
                    .removeSuffix("/"),
            security = api.securityRequirements.takeIf(List<SecurityRequirement>::isNotEmpty)?.toGeneratorSecurityRequirements(),
            securitySchemes = api.securitySchemes.mapValues { (name, scheme) -> scheme.toGeneratorSecurityScheme(name) },
            paths = api.paths.map { (path, value) -> value.toGeneratorPathItem(path) },
            webhooks = emptyList(),
        )

    private fun Path.toGeneratorPathItem(
        path: String,
        kind: GeneratorPathItemKind = GeneratorPathItemKind.PATH,
    ): GeneratorPathItem =
        GeneratorPathItem(
            path = path,
            kind = kind,
            parameters = parameters.map { it.toGeneratorParameter() },
            operations = operations.map { (method, operation) -> operation.toGeneratorOperation(method) },
        )

    private fun Operation.toGeneratorOperation(method: String): GeneratorOperation =
        GeneratorOperation(
            method = method,
            operationId = operationId,
            summary = summary,
            description = description,
            tags = tags.orEmpty(),
            deprecated = isDeprecated,
            parameters = parameters.map { it.toGeneratorParameter() },
            requestBody = requestBody.takeIfPresent()?.toGeneratorRequestBody(),
            responses = responses.map { (status, response) -> response.toGeneratorResponse(status) },
            security =
                if (hasSecurityRequirements()) {
                    securityRequirements.toGeneratorSecurityRequirements()
                } else {
                    null
                },
            callbacks = callbacks.map { (name, callback) -> callback.toGeneratorCallback(name) },
            extensions = extensions.mapValues { (_, value) -> YamlUtils.objectMapper.valueToTree(value) },
        )

    private fun Callback.toGeneratorCallback(name: String): GeneratorCallback =
        GeneratorCallback(
            name = name,
            pathItems = callbackPaths.map { (path, value) -> value.toGeneratorPathItem(path, GeneratorPathItemKind.CALLBACK) },
            extensions = extensions.mapValues { (_, value) -> YamlUtils.objectMapper.valueToTree(value) },
        )

    private fun Parameter.toGeneratorParameter(): GeneratorParameter =
        GeneratorParameter(
            name = name,
            placement = `in`,
            description = description,
            required = isRequired,
            deprecated = isDeprecated,
            explode = explode,
            schema = schema.takeIfPresent()?.let(schemaAdapter::adapt),
            content = contentMediaTypes.map { (key, value) -> value.toGeneratorMediaType(key) },
        )

    private fun RequestBody.toGeneratorRequestBody(): GeneratorRequestBody =
        GeneratorRequestBody(
            description = description,
            required = isRequired,
            content = contentMediaTypes.map { (key, value) -> value.toGeneratorMediaType(key) },
        )

    private fun Response.toGeneratorResponse(status: String): GeneratorResponse =
        GeneratorResponse(
            status = status,
            description = description,
            headers = headers.mapValues { (name, header) -> header.toGeneratorHeader(name) },
            content = contentMediaTypes.map { (key, value) -> value.toGeneratorMediaType(key) },
        )

    private fun Header.toGeneratorHeader(name: String): GeneratorHeader =
        GeneratorHeader(
            name = name,
            description = description,
            required = isRequired,
            deprecated = isDeprecated,
            explode = explode,
            schema = schema.takeIfPresent()?.let(schemaAdapter::adapt),
            content = contentMediaTypes.map { (key, value) -> value.toGeneratorMediaType(key) },
        )

    private fun MediaType.toGeneratorMediaType(key: String): GeneratorMediaType =
        GeneratorMediaType(
            key = key,
            schema = schema.takeIfPresent()?.let(schemaAdapter::adapt),
            itemSchema = null,
            encoding =
                encodingProperties.mapValues { (_, value) ->
                    GeneratorEncoding(
                        contentType = value.contentType,
                        headers = emptyMap(),
                        style = value.style,
                        explode = value.explode,
                        allowReserved = value.allowReserved,
                        encoding = emptyMap(),
                        prefixEncoding = emptyList(),
                        itemEncoding = null,
                        extensions = emptyMap(),
                    )
                },
            prefixEncoding = emptyList(),
            itemEncoding = null,
        )

    private fun List<SecurityRequirement>.toGeneratorSecurityRequirements(): GeneratorSecurityRequirements =
        GeneratorSecurityRequirements(
            map { requirement ->
                GeneratorSecurityRequirement(
                    requirement.requirements.mapValues { (_, securityParameter) -> securityParameter.parameters.orEmpty() },
                )
            },
        )

    private fun SecurityScheme.toGeneratorSecurityScheme(name: String): GeneratorSecurityScheme =
        GeneratorSecurityScheme(
            name = name,
            type = type,
            description = description,
            parameterName = this.name,
            placement = `in`,
            scheme = scheme,
            bearerFormat = bearerFormat,
            flows =
                listOfNotNull(
                    implicitOAuthFlow.takeIfContent()?.toGeneratorOAuthFlow("implicit"),
                    passwordOAuthFlow.takeIfContent()?.toGeneratorOAuthFlow("password"),
                    clientCredentialsOAuthFlow.takeIfContent()?.toGeneratorOAuthFlow("clientCredentials"),
                    authorizationCodeOAuthFlow.takeIfContent()?.toGeneratorOAuthFlow("authorizationCode"),
                ),
            openIdConnectUrl = openIdConnectUrl,
            oauth2MetadataUrl = null,
            deprecated = false,
            extensions = extensions.mapValues { (_, value) -> YamlUtils.objectMapper.valueToTree(value) },
        )

    private fun OAuthFlow.toGeneratorOAuthFlow(type: String): GeneratorOAuthFlow =
        GeneratorOAuthFlow(
            type = type,
            authorizationUrl = authorizationUrl,
            deviceAuthorizationUrl = null,
            tokenUrl = tokenUrl,
            refreshUrl = refreshUrl,
            scopes = scopes,
            extensions = extensions.mapValues { (_, value) -> YamlUtils.objectMapper.valueToTree(value) },
        )

    private fun RequestBody?.takeIfPresent(): RequestBody? = this?.takeIf { Overlay.of(it).isPresent }

    private fun OAuthFlow?.takeIfContent(): OAuthFlow? =
        this?.takeIf {
            it.authorizationUrl != null ||
                it.tokenUrl != null ||
                it.refreshUrl != null ||
                it.scopes.isNotEmpty() ||
                it.extensions.isNotEmpty()
        }

    private fun Schema?.takeIfPresent(): Schema? = this?.takeIf { Overlay.of(it).isPresent }
}
