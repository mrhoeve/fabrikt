package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.generators.GeneratorUtils.toKCodeName
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.JSON_NODE_CLASS
import com.cjbooms.fabrikt.generators.model.ModelGenerator.Companion.toModelType
import com.cjbooms.fabrikt.model.BodyParameter
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolution
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolver
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.model.HeaderParam
import com.cjbooms.fabrikt.model.IncomingParameter
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.MultipartParameter
import com.cjbooms.fabrikt.model.RequestParameter
import com.cjbooms.fabrikt.model.RequestParameterLocation
import com.cjbooms.fabrikt.parser.GeneratorMediaType
import com.cjbooms.fabrikt.parser.GeneratorObjectSchema
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorOperationDocument
import com.cjbooms.fabrikt.parser.GeneratorParameter
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.parser.GeneratorResponse
import com.cjbooms.fabrikt.parser.GeneratorSchema
import com.cjbooms.fabrikt.parser.GeneratorSchemaDocument
import com.cjbooms.fabrikt.parser.SourceSchemaType
import com.cjbooms.fabrikt.util.GroupingStrategy
import com.cjbooms.fabrikt.util.NormalisedString.camelCase
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.fasterxml.jackson.databind.JsonNode
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName

internal class GeneratorEndpointContext(
    val operations: GeneratorOperationDocument,
    private val schemas: GeneratorSchemaDocument,
    private val basePackage: String,
) {
    private val typeResolver = GeneratorKotlinTypeResolver(schemas, GeneratorModelDescriptorBuilder.registeredModelNames(schemas))

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

    fun requireMultipartSupported(target: String) {
        val unsupported =
            operations.paths.flatMap { path ->
                path.operations
                    .filter(::hasMultipartRequestBody)
                    .map { operation -> "${operation.method.uppercase()} ${path.path}" }
            }
        require(unsupported.isEmpty()) {
            "$target does not support native multipart generation for: ${unsupported.joinToString()}."
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
        val kdoc = CodeBlock.builder().add("${operation.summary.orEmpty()}\n${operation.description.orEmpty()}\n")
        parameters.forEach { kdoc.add("@param %L %L\n", it.name.toKCodeName(), it.description.orEmpty()) }
        return kdoc.build()
    }

    fun incomingParameters(
        operation: GeneratorOperation,
        pathParameters: List<GeneratorParameter>,
        extraParameters: List<IncomingParameter> = emptyList(),
    ): List<IncomingParameter> {
        val bodies = operation.requestBody?.let(::bodyParameters).orEmpty()
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
        val extra =
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
        return incomingParameters(operation, path.parameters, extra)
    }

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

    fun hasMultipartRequestBody(operation: GeneratorOperation): Boolean =
        operation.requestBody?.content?.any { it.key.startsWith("multipart/form-data") } == true

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
        return schema?.let { toModelType(basePackage, resolveType(it).typeInfo, resolveType(it).nullable) } ?: Unit::class.asTypeName()
    }

    fun isSseResponse(operation: GeneratorOperation): Boolean {
        val media = operation.primarySuccessResponse()?.content?.firstOrNull { it.key == "text/event-stream" } ?: return false
        val schema = media.effectiveSchema()?.let(schemas::resolve) as? GeneratorObjectSchema ?: return false
        return SourceSchemaType.ARRAY in schema.types && schema.metadata.format == "event-stream"
    }

    private fun bodyParameters(requestBody: com.cjbooms.fabrikt.parser.GeneratorRequestBody): List<IncomingParameter> {
        val multipart = requestBody.content.firstOrNull { it.key.startsWith("multipart/form-data") }
        if (multipart != null) {
            val schema = multipart.effectiveSchema()?.let(schemas::resolve) as? GeneratorObjectSchema ?: return emptyList()
            return schema.properties.map { (name, property) ->
                val resolved = schemas.resolve(property) as? GeneratorObjectSchema
                val item = resolved?.items?.let(schemas::resolve) as? GeneratorObjectSchema
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
                    type = typeName(property, name in schema.requiredProperties),
                    partName = name,
                    isBinaryFile = binary,
                    contentType =
                        if (binary) {
                            "application/octet-stream"
                        } else if (resolved.isSimple()) {
                            "text/plain"
                        } else {
                            "application/json"
                        },
                    isRequired = name in schema.requiredProperties,
                    isArray = SourceSchemaType.ARRAY in resolved.typesOrEmpty(),
                )
            }
        }

        val bodies =
            requestBody.content.mapNotNull { media ->
                val schema = media.effectiveSchema() ?: return@mapNotNull null
                val resolution = resolveType(schema)
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
        val schema = parameter.schema ?: parameter.content.firstNotNullOfOrNull { it.effectiveSchema() } ?: return null
        val resolvedSchema = schemas.resolve(schema) as? GeneratorObjectSchema
        val resolution = resolveType(schema)
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
            explode = parameter.explode,
            defaultValue = resolvedSchema?.metadata?.defaultValue?.toValue(),
        )
    }

    private fun typeName(
        schema: GeneratorSchema,
        required: Boolean,
    ): TypeName {
        val resolution = resolveType(schema)
        return toModelType(basePackage, resolution.typeInfo, !required || resolution.nullable)
    }

    private fun resolveType(schema: GeneratorSchema): GeneratorKotlinTypeResolution.Resolved =
        when (val resolution = typeResolver.resolve(schema)) {
            is GeneratorKotlinTypeResolution.Resolved -> resolution
            is GeneratorKotlinTypeResolution.Fallback -> GeneratorKotlinTypeResolution.Resolved(resolution.typeInfo, resolution.nullable)
            is GeneratorKotlinTypeResolution.Unsupported -> GeneratorKotlinTypeResolution.Resolved(KotlinTypeInfo.AnyType, true)
        }

    private fun avoidNameClashes(parameters: List<IncomingParameter>): List<IncomingParameter> {
        if (parameters.map(IncomingParameter::name).distinct().size == parameters.size) return parameters
        return parameters.map { parameter ->
            when (parameter) {
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
                        explode = parameter.explode,
                        defaultValue = parameter.defaultValue,
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
