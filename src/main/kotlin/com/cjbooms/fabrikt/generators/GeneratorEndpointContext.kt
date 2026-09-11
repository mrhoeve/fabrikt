package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.generators.GeneratorUtils.toKCodeName
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.JSON_NODE_CLASS
import com.cjbooms.fabrikt.generators.model.ModelGenerator.Companion.toModelType
import com.cjbooms.fabrikt.model.BodyParameter
import com.cjbooms.fabrikt.model.Destinations.clientPackage
import com.cjbooms.fabrikt.model.FormObjectProperty
import com.cjbooms.fabrikt.model.FormParameter
import com.cjbooms.fabrikt.model.GeneratorDirectionalModelPlan
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolution
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolver
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.model.GeneratorModelDirection
import com.cjbooms.fabrikt.model.HeaderParam
import com.cjbooms.fabrikt.model.IncomingParameter
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.MultipartHeaderParameter
import com.cjbooms.fabrikt.model.MultipartParameter
import com.cjbooms.fabrikt.model.MultipartPartEncoding
import com.cjbooms.fabrikt.model.QueryStringParam
import com.cjbooms.fabrikt.model.RequestParameter
import com.cjbooms.fabrikt.model.RequestParameterLocation
import com.cjbooms.fabrikt.model.SequentialMultipartParameter
import com.cjbooms.fabrikt.parser.GeneratorEncoding
import com.cjbooms.fabrikt.parser.GeneratorHeader
import com.cjbooms.fabrikt.parser.GeneratorMediaType
import com.cjbooms.fabrikt.parser.GeneratorObjectSchema
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorOperationDocument
import com.cjbooms.fabrikt.parser.GeneratorOperationSecurity
import com.cjbooms.fabrikt.parser.GeneratorParameter
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.parser.GeneratorResponse
import com.cjbooms.fabrikt.parser.GeneratorSchema
import com.cjbooms.fabrikt.parser.GeneratorSchemaDocument
import com.cjbooms.fabrikt.parser.GeneratorSchemaValueConstraint
import com.cjbooms.fabrikt.parser.GeneratorSecurityAlternative
import com.cjbooms.fabrikt.parser.GeneratorSecuritySelection
import com.cjbooms.fabrikt.parser.SourceSchemaType
import com.cjbooms.fabrikt.parser.valueConstraint
import com.cjbooms.fabrikt.util.GroupingStrategy
import com.cjbooms.fabrikt.util.NormalisedString.camelCase
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.fasterxml.jackson.databind.JsonNode
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName

private const val CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding"

internal class GeneratorEndpointContext(
    val operations: GeneratorOperationDocument,
    private val schemas: GeneratorSchemaDocument,
    private val basePackage: String,
) {
    private val typeResolver = GeneratorKotlinTypeResolver(schemas, GeneratorModelDescriptorBuilder.registeredModelNames(schemas))
    private val directionalModels =
        GeneratorDirectionalModelPlan.create(GeneratorModelDescriptorBuilder.build(schemas))

    fun groupedPaths(strategy: GroupingStrategy): Map<String, List<GeneratorPathItem>> =
        operations.paths.groupBy { path ->
            when (strategy) {
                GroupingStrategy.BY_FIRST_PATH_SEGMENT -> path.path.toResourceName()
                GroupingStrategy.BY_FIRST_TAG ->
                    path.operations
                        .sortedBy(GeneratorOperation::method)
                        .firstNotNullOfOrNull { it.tags.firstOrNull() }
                        ?.toModelClassName()
                        ?: path.path.toResourceName()
            }
        }

    fun clientAuthenticationTypes(): Set<ClientAuthenticationType> =
        operations.securitySchemes.values.mapNotNullTo(linkedSetOf()) { scheme ->
            when {
                scheme.type.equals("http", ignoreCase = true) && scheme.scheme.equals("basic", ignoreCase = true) ->
                    ClientAuthenticationType.BASIC
                scheme.type.equals("http", ignoreCase = true) && scheme.scheme.equals("bearer", ignoreCase = true) ->
                    ClientAuthenticationType.BEARER
                scheme.type.equals("oauth2", ignoreCase = true) || scheme.type.equals("openIdConnect", ignoreCase = true) ->
                    ClientAuthenticationType.BEARER
                else -> null
            }
        }

    fun requireSupportedMethods(
        target: String,
        supportedMethods: Set<String>,
    ) {
        val unsupported =
            operations.paths
                .flatMap(GeneratorPathItem::operations)
                .map { it.method.uppercase() }
                .filterNot(supportedMethods::contains)
                .distinct()
        require(unsupported.isEmpty()) {
            "$target does not support native generation for HTTP method(s): ${unsupported.joinToString()}."
        }
    }

    fun requireSupportedMultipartMethods(
        target: String,
        supportedMethods: Set<String>,
    ) {
        val unsupported =
            operations.paths.flatMap { path ->
                path.operations
                    .filter(::hasMultipartRequestBody)
                    .filterNot { it.method.uppercase() in supportedMethods }
                    .map { operation -> "${operation.method.uppercase()} ${path.path}" }
            }
        require(unsupported.isEmpty()) {
            "$target does not support native multipart generation for: ${unsupported.joinToString()}."
        }
    }

    fun requireNoMultipartEncodingHeaders(target: String) {
        val operationsWithPartHeaders =
            operations.paths.flatMap { path ->
                path.operations
                    .filter { operation ->
                        operation.requestBody
                            ?.takeUnless { multipartBody(it) is GeneratorMultipartBody.Sequential }
                            ?.let(::bodyParameters)
                            .orEmpty()
                            .filterIsInstance<MultipartParameter>()
                            .any { it.headers.isNotEmpty() || it.fixedHeaders.isNotEmpty() }
                    }.map { operation -> "${operation.method.uppercase()} ${path.path}" }
            }
        require(operationsWithPartHeaders.isEmpty()) {
            "$target cannot represent native multipart part headers for: ${operationsWithPartHeaders.joinToString()}."
        }
    }

    fun requireNoParameterContent(target: String) {
        val unsupported = parameterContentOperations()
        require(unsupported.isEmpty()) {
            "$target cannot represent native content-based parameters for: ${unsupported.joinToString()}."
        }
    }

    fun requireSupportedParameterContent(target: String) {
        val unsupported =
            parameterContentOperations { parameter, mediaType ->
                mediaType.equals("text/plain", ignoreCase = true) ||
                    mediaType.isJsonMediaType() ||
                    (parameter.placement == "querystring" && mediaType.isFormMediaType())
            }
        require(unsupported.isEmpty()) {
            "$target supports native content-based parameters only for text/plain, JSON, and form-encoded OpenAPI 3.2 querystring parameters: ${unsupported.joinToString()}."
        }
    }

    fun requireJsonParameterContent(target: String) {
        val unsupported =
            parameterContentOperations { parameter, mediaType ->
                mediaType.isJsonMediaType() || (parameter.placement == "querystring" && mediaType.isFormMediaType())
            }
        require(unsupported.isEmpty()) {
            "$target supports native content-based parameters only for JSON and form-encoded OpenAPI 3.2 querystring parameters: ${unsupported.joinToString()}."
        }
    }

    private fun parameterContentOperations(supported: (GeneratorParameter, String) -> Boolean = { _, _ -> false }): List<String> =
        operations.paths.flatMap { path ->
            path.operations.flatMap { operation ->
                (path.parameters + operation.parameters)
                    .flatMap { parameter -> parameter.content.map { parameter to it.key } }
                    .filterNot { (parameter, mediaType) -> supported(parameter, mediaType) }
                    .map { (parameter, mediaType) ->
                        "${operation.method.uppercase()} ${path.path} (${parameter.name}: $mediaType)"
                    }
            }
        }

    fun methodName(
        operation: GeneratorOperation,
        path: String,
    ): String = operation.operationId?.camelCase() ?: if (path.isSingleResource()) "${operation.method}ById" else operation.method

    fun functionName(
        operation: GeneratorOperation,
        resource: String,
    ): String = operation.operationId?.camelCase() ?: "${operation.method} $resource".toKCodeName()

    fun toKdoc(
        operation: GeneratorOperation,
        parameters: List<IncomingParameter>,
    ): CodeBlock {
        val kdoc = CodeBlock.builder().add("%L\n%L\n", operation.summary.orEmpty(), operation.description.orEmpty())
        parameters.forEach { kdoc.add("@param %L %L\n", it.name.toKCodeName(), it.description.orEmpty()) }
        return kdoc.build()
    }

    fun incomingParameters(
        operation: GeneratorOperation,
        pathParameters: List<GeneratorParameter>,
        extraParameters: List<IncomingParameter> = emptyList(),
        sequentialMultipartType: TypeName? = null,
    ): List<IncomingParameter> {
        val bodies = operation.requestBody?.let { bodyParameters(it, sequentialMultipartType) }.orEmpty()
        val merged =
            pathParameters.filter { path ->
                operation.parameters.none { it.name == path.name && it.placement == path.placement }
            } + operation.parameters
        val parameters = merged.mapNotNull(::requestParameter).sortedBy(IncomingParameter::isNullable)
        return avoidNameClashes(bodies + parameters + extraParameters)
    }

    fun clientParameters(
        operation: GeneratorOperation,
        path: GeneratorPathItem,
    ): List<IncomingParameter> {
        val hasAcceptParameter =
            (path.parameters + operation.parameters).any {
                it.placement == "header" && it.name.equals("Accept", ignoreCase = true)
            }
        val primaryResponse = operation.responses.firstOrNull { it.status != "default" && it.content.isNotEmpty() }
        val acceptParameter =
            if (primaryResponse?.content?.size.orZero() > 1 && !hasAcceptParameter) {
                listOf(
                    RequestParameter(
                        oasName = "acceptHeader",
                        description = null,
                        type = String::class.asTypeName(),
                        isRequired = true,
                        originalName = "Accept",
                        parameterLocation = HeaderParam,
                        typeInfo = KotlinTypeInfo.Text,
                        defaultValue = primaryResponse?.content?.firstOrNull()?.key,
                    ),
                )
            } else {
                emptyList()
            }
        return incomingParameters(
            operation,
            path.parameters,
            securityParameters(operation, path.parameters) + acceptParameter,
            Iterable::class.asClassName().parameterizedBy(ClassName(clientPackage(basePackage), "MultipartPart")),
        )
    }

    private fun securityParameters(
        operation: GeneratorOperation,
        pathParameters: List<GeneratorParameter>,
    ): List<RequestParameter> {
        val alternatives = securityPlan(operation).alternatives
        val securedAlternatives = alternatives.filter { it.schemes.isNotEmpty() }
        if (securedAlternatives.isEmpty()) return emptyList()
        val commonSchemeNames =
            securedAlternatives
                .map { alternative -> alternative.schemes.map { it.name }.toSet() }
                .reduce(Set<String>::intersect)
        val required = alternatives.none { it.schemes.isEmpty() }
        val declaredParameters = pathParameters + operation.parameters
        return securedAlternatives
            .flatMap(GeneratorSecurityAlternative::schemes)
            .distinctBy(GeneratorSecuritySelection::name)
            .mapNotNull { selection ->
                val scheme = selection.scheme ?: return@mapNotNull null
                val schemeRequired = required && selection.name in commonSchemeNames
                val parameter =
                    when {
                        scheme.type.equals("apiKey", ignoreCase = true) -> apiKeyParameter(selection.name, scheme, schemeRequired)
                        scheme.type.equals("http", ignoreCase = true) && scheme.scheme.equals("basic", ignoreCase = true) ->
                            authorizationParameter(selection.name, "BasicCredentials", scheme.description, schemeRequired)
                        scheme.type.equals("http", ignoreCase = true) && scheme.scheme.equals("bearer", ignoreCase = true) ->
                            authorizationParameter(selection.name, "BearerToken", scheme.description, schemeRequired)
                        scheme.type.equals("oauth2", ignoreCase = true) ||
                            scheme.type.equals("openIdConnect", ignoreCase = true) ->
                            authorizationParameter(selection.name, "BearerToken", scheme.description, schemeRequired)
                        else -> null
                    } ?: return@mapNotNull null
                if (
                    declaredParameters.any {
                        it.name == parameter.originalName &&
                            it.placement?.let { placement ->
                                runCatching { RequestParameterLocation(placement) }.getOrNull()
                            } == parameter.parameterLocation
                    }
                ) {
                    return@mapNotNull null
                }
                parameter
            }
    }

    private fun apiKeyParameter(
        name: String,
        scheme: com.cjbooms.fabrikt.parser.GeneratorSecurityScheme,
        required: Boolean,
    ): RequestParameter? {
        val wireName = scheme.parameterName ?: return null
        val placement = scheme.placement?.let { runCatching { RequestParameterLocation(it) }.getOrNull() } ?: return null
        return credentialParameter(name, scheme.description, String::class.asTypeName(), required, wireName, placement)
    }

    private fun authorizationParameter(
        name: String,
        typeName: String,
        description: String?,
        required: Boolean,
    ): RequestParameter =
        credentialParameter(
            name,
            description,
            ClassName(clientPackage(basePackage), typeName),
            required,
            "Authorization",
            HeaderParam,
        )

    private fun credentialParameter(
        name: String,
        description: String?,
        type: TypeName,
        required: Boolean,
        wireName: String,
        placement: RequestParameterLocation,
    ): RequestParameter =
        RequestParameter(
            oasName = name,
            description = description,
            type = type,
            isRequired = required,
            originalName = wireName,
            parameterLocation = placement,
            typeInfo = KotlinTypeInfo.Text,
        )

    fun primaryResponseContentType(operation: GeneratorOperation): String? =
        operation.responses
            .firstOrNull { it.status != "default" && it.content.isNotEmpty() }
            ?.content
            ?.firstOrNull()
            ?.key

    fun requestContentType(operation: GeneratorOperation): String? =
        operation.requestBody
            ?.content
            ?.firstOrNull()
            ?.key

    fun securityPlan(operation: GeneratorOperation): GeneratorOperationSecurity {
        val requirements = operation.security ?: operations.security
        return GeneratorOperationSecurity(
            alternatives =
                requirements
                    ?.values
                    .orEmpty()
                    .map { requirement ->
                        GeneratorSecurityAlternative(
                            schemes =
                                requirement.schemes.map { (name, scopes) ->
                                    GeneratorSecuritySelection(name, scopes, operations.securitySchemes[name])
                                },
                        )
                    },
        )
    }

    fun hasMultipartRequestBody(operation: GeneratorOperation): Boolean =
        operation.requestBody?.content?.any { it.key.startsWith("multipart/") } == true

    fun multipartBody(operation: GeneratorOperation): GeneratorMultipartBody? {
        val requestBody = operation.requestBody ?: return null
        return multipartBody(requestBody)
    }

    private fun multipartBody(requestBody: com.cjbooms.fabrikt.parser.GeneratorRequestBody): GeneratorMultipartBody? {
        val mediaType = requestBody.content.firstOrNull { it.key.startsWith("multipart/") } ?: return null
        val schema = mediaType.schema?.let(schemas::resolve) as? GeneratorObjectSchema
        val sequential =
            mediaType.itemSchema != null ||
                mediaType.prefixEncoding.isNotEmpty() ||
                mediaType.itemEncoding != null ||
                schema?.let { SourceSchemaType.ARRAY in it.types || it.prefixItems.isNotEmpty() || it.items != null } == true
        if (!sequential) return GeneratorMultipartBody.Named(mediaType)

        require(mediaType.encoding.isEmpty()) {
            "Multipart media type '${mediaType.key}' cannot combine named and positional encodings."
        }
        require(mediaType.itemSchema != null || schema != null) {
            "Multipart media type '${mediaType.key}' requires itemSchema or an array schema for positional encoding."
        }
        if (mediaType.itemSchema == null) {
            require(schema?.let { SourceSchemaType.ARRAY in it.types || it.prefixItems.isNotEmpty() || it.items != null } == true) {
                "Multipart media type '${mediaType.key}' requires an array schema for positional encoding."
            }
        }

        val minimumPartCount = schema?.constraints?.minItems ?: 0
        val prefixSchemas = schema?.prefixItems.orEmpty()
        val prefixCount = maxOf(prefixSchemas.size, mediaType.prefixEncoding.size)
        val remainingSchema = mediaType.itemSchema ?: schema?.items
        return GeneratorMultipartBody.Sequential(
            mediaType = mediaType,
            prefixParts =
                (0 until prefixCount).map { index ->
                    GeneratorSequentialMultipartPart(
                        index = index,
                        schema = prefixSchemas.getOrNull(index) ?: schema?.items,
                        encoding = mediaType.prefixEncoding.getOrNull(index),
                        required = index < minimumPartCount,
                    )
                },
            remainingPart =
                remainingSchema?.let {
                    GeneratorSequentialMultipartPart(
                        index = null,
                        schema = it,
                        encoding = mediaType.itemEncoding,
                        required = minimumPartCount > prefixCount,
                    )
                },
            minimumPartCount = minimumPartCount,
            maximumPartCount = schema?.constraints?.maxItems,
            streaming = mediaType.itemSchema != null,
        )
    }

    fun requireNoSequentialMultipart(target: String) {
        val unsupported =
            operations.paths.flatMap { path ->
                path.operations
                    .filter { multipartBody(it) is GeneratorMultipartBody.Sequential }
                    .map { operation -> "${operation.method.uppercase()} ${path.path}" }
            }
        require(unsupported.isEmpty()) {
            "$target does not yet support native sequential multipart generation for: ${unsupported.joinToString()}."
        }
    }

    fun hasSequentialMultipartBodies(): Boolean =
        operations.paths.any { path -> path.operations.any { multipartBody(it) is GeneratorMultipartBody.Sequential } }

    fun requireScalarFormParameters(target: String) {
        val unsupported =
            operations.paths.flatMap { path ->
                path.operations.flatMap { operation ->
                    operation.requestBody
                        ?.takeUnless { multipartBody(it) is GeneratorMultipartBody.Sequential }
                        ?.let(::bodyParameters)
                        .orEmpty()
                        .filterIsInstance<FormParameter>()
                        .filter { it.typeInfo is KotlinTypeInfo.Array }
                        .map { parameter -> "${operation.method.uppercase()} ${path.path} (${parameter.fieldName})" }
                }
            }
        require(unsupported.isEmpty()) {
            "$target does not support native form arrays for: ${unsupported.joinToString()}."
        }
    }

    fun requireNoObjectFormParameters(target: String) {
        val unsupported =
            operations.paths.flatMap { path ->
                path.operations
                    .flatMap { operation ->
                        operation.requestBody
                            ?.takeUnless { multipartBody(it) is GeneratorMultipartBody.Sequential }
                            ?.let(::bodyParameters)
                            .orEmpty()
                            .filterIsInstance<FormParameter>()
                            .filter { it.objectProperties.isNotEmpty() }
                            .map { parameter -> "${operation.method.uppercase()} ${path.path} (${parameter.fieldName})" }
                    }
            }
        require(unsupported.isEmpty()) {
            "$target does not support native object-valued form fields for: ${unsupported.joinToString()}."
        }
    }

    fun successResponseType(
        operation: GeneratorOperation,
        basePackage: String,
    ): TypeName {
        val responses = operation.successResponses().flatMap(GeneratorResponse::content)
        val schemas = responses.mapNotNull { it.effectiveSchema() }
        if (schemas.map { this.schemas.resolve(it).identity }.distinct().size > 1) {
            return if (responses.all { "json" in it.key.lowercase() }) JSON_NODE_CLASS else Any::class.asTypeName()
        }
        val schema = operation.primarySuccessResponse()?.content?.firstNotNullOfOrNull { it.effectiveSchema() }
        return schema
            ?.let { resolveType(it, GeneratorModelDirection.RESPONSE) }
            ?.let { toModelType(basePackage, it.typeInfo, it.nullable) }
            ?: Unit::class.asTypeName()
    }

    fun isSseResponse(operation: GeneratorOperation): Boolean {
        val media = operation.primarySuccessResponse()?.content?.firstOrNull { it.key == "text/event-stream" } ?: return false
        val schema = media.effectiveSchema()?.let(schemas::resolve) as? GeneratorObjectSchema ?: return false
        return SourceSchemaType.ARRAY in schema.types && schema.metadata.format == "event-stream"
    }

    private fun bodyParameters(
        requestBody: com.cjbooms.fabrikt.parser.GeneratorRequestBody,
        sequentialMultipartType: TypeName? = null,
    ): List<IncomingParameter> {
        val multipartBody = multipartBody(requestBody)
        if (multipartBody is GeneratorMultipartBody.Sequential) {
            requireNotNull(sequentialMultipartType) {
                "Native sequential multipart parameters require a target-specific multipart type."
            }
            return listOf(
                SequentialMultipartParameter(
                    oasName = "parts",
                    description = requestBody.description,
                    type = sequentialMultipartType,
                    isRequired = requestBody.required,
                    mediaType = multipartBody.mediaType.key,
                    minimumPartCount = multipartBody.minimumPartCount,
                    maximumPartCount = multipartBody.maximumPartCount,
                    prefixEncodings = multipartBody.prefixParts.map(::multipartPartEncoding),
                    itemEncoding = multipartBody.remainingPart?.let(::multipartPartEncoding),
                    streaming = multipartBody.streaming,
                ),
            )
        }
        val multipart = requestBody.content.firstOrNull { it.key.startsWith("multipart/") }
        if (multipart != null) {
            val schema = multipart.effectiveSchema()?.let(schemas::resolve) as? GeneratorObjectSchema ?: return emptyList()
            return schema.properties.map { (name, property) ->
                val resolved = schemas.resolve(property) as? GeneratorObjectSchema
                val item = resolved?.items?.let(schemas::resolve) as? GeneratorObjectSchema
                val encoding = multipart.encoding[name]
                val partSchema = if (SourceSchemaType.ARRAY in resolved.typesOrEmpty()) item else resolved
                val fixedHeaders = multipartFixedHeaders(partSchema, encoding)
                val binary =
                    (SourceSchemaType.STRING in resolved.typesOrEmpty() && resolved?.metadata?.format == "binary") ||
                        (
                            SourceSchemaType.ARRAY in resolved.typesOrEmpty() &&
                                SourceSchemaType.STRING in item.typesOrEmpty() &&
                                item?.metadata?.format == "binary"
                        )
                MultipartParameter(
                    oasName = name,
                    description = resolved?.metadata?.description,
                    type = typeName(property, name in schema.requiredProperties, GeneratorModelDirection.REQUEST),
                    partName = name,
                    isBinaryFile = binary,
                    contentType =
                        encoding?.contentType ?: if (binary) {
                            "application/octet-stream"
                        } else if (resolved.isSimple()) {
                            "text/plain"
                        } else {
                            "application/json"
                        },
                    isRequired = name in schema.requiredProperties,
                    isArray = SourceSchemaType.ARRAY in resolved.typesOrEmpty(),
                    headers =
                        encoding
                            ?.headers
                            .orEmpty()
                            .values
                            .filterNot { header ->
                                header.name.equals("Content-Type", ignoreCase = true) ||
                                    fixedHeaders.keys.any { it.equals(header.name, ignoreCase = true) }
                            }.mapNotNull { header -> multipartHeader(name, header) },
                    fixedHeaders = fixedHeaders,
                )
            }
        }

        val form = requestBody.content.firstOrNull { it.key.startsWith("application/x-www-form-urlencoded") }
        if (form != null) {
            val schema = form.effectiveSchema()?.let(schemas::resolve) as? GeneratorObjectSchema ?: return emptyList()
            return schema.properties.map { (name, property) ->
                val resolution = resolveType(property, GeneratorModelDirection.REQUEST)
                val resolved = schemas.resolve(property) as? GeneratorObjectSchema
                val encoding = form.encoding[name]
                val objectProperties = resolved?.formObjectProperties().orEmpty()
                require(resolution.typeInfo.supportsFormSerialization() || objectProperties.isNotEmpty()) {
                    "Native form generation does not yet support object-valued field '$name'."
                }
                FormParameter(
                    oasName = name,
                    description = (schemas.resolve(property) as? GeneratorObjectSchema)?.metadata?.description,
                    type = toModelType(basePackage, resolution.typeInfo, resolution.nullable),
                    isRequired = name in schema.requiredProperties,
                    fieldName = name,
                    typeInfo = resolution.typeInfo,
                    style = encoding?.style ?: "form",
                    explode = encoding?.explode ?: true,
                    allowReserved = encoding?.allowReserved ?: false,
                    objectProperties = objectProperties,
                )
            }
        }

        val bodies =
            requestBody.content.mapNotNull { media ->
                val schema = media.effectiveSchema() ?: return@mapNotNull null
                val resolution = resolveType(schema, GeneratorModelDirection.REQUEST)
                BodyParameter(
                    oasName = resolution.typeInfo.generatedModelClassName?.toKotlinParameterName() ?: "body",
                    description = requestBody.description,
                    type = toModelType(basePackage, resolution.typeInfo, resolution.nullable),
                    isRequired = requestBody.required,
                )
            }
        return bodies
            .distinctBy { it.name }
            .reduceOrNull { first, second ->
                BodyParameter("body", first.description, first.type, first.isRequired && second.isRequired)
            }?.let(::listOf)
            .orEmpty()
    }

    private fun requestParameter(parameter: GeneratorParameter): RequestParameter? {
        val name = parameter.name ?: return null
        val placement = parameter.placement ?: return null
        val parameterLocation = runCatching { RequestParameterLocation(placement) }.getOrNull() ?: return null
        require(parameter.content.size <= 1) {
            "Parameter '$name' must define at most one content media type."
        }
        val content = parameter.content.singleOrNull()
        val schema = parameter.schema ?: content?.effectiveSchema() ?: return null
        val resolvedSchema = schemas.resolve(schema) as? GeneratorObjectSchema
        val resolution = resolveType(schema, GeneratorModelDirection.REQUEST)
        val objectProperties =
            when {
                content == null -> resolvedSchema?.formObjectProperties().orEmpty()
                parameterLocation is QueryStringParam && content.key.isFormMediaType() ->
                    resolvedSchema
                        ?.formObjectProperties()
                        .orEmpty()
                        .map { property ->
                            val encoding = content.encoding[property.fieldName]
                            property.copy(
                                style = encoding?.style ?: "form",
                                explode = encoding?.explode ?: true,
                                allowReserved = encoding?.allowReserved ?: false,
                            )
                        }
                else -> emptyList()
            }
        require(parameterLocation !is QueryStringParam || (content?.key.isFormMediaType() && objectProperties.isNotEmpty())) {
            "Native querystring parameters currently require object-valued application/x-www-form-urlencoded content."
        }
        return RequestParameter(
            oasName = name,
            description = parameter.description,
            type = toModelType(basePackage, resolution.typeInfo, resolution.nullable),
            isRequired = parameter.required,
            originalName = name,
            parameterLocation = parameterLocation,
            typeInfo = resolution.typeInfo,
            minimum = resolvedSchema?.constraints?.minimum?.value,
            maximum = resolvedSchema?.constraints?.maximum?.value,
            minLength = resolvedSchema?.constraints?.minLength,
            maxLength = resolvedSchema?.constraints?.maxLength,
            style = parameter.style,
            explode = parameter.explode,
            allowReserved = parameter.allowReserved ?: false,
            objectProperties = objectProperties,
            defaultValue = resolvedSchema?.metadata?.defaultValue?.toValue(),
            contentType = content?.key,
        )
    }

    private fun multipartHeader(
        partName: String,
        header: GeneratorHeader,
    ): MultipartHeaderParameter? {
        val schema = header.schema ?: header.content.firstNotNullOfOrNull { it.effectiveSchema() } ?: return null
        val resolvedSchema = schemas.resolve(schema) as? GeneratorObjectSchema
        val resolution = resolveType(schema, GeneratorModelDirection.REQUEST)
        return MultipartHeaderParameter(
            name = "${partName}_${header.name}".toKotlinParameterName(),
            originalName = header.name,
            description = header.description,
            type = toModelType(basePackage, resolution.typeInfo, resolution.nullable),
            isRequired = header.required,
            typeInfo = resolution.typeInfo,
            explode = header.explode ?: false,
            objectProperties = resolvedSchema?.formObjectProperties().orEmpty(),
        )
    }

    private fun multipartFixedHeaders(
        schema: GeneratorObjectSchema?,
        encoding: GeneratorEncoding?,
    ): Map<String, String> {
        val header =
            encoding
                ?.headers
                ?.values
                ?.firstOrNull { it.name.equals(CONTENT_TRANSFER_ENCODING, ignoreCase = true) }
        val allowedValues = header?.allowedValues()
        val contentEncoding = schema?.metadata?.contentEncoding

        if (contentEncoding != null && allowedValues != null) {
            require(allowedValues.any { it.isTextual && it.textValue().equals(contentEncoding, ignoreCase = true) }) {
                "Multipart $CONTENT_TRANSFER_ENCODING header does not allow schema contentEncoding '$contentEncoding'."
            }
        }

        val fixedValue =
            contentEncoding
                ?: allowedValues
                    ?.takeIf { header.required }
                    ?.takeIf { values -> values.all(JsonNode::isTextual) }
                    ?.map(JsonNode::textValue)
                    ?.distinctBy { it.lowercase() }
                    ?.singleOrNull()
                ?: return emptyMap()
        return mapOf(CONTENT_TRANSFER_ENCODING to fixedValue)
    }

    private fun GeneratorHeader.allowedValues(): List<JsonNode>? {
        val headerSchema = schema ?: content.firstNotNullOfOrNull { it.effectiveSchema() } ?: return null
        val resolved = schemas.resolve(headerSchema) as? GeneratorObjectSchema ?: return null
        return when (val constraint = resolved.valueConstraint()) {
            is GeneratorSchemaValueConstraint.Allowed -> constraint.values
            GeneratorSchemaValueConstraint.Impossible -> emptyList()
            GeneratorSchemaValueConstraint.Unconstrained -> null
        }
    }

    private fun multipartPartEncoding(part: GeneratorSequentialMultipartPart): MultipartPartEncoding =
        multipartPartEncoding(part.schema, part.encoding)

    private fun multipartPartEncoding(
        schema: GeneratorSchema?,
        encoding: com.cjbooms.fabrikt.parser.GeneratorEncoding?,
    ): MultipartPartEncoding {
        val resolvedSchema = schema?.let(schemas::resolve) as? GeneratorObjectSchema
        val prefixSchemas = resolvedSchema?.prefixItems.orEmpty()
        val prefixCount = maxOf(prefixSchemas.size, encoding?.prefixEncoding?.size ?: 0)
        val fixedHeaders = multipartFixedHeaders(resolvedSchema, encoding)
        return MultipartPartEncoding(
            contentTypes = encoding?.contentType.toContentTypes(resolvedSchema),
            requiredHeaders =
                encoding
                    ?.headers
                    .orEmpty()
                    .values
                    .filter { header ->
                        header.required &&
                            !header.name.equals("Content-Type", ignoreCase = true) &&
                            fixedHeaders.keys.none { it.equals(header.name, ignoreCase = true) }
                    }.mapTo(linkedSetOf(), GeneratorHeader::name),
            fixedHeaders = fixedHeaders,
            prefixEncodings =
                (0 until prefixCount).map { index ->
                    multipartPartEncoding(
                        prefixSchemas.getOrNull(index) ?: resolvedSchema?.items,
                        encoding?.prefixEncoding?.getOrNull(index),
                    )
                },
            itemEncoding =
                (resolvedSchema?.items ?: schema?.takeIf { encoding?.itemEncoding != null })
                    ?.let { multipartPartEncoding(it, encoding?.itemEncoding) },
            minimumPartCount = resolvedSchema?.constraints?.minItems ?: 0,
            maximumPartCount = resolvedSchema?.constraints?.maxItems,
        )
    }

    private fun String?.toContentTypes(schema: GeneratorObjectSchema?): List<String> =
        this
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf(List<String>::isNotEmpty)
            ?: listOf(schema.defaultMultipartContentType())

    private fun GeneratorObjectSchema?.defaultMultipartContentType(): String =
        when {
            this == null || types.isEmpty() -> "application/octet-stream"
            SourceSchemaType.STRING in types && metadata.contentEncoding != null -> "application/octet-stream"
            SourceSchemaType.STRING in types ||
                SourceSchemaType.INTEGER in types ||
                SourceSchemaType.NUMBER in types ||
                SourceSchemaType.BOOLEAN in types -> "text/plain"
            else -> "application/json"
        }

    private fun typeName(
        schema: GeneratorSchema,
        required: Boolean,
        direction: GeneratorModelDirection,
    ): TypeName {
        val resolution = resolveType(schema, direction)
        return toModelType(basePackage, resolution.typeInfo, !required || resolution.nullable)
    }

    private fun resolveType(
        schema: GeneratorSchema,
        direction: GeneratorModelDirection = GeneratorModelDirection.COMBINED,
    ): GeneratorKotlinTypeResolution.Resolved {
        val resolved =
            when (val resolution = typeResolver.resolve(schema)) {
                is GeneratorKotlinTypeResolution.Resolved -> resolution
                is GeneratorKotlinTypeResolution.Fallback ->
                    GeneratorKotlinTypeResolution.Resolved(resolution.typeInfo, resolution.nullable)
                is GeneratorKotlinTypeResolution.Uninhabitable -> GeneratorKotlinTypeResolution.Resolved(KotlinTypeInfo.AnyType, true)
                is GeneratorKotlinTypeResolution.Unsupported -> GeneratorKotlinTypeResolution.Resolved(KotlinTypeInfo.AnyType, true)
            }
        return directionalModels.resolve(resolved, direction)
    }

    private fun avoidNameClashes(parameters: List<IncomingParameter>): List<IncomingParameter> {
        if (parameters.map(IncomingParameter::name).distinct().size == parameters.size) return parameters
        return parameters.map { parameter ->
            when (parameter) {
                is FormParameter ->
                    FormParameter(
                        oasName = "form_${parameter.oasName}".toKotlinParameterName(),
                        description = parameter.description,
                        type = parameter.type,
                        isRequired = parameter.isRequired,
                        fieldName = parameter.fieldName,
                        typeInfo = parameter.typeInfo,
                        style = parameter.style,
                        explode = parameter.explode,
                        allowReserved = parameter.allowReserved,
                        objectProperties = parameter.objectProperties,
                    )
                is MultipartParameter ->
                    MultipartParameter(
                        oasName = "multipart_${parameter.oasName}".toKotlinParameterName(),
                        description = parameter.description,
                        type = parameter.type,
                        isRequired = parameter.isRequired,
                        partName = parameter.partName,
                        isBinaryFile = parameter.isBinaryFile,
                        contentType = parameter.contentType,
                        isArray = parameter.isArray,
                        headers = parameter.headers,
                        fixedHeaders = parameter.fixedHeaders,
                    )
                is SequentialMultipartParameter ->
                    SequentialMultipartParameter(
                        oasName = "multipart_${parameter.oasName}".toKotlinParameterName(),
                        description = parameter.description,
                        type = parameter.type,
                        isRequired = parameter.isRequired,
                        mediaType = parameter.mediaType,
                        minimumPartCount = parameter.minimumPartCount,
                        maximumPartCount = parameter.maximumPartCount,
                        prefixEncodings = parameter.prefixEncodings,
                        itemEncoding = parameter.itemEncoding,
                        streaming = parameter.streaming,
                    )
                is BodyParameter ->
                    BodyParameter(
                        "body_${parameter.oasName}".toKotlinParameterName(),
                        parameter.description,
                        parameter.type,
                        parameter.isRequired,
                    )
                is RequestParameter ->
                    RequestParameter(
                        oasName = "${parameter.parameterLocation}_${parameter.oasName}".toKotlinParameterName(),
                        description = parameter.description,
                        type = parameter.type,
                        isRequired = parameter.isRequired,
                        originalName = parameter.originalName,
                        parameterLocation = parameter.parameterLocation,
                        typeInfo = parameter.typeInfo,
                        minimum = parameter.minimum,
                        maximum = parameter.maximum,
                        minLength = parameter.minLength,
                        maxLength = parameter.maxLength,
                        style = parameter.style,
                        explode = parameter.explode,
                        allowReserved = parameter.allowReserved,
                        objectProperties = parameter.objectProperties,
                        defaultValue = parameter.defaultValue,
                        contentType = parameter.contentType,
                    )
            }
        }
    }

    private fun GeneratorOperation.successResponses(): List<GeneratorResponse> =
        responses.filter {
            it.status
                .replace('X', '0')
                .toIntOrNull()
                ?.let { status -> status in 200..299 } == true &&
                it.content.isNotEmpty()
        }

    private fun GeneratorOperation.primarySuccessResponse(): GeneratorResponse? =
        successResponses()
            .mapNotNull { response ->
                response.status
                    .replace('X', '0')
                    .toIntOrNull()
                    ?.let { it to response }
            }.minByOrNull(Pair<Int, GeneratorResponse>::first)
            ?.second

    private fun GeneratorMediaType.effectiveSchema(): GeneratorSchema? = schema ?: itemSchema

    private fun GeneratorObjectSchema?.typesOrEmpty(): Set<SourceSchemaType> = this?.types.orEmpty()

    private fun GeneratorObjectSchema?.isSimple(): Boolean =
        this != null &&
            types.any { it in setOf(SourceSchemaType.STRING, SourceSchemaType.INTEGER, SourceSchemaType.NUMBER, SourceSchemaType.BOOLEAN) }

    private fun String.isJsonMediaType(): Boolean =
        equals("application/json", ignoreCase = true) || substringBefore(';').endsWith("+json", ignoreCase = true)

    private fun String?.isFormMediaType(): Boolean =
        this?.substringBefore(';')?.equals("application/x-www-form-urlencoded", ignoreCase = true) == true

    private fun KotlinTypeInfo.supportsFormSerialization(): Boolean =
        when (this) {
            is KotlinTypeInfo.Array -> !parameterizedType.isComplexType
            else -> !isComplexType
        }

    private fun GeneratorObjectSchema.formObjectProperties(): List<FormObjectProperty>? {
        if (SourceSchemaType.OBJECT !in types || properties.isEmpty()) return null
        return properties.map { (name, property) ->
            val resolution = resolveType(property, GeneratorModelDirection.REQUEST)
            if (!resolution.typeInfo.supportsFormSerialization()) return null
            FormObjectProperty(
                fieldName = name,
                propertyName = name.toKotlinParameterName(),
                typeInfo = resolution.typeInfo,
                type = toModelType(basePackage, resolution.typeInfo, false),
                nullable = name !in requiredProperties || resolution.nullable,
            )
        }
    }

    private fun String.toResourceName(): String =
        split('/')
            .filterNot { it.isBlank() || it.matches("\\{.*}".toRegex()) }
            .joinToString("-")
            .toModelClassName()

    private fun String.isSingleResource(): Boolean = count { it == '/' } % 2 == 0 && endsWith("}")

    private fun JsonNode.toValue(): Any? =
        when {
            isNull -> null
            isBoolean -> booleanValue()
            isIntegralNumber -> longValue()
            isFloatingPointNumber -> decimalValue()
            isTextual -> textValue()
            else -> this
        }

    private fun Int?.orZero(): Int = this ?: 0
}

internal enum class ClientAuthenticationType {
    BASIC,
    BEARER,
}
