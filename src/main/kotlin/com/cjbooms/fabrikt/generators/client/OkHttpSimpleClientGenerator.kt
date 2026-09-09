package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.cli.ClientCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.generators.GeneratorUtils.functionName
import com.cjbooms.fabrikt.generators.GeneratorUtils.getPrimaryContentMediaType
import com.cjbooms.fabrikt.generators.GeneratorUtils.hasMultipartRequestBody
import com.cjbooms.fabrikt.generators.GeneratorUtils.primaryPropertiesConstructor
import com.cjbooms.fabrikt.generators.GeneratorUtils.toClassName
import com.cjbooms.fabrikt.generators.GeneratorUtils.toKdoc
import com.cjbooms.fabrikt.generators.TypeFactory
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.ADDITIONAL_HEADERS_PARAMETER_NAME
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.ADDITIONAL_QUERY_PARAMETERS_PARAMETER_NAME
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.addIncomingParameters
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.deriveClientParameters
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.getReturnType
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.groupedClientPaths
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.simpleClientName
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.toClientReturnType
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.OBJECT_MAPPER_CLASS
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.TYPE_REFERENCE_IMPORT
import com.cjbooms.fabrikt.generators.toWireValue
import com.cjbooms.fabrikt.model.BodyParameter
import com.cjbooms.fabrikt.model.ClientType
import com.cjbooms.fabrikt.model.CookieParam
import com.cjbooms.fabrikt.model.Destinations
import com.cjbooms.fabrikt.model.FormObjectProperty
import com.cjbooms.fabrikt.model.FormParameter
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.HeaderParam
import com.cjbooms.fabrikt.model.IncomingParameter
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.MultipartParameter
import com.cjbooms.fabrikt.model.PathParam
import com.cjbooms.fabrikt.model.QueryParam
import com.cjbooms.fabrikt.model.RequestParameter
import com.cjbooms.fabrikt.model.SimpleFile
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.util.GroupingStrategy
import com.github.javaparser.utils.CodeGenerationUtils
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import java.nio.file.Path
import java.util.Locale.getDefault

class OkHttpSimpleClientGenerator(
    private val packages: Packages,
    private val api: SourceApi,
    private val srcPath: Path = Destinations.MAIN_KT_SOURCE,
) {
    private val multipartParameterToSpecBuilder = ClientGeneratorUtils.MultipartParameterToSpecBuilder(packages.client)
    private var generatorContext: GeneratorEndpointContext? = null

    internal constructor(
        packages: Packages,
        api: SourceApi,
        srcPath: Path,
        generatorContext: GeneratorEndpointContext,
    ) : this(packages, api, srcPath) {
        this.generatorContext = generatorContext
    }

    fun generateDynamicClientCode(options: Set<ClientCodeGenOptionType> = emptySet()): Collection<ClientType> =
        generatorContext?.let { generateDynamicClientCode(it, options) } ?: api
            .groupedClientPaths(options)
            .map { (resourceName, paths) ->
                val funcSpecs: List<FunSpec> =
                    paths.flatMap { (resource, path) ->
                        path.operations.map { (verb, operation) ->
                            val parameters = deriveClientParameters(path, operation, packages.base)
                            FunSpec
                                .builder(functionName(operation, resource, verb))
                                .addModifiers(KModifier.PUBLIC)
                                .addKdoc(operation.toKdoc(parameters))
                                .addAnnotation(
                                    AnnotationSpec
                                        .builder(Throws::class)
                                        .addMember("%T::class", "ApiException".toClassName(packages.client))
                                        .build(),
                                ).addIncomingParameters(
                                    parameters,
                                    multipartParameterToSpecBuilder = multipartParameterToSpecBuilder.toSpecBuilder(),
                                ).addParameter(
                                    ParameterSpec
                                        .builder(
                                            ADDITIONAL_HEADERS_PARAMETER_NAME,
                                            TypeFactory.createMapOfStringToNonNullType(String::class.asTypeName()),
                                        ).defaultValue("emptyMap()")
                                        .build(),
                                ).addParameter(
                                    ParameterSpec
                                        .builder(
                                            ADDITIONAL_QUERY_PARAMETERS_PARAMETER_NAME,
                                            TypeFactory.createMapOfStringToNonNullType(String::class.asTypeName()),
                                        ).defaultValue("emptyMap()")
                                        .build(),
                                ).addCode(
                                    SimpleClientOperationStatement(
                                        packages,
                                        resource,
                                        verb,
                                        operation.hasMultipartRequestBody(),
                                        operation.requestBody.getPrimaryContentMediaType()?.key,
                                        operation.getReturnType(packages),
                                        parameters,
                                        options,
                                    ).toStatement(),
                                ).returns(operation.toClientReturnType(packages))
                                .build()
                        }
                    }

                val clientType =
                    TypeSpec
                        .classBuilder(simpleClientName(resourceName))
                        .primaryPropertiesConstructor(
                            PropertySpec.builder("objectMapper", OBJECT_MAPPER_CLASS, KModifier.PRIVATE).build(),
                            PropertySpec.builder("baseUrl", String::class.asTypeName(), KModifier.PRIVATE).build(),
                            PropertySpec
                                .builder("okHttpClient", "OkHttpClient".toClassName("okhttp3"), KModifier.PRIVATE)
                                .build(),
                        ).addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
                        .addFunctions(funcSpecs)
                        .build()

                ClientType(clientType, packages.base, setOf(TYPE_REFERENCE_IMPORT))
            }.toSet()

    private fun generateDynamicClientCode(
        context: GeneratorEndpointContext,
        options: Set<ClientCodeGenOptionType>,
    ): Collection<ClientType> {
        val strategy =
            if (ClientCodeGenOptionType.GROUP_BY_TAG in options) GroupingStrategy.BY_FIRST_TAG else GroupingStrategy.BY_FIRST_PATH_SEGMENT
        return context
            .groupedPaths(strategy)
            .map { (resourceName, paths) ->
                val functions = paths.flatMap { path -> path.operations.map { buildFunction(context, path, it, options) } }
                val type =
                    TypeSpec
                        .classBuilder(simpleClientName(resourceName))
                        .primaryPropertiesConstructor(
                            PropertySpec.builder("objectMapper", OBJECT_MAPPER_CLASS, KModifier.PRIVATE).build(),
                            PropertySpec.builder("baseUrl", String::class.asTypeName(), KModifier.PRIVATE).build(),
                            PropertySpec.builder("okHttpClient", "OkHttpClient".toClassName("okhttp3"), KModifier.PRIVATE).build(),
                        ).addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
                        .addFunctions(functions)
                        .build()
                ClientType(type, packages.base, setOf(TYPE_REFERENCE_IMPORT))
            }.toSet()
    }

    private fun buildFunction(
        context: GeneratorEndpointContext,
        path: GeneratorPathItem,
        operation: GeneratorOperation,
        options: Set<ClientCodeGenOptionType>,
    ): FunSpec {
        val parameters = context.clientParameters(operation, path)
        val returnType = context.successResponseType(operation, packages.base)
        return FunSpec
            .builder(context.functionName(operation, path.path))
            .addModifiers(KModifier.PUBLIC)
            .addKdoc(context.toKdoc(operation, parameters))
            .addAnnotation(
                AnnotationSpec.builder(Throws::class).addMember("%T::class", "ApiException".toClassName(packages.client)).build(),
            ).addIncomingParameters(parameters, multipartParameterToSpecBuilder = multipartParameterToSpecBuilder.toSpecBuilder())
            .addNativeMultipartHeaderParameters(parameters)
            .addParameter(
                ParameterSpec
                    .builder(
                        ADDITIONAL_HEADERS_PARAMETER_NAME,
                        TypeFactory.createMapOfStringToNonNullType(String::class.asTypeName()),
                    ).defaultValue("emptyMap()")
                    .build(),
            ).addParameter(
                ParameterSpec
                    .builder(
                        ADDITIONAL_QUERY_PARAMETERS_PARAMETER_NAME,
                        TypeFactory.createMapOfStringToNonNullType(String::class.asTypeName()),
                    ).defaultValue("emptyMap()")
                    .build(),
            ).addCode(
                SimpleClientOperationStatement(
                    packages,
                    path.path,
                    operation.method,
                    context.hasMultipartRequestBody(operation),
                    context.requestContentType(operation),
                    returnType,
                    parameters,
                    options,
                    nativeGeneration = true,
                ).toStatement(),
            ).returns("ApiResponse".toClassName(packages.client).parameterizedBy(returnType))
            .build()
    }

    private fun FunSpec.Builder.addNativeMultipartHeaderParameters(parameters: List<IncomingParameter>): FunSpec.Builder {
        parameters.filterIsInstance<MultipartParameter>().forEach { part ->
            part.headers.forEach { header -> addParameter(header.toParameterSpecBuilder(part).build()) }
        }
        return this
    }

    fun generateLibrary(options: Set<ClientCodeGenOptionType>): Collection<GeneratedFile> {
        val codeDir = srcPath.resolve(CodeGenerationUtils.packageToPath(packages.base))
        val clientDir = codeDir.resolve("client")
        val nonNullDataPayloads = ClientCodeGenOptionType.OKHTTP_NON_NULL_RESPONSE_PAYLOADS in options
        return setOf(
            SimpleFile(
                clientDir.resolve("ApiModels.kt"),
                OkHttpClientLibraryFiles.apiModels(packages, nonNullDataPayloads).toString(),
            ),
            SimpleFile(
                clientDir.resolve("HttpUtil.kt"),
                OkHttpClientLibraryFiles.httpUtil(packages, nonNullDataPayloads).toString(),
            ),
            SimpleFile(clientDir.resolve("OAuth.kt"), OkHttpClientLibraryFiles.oAuth(packages).toString()),
        )
    }
}

data class SimpleClientOperationStatement(
    private val packages: Packages,
    private val resource: String,
    private val verb: String,
    private val multipartRequestBody: Boolean,
    private val requestContentType: String?,
    private val returnType: com.squareup.kotlinpoet.TypeName,
    private val parameters: List<IncomingParameter>,
    private val options: Set<ClientCodeGenOptionType>,
    private val nativeGeneration: Boolean = false,
) {
    fun toStatement(): CodeBlock =
        CodeBlock
            .builder()
            .addUrlStatement()
            .addPathParamStatement()
            .addQueryParamStatement()
            .addHeaderParamStatement()
            .addRequestStatement()
            .addRequestExecutionStatement()
            .build()

    private fun CodeBlock.Builder.addUrlStatement(): CodeBlock.Builder {
        this.add("val httpUrl: %T = \"%L\"", "HttpUrl".toClassName("okhttp3"), "\$baseUrl$resource")
        return this
    }

    private fun CodeBlock.Builder.addPathParamStatement(): CodeBlock.Builder {
        parameters
            .filterIsInstance<RequestParameter>()
            .filter { it.parameterLocation == PathParam }
            .forEach {
                this.add("\n.pathParam(%S to %N)", "{${it.originalName}}", it.name)
            }

        this.add("\n.%T()\n.newBuilder()", "toHttpUrl".toClassName("okhttp3.HttpUrl.Companion"))
        return this
    }

    private fun CodeBlock.Builder.addQueryParamStatement(): CodeBlock.Builder {
        if (!nativeGeneration) return addLegacyQueryParamStatement()
        parameters
            .filterIsInstance<RequestParameter>()
            .filter { it.parameterLocation == QueryParam }
            .forEach { addQueryParameter(it) }
        this.add("\n.also { builder -> additionalQueryParameters.forEach { builder.queryParam(it.key, it.value) } }")
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addLegacyQueryParamStatement(): CodeBlock.Builder {
        parameters
            .filterIsInstance<RequestParameter>()
            .filter { it.parameterLocation == QueryParam }
            .forEach {
                when (it.typeInfo) {
                    is KotlinTypeInfo.Array ->
                        add(
                            "\n.%T(%S, %N, %L)",
                            "queryParam".toClassName(packages.client),
                            it.originalName,
                            it.name,
                            if (it.explode == null || it.explode) "true" else "false",
                        )

                    else -> add("\n.%T(%S, %N)", "queryParam".toClassName(packages.client), it.originalName, it.name)
                }
            }
        add("\n.also { builder -> additionalQueryParameters.forEach { builder.queryParam(it.key, it.value) } }")
        return add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addQueryParameter(parameter: RequestParameter) {
        if (parameter.objectProperties.isNotEmpty()) {
            addQueryObjectParameter(parameter)
            return
        }
        val typeInfo = parameter.typeInfo
        val method = parameter.queryParameterMethod()
        val parameterName = parameter.queryParameterName()
        if (typeInfo is KotlinTypeInfo.Array) {
            val style = parameter.style ?: "form"
            val explode = parameter.explode ?: (style == "form")
            val delimiter =
                if (style == "spaceDelimited") {
                    " "
                } else if (style == "pipeDelimited") {
                    "|"
                } else {
                    ","
                }
            val optional = !parameter.isRequired
            add("\n.also { builder ->")
            if (optional) add("\n%N?.let { values ->", parameter.name)
            val valueName = if (optional) "values" else parameter.name
            val itemValue = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) "it.value" else "it.toString()"
            if (explode) {
                add("\n%N.forEach { builder.%L(%S, %L) }", valueName, method, parameterName, itemValue)
            } else {
                val transform = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) " { it.value }" else ""
                add("\nbuilder.%L(%S, %N.joinToString(%S)$transform)", method, parameterName, valueName, delimiter)
            }
            if (optional) add("\n}")
            add("\n}")
            return
        }
        if (typeInfo is KotlinTypeInfo.Enum) {
            val optional = !parameter.isRequired
            add("\n.also { builder ->")
            if (optional) {
                add("\n%N?.let { builder.%L(%S, it.value) }", parameter.name, method, parameterName)
            } else {
                add("\nbuilder.%L(%S, %N.value)", method, parameterName, parameter.name)
            }
            add("\n}")
        } else if (parameter.allowReserved) {
            add("\n.also { builder ->")
            if (parameter.isRequired) {
                add("\nbuilder.%L(%S, %N.toString())", method, parameterName, parameter.name)
            } else {
                add("\n%N?.let { builder.%L(%S, it.toString()) }", parameter.name, method, parameterName)
            }
            add("\n}")
        } else {
            add("\n.%T(%S, %N)", "queryParam".toClassName(packages.client), parameter.originalName, parameter.name)
        }
    }

    private fun CodeBlock.Builder.addQueryObjectParameter(parameter: RequestParameter) {
        add("\n.also { builder ->")
        val optional = !parameter.isRequired
        if (optional) add("\n%N?.let { value ->", parameter.name)
        val valueName = if (optional) "value" else parameter.name
        val explode = parameter.explode ?: (parameter.style == null || parameter.style == "form")
        val method = parameter.queryParameterMethod()
        if (explode) {
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                val fieldName =
                    if (parameter.style == "deepObject") {
                        "${parameter.originalName}[${property.fieldName}]"
                    } else {
                        property.fieldName
                    }
                if (property.nullable) {
                    add(
                        "\n%L?.let { builder.%L(%S, %L) }",
                        expression,
                        method,
                        parameter.queryParameterName(fieldName),
                        formValue("it", property.typeInfo),
                    )
                } else {
                    add(
                        "\nbuilder.%L(%S, %L)",
                        method,
                        parameter.queryParameterName(fieldName),
                        formValue(expression, property.typeInfo),
                    )
                }
            }
        } else {
            add("\nbuilder.%L(%S, buildList {", method, parameter.queryParameterName())
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                if (property.nullable) {
                    add("\n%L?.let { add(%S); add(%L) }", expression, property.fieldName, formValue("it", property.typeInfo))
                } else {
                    add("\nadd(%S)", property.fieldName)
                    add("\nadd(%L)", formValue(expression, property.typeInfo))
                }
            }
            add("\n}.joinToString(%S))", ",")
        }
        if (optional) add("\n}")
        add("\n}")
    }

    private fun RequestParameter.queryParameterMethod(): String = if (allowReserved) "addEncodedQueryParameter" else "addQueryParameter"

    private fun RequestParameter.queryParameterName(name: String = originalName): String =
        if (allowReserved) name.percentEncodeQueryName() else name

    private fun String.percentEncodeQueryName(): String =
        toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            val character = value.toChar()
            if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~") {
                character.toString()
            } else {
                "%${value.toString(16).uppercase().padStart(2, '0')}"
            }
        }

    private fun CodeBlock.Builder.addHeaderParamStatement(): CodeBlock.Builder {
        this.add("\nval headerBuilder = Headers.Builder()")
        parameters
            .filterIsInstance<RequestParameter>()
            .filter { it.parameterLocation == HeaderParam }
            .forEach {
                this.add(
                    "\n.%T(%S, %L)",
                    "header".toClassName(packages.client),
                    it.originalName,
                    it.name + if (it.typeInfo is KotlinTypeInfo.Enum) "?.value" else "",
                )
            }
        addCookieParams()
        this.add("\nadditionalHeaders.forEach { headerBuilder.header(it.key, it.value) }")

        return this.add("\nval httpHeaders: %T = headerBuilder.build()\n", "Headers".toClassName("okhttp3"))
    }

    private fun CodeBlock.Builder.addCookieParams() {
        val cookieParameters =
            parameters
                .filterIsInstance<RequestParameter>()
                .filter { it.parameterLocation == CookieParam }
        if (cookieParameters.isEmpty()) return

        add("\nval cookieValues = buildList {")
        cookieParameters.forEach { parameter ->
            val receiver = parameter.name + if (parameter.isRequired) "" else "?"
            when (val typeInfo = parameter.typeInfo) {
                is KotlinTypeInfo.Array ->
                    if (parameter.explode == false) {
                        if (nativeGeneration && typeInfo.parameterizedType is KotlinTypeInfo.Enum) {
                            add(
                                "\n%L.let { add(%S + it.joinToString(%S) { value -> value.value }) }",
                                receiver,
                                "${parameter.originalName}=",
                                ",",
                            )
                        } else {
                            add("\n%L.let { add(%S + it.joinToString(%S)) }", receiver, "${parameter.originalName}=", ",")
                        }
                    } else {
                        val value = if (nativeGeneration && typeInfo.parameterizedType is KotlinTypeInfo.Enum) "it.value" else "it"
                        add("\n%L.forEach { add(%S + %L) }", receiver, "${parameter.originalName}=", value)
                    }

                else -> {
                    val value = if (parameter.typeInfo is KotlinTypeInfo.Enum) "it.value" else "it"
                    add("\n%L.let { add(%S + %L) }", receiver, "${parameter.originalName}=", value)
                }
            }
        }
        add("\n}")
        add("\nif (cookieValues.isNotEmpty()) headerBuilder.add(%S, cookieValues.joinToString(%S))", "Cookie", "; ")
    }

    private fun CodeBlock.Builder.addRequestStatement(): CodeBlock.Builder {
        val formParameters = parameters.filterIsInstance<FormParameter>()
        if (formParameters.isNotEmpty()) {
            addFormBodyStatement(formParameters)
            add("\nval request: %T = Request.Builder()", "Request".toClassName("okhttp3"))
            add("\n.url(httpUrl)\n.headers(httpHeaders)")
            when (val op = verb.uppercase(getDefault())) {
                "PUT" -> add("\n.put(formBody)")
                "POST" -> add("\n.post(formBody)")
                "PATCH" -> add("\n.patch(formBody)")
                else -> add("\n.method(%S, formBody)", op)
            }
        } else if (multipartRequestBody) {
            // For multipart requests, build the multipart body first, then the request
            this.addMultipartBodyStatement()
            this.add("\nval request: %T = Request.Builder()", "Request".toClassName("okhttp3"))
            this.add("\n.url(httpUrl)\n.headers(httpHeaders)")
            when (val op = verb.uppercase(getDefault())) {
                "PUT" -> this.add("\n.put(multipartBody)")
                "POST" -> this.add("\n.post(multipartBody)")
                "PATCH" -> this.add("\n.patch(multipartBody)")
                "DELETE" -> this.add("\n.method(%S, multipartBody)", op)
                else -> throw IllegalArgumentException("API operation $op is not supported for multipart")
            }
        } else {
            // Regular requests
            this.add(
                "\nval request: %T = %T.Builder()",
                "Request".toClassName("okhttp3"),
                "Request".toClassName("okhttp3"),
            )
            this.add("\n.url(httpUrl)\n.headers(httpHeaders)")
            when (val op = verb.uppercase(getDefault())) {
                "PUT" -> this.addRequestSerializerStatement("put")
                "POST" -> this.addRequestSerializerStatement("post")
                "PATCH" -> this.addRequestSerializerStatement("patch")
                "HEAD" -> this.add("\n.head()")
                "GET" -> this.add("\n.get()")
                "DELETE" -> this.add("\n.delete()")
                else -> this.addGenericRequestStatement(op)
            }
        }
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addRequestExecutionStatement() =
        when (returnType) {
            ByteArray::class.asTypeName() ->
                this.add("\nreturn request.execute(okHttpClient)\n")

            Unit::class.asTypeName() ->
                if (ClientCodeGenOptionType.OKHTTP_NON_NULL_RESPONSE_PAYLOADS in options) {
                    this.add("\nreturn request.executeWithoutResponseBody(okHttpClient)\n")
                } else {
                    this.add("\nreturn request.execute(okHttpClient, objectMapper, jacksonTypeRef())\n")
                }

            else ->
                this.add("\nreturn request.execute(okHttpClient, objectMapper, jacksonTypeRef())\n")
        }

    private fun CodeBlock.Builder.addRequestSerializerStatement(verb: String) {
        val toRequestBody = "toRequestBody".toClassName("okhttp3.RequestBody.Companion")
        parameters.filterIsInstance<BodyParameter>().firstOrNull()?.let {
            this.add(
                "\n.%N(objectMapper.writeValueAsString(%N).%T(%S.%T()))",
                verb,
                it.name,
                toRequestBody,
                requestContentType,
                "toMediaType".toClassName("okhttp3.MediaType.Companion"),
            )
        } ?: this.add("\n.%N(ByteArray(0).%T())", verb, toRequestBody)
    }

    private fun CodeBlock.Builder.addGenericRequestStatement(verb: String) {
        add("\n.method(\n")
        indent()
        add("%S,\n", verb)
        val body = parameters.filterIsInstance<BodyParameter>().firstOrNull()
        if (body == null) {
            add("null")
            unindent()
            add("\n)")
            return
        }
        add(
            "objectMapper.writeValueAsString(%N).%T(%S.%T())",
            body.name,
            "toRequestBody".toClassName("okhttp3.RequestBody.Companion"),
            requestContentType ?: "application/json",
            "toMediaType".toClassName("okhttp3.MediaType.Companion"),
        )
        unindent()
        add("\n)")
    }

    private fun CodeBlock.Builder.addMultipartBodyStatement() {
        this.add("\nval multipartBuilder = %T()", "MultipartBody.Builder".toClassName("okhttp3"))
        this.add("\n.setType(%T.FORM)", "MultipartBody".toClassName("okhttp3"))

        // First handle the array binary files with forEach loops
        parameters
            .filterIsInstance<MultipartParameter>()
            .filter { it.isBinaryFile && it.isArray }
            .forEach { param ->
                this.add("\n%N?.forEachIndexed { index, fileData ->", param.name)
                if (param.headers.isEmpty()) {
                    this.add(
                        "\n      multipartBuilder.addFormDataPart(%S, fileData.filename, fileData.requestBody)",
                        param.partName,
                    )
                } else {
                    addMultipartPartHeaders(param, "fileData.filename", "index")
                    this.add("\n      multipartBuilder.addPart(${param.name}PartHeaders, fileData.requestBody)")
                }
                this.add("\n}")
            }

        // Then handle other parameters using multipartBuilder directly
        parameters
            .filterIsInstance<MultipartParameter>()
            .filter { !(it.isBinaryFile && it.isArray) }
            .forEach { param ->
                if (!param.isRequired) this.add("\n%N?.let {", param.name)
                when {
                    param.isBinaryFile -> {
                        if (nativeGeneration && param.headers.isNotEmpty()) {
                            addMultipartPartHeaders(param, "${param.name}.filename")
                            this.add("\n    multipartBuilder.addPart(${param.name}PartHeaders, %N.requestBody)", param.name)
                        } else {
                            this.add(
                                "\n    multipartBuilder.addFormDataPart(%S, %N.filename, %N.requestBody)",
                                param.partName,
                                param.name,
                                param.name,
                            )
                        }
                    }

                    !nativeGeneration && param.contentType == "application/json" -> {
                        this.add(
                            "\n    multipartBuilder.addFormDataPart(%S, objectMapper.writeValueAsString(%N))",
                            param.partName,
                            param.name,
                        )
                    }

                    !nativeGeneration -> {
                        this.add(
                            "\n    multipartBuilder.addFormDataPart(%S, %N.toString())",
                            param.partName,
                            param.name,
                        )
                    }

                    else -> {
                        val value =
                            if (param.contentType.isJsonMediaType()) {
                                CodeBlock.of("objectMapper.writeValueAsString(%N)", param.name)
                            } else {
                                CodeBlock.of("%N.toString()", param.name)
                            }
                        val requestBody =
                            CodeBlock.of(
                                "%L.%T(%S.%T())",
                                value,
                                "toRequestBody".toClassName("okhttp3.RequestBody.Companion"),
                                param.contentType ?: "text/plain",
                                "toMediaType".toClassName("okhttp3.MediaType.Companion"),
                            )
                        if (param.headers.isEmpty()) {
                            this.add("\n    multipartBuilder.addFormDataPart(%S, null, %L)", param.partName, requestBody)
                        } else {
                            addMultipartPartHeaders(param)
                            this.add("\n    multipartBuilder.addPart(${param.name}PartHeaders, %L)", requestBody)
                        }
                    }
                }
                if (!param.isRequired) this.add("\n}")
            }

        this.add("\nval multipartBody = multipartBuilder.build()")
    }

    private fun CodeBlock.Builder.addMultipartPartHeaders(
        parameter: MultipartParameter,
        filename: String? = null,
        index: String? = null,
    ) {
        val contentDisposition =
            if (filename == null) {
                CodeBlock.of("%S", "form-data; name=\"${parameter.partName}\"")
            } else {
                CodeBlock.of("%S + %L + %S", "form-data; name=\"${parameter.partName}\"; filename=\"", filename, "\"")
            }
        add("\n    val ${parameter.name}PartHeadersBuilder = %T.Builder()", "Headers".toClassName("okhttp3"))
        add("\n      .add(%S, %L)", "Content-Disposition", contentDisposition)
        parameter.headers.forEach { header ->
            val headerValue = parameter.headerValue(header.name, index)
            if (header.isRequired) {
                add(
                    "\n    ${parameter.name}PartHeadersBuilder.add(%S, %L)",
                    header.originalName,
                    header.toWireValue(headerValue.requiredValue),
                )
            } else {
                add(
                    "\n    %L?.let { ${parameter.name}PartHeadersBuilder.add(%S, %L) }",
                    headerValue.expression,
                    header.originalName,
                    header.toWireValue("it"),
                )
            }
        }
        add("\n    val ${parameter.name}PartHeaders = ${parameter.name}PartHeadersBuilder.build()")
    }

    private fun MultipartParameter.headerValue(
        name: String,
        index: String?,
    ): MultipartHeaderValue =
        when {
            isArray && isRequired -> MultipartHeaderValue("$name.getOrNull($index)", "requireNotNull($name.getOrNull($index))")
            isArray -> MultipartHeaderValue("$name?.getOrNull($index)", "requireNotNull($name?.getOrNull($index))")
            isRequired -> MultipartHeaderValue(name, name)
            else -> MultipartHeaderValue(name, "requireNotNull($name)")
        }

    private data class MultipartHeaderValue(
        val expression: String,
        val requiredValue: String,
    )

    private fun String?.isJsonMediaType(): Boolean = this == "application/json" || this?.substringBefore(';')?.endsWith("+json") == true

    private fun CodeBlock.Builder.addFormBodyStatement(parameters: List<FormParameter>) {
        add("\nval formBuilder = %T.Builder()", "FormBody".toClassName("okhttp3"))
        parameters.forEach { parameter ->
            val method = if (parameter.allowReserved) "addEncoded" else "add"
            val optional = !parameter.isRequired
            if (optional) add("\n%N?.let { value ->", parameter.name)
            val valueName = if (optional) "value" else parameter.name
            if (parameter.objectProperties.isNotEmpty()) {
                addFormObjectParameter(parameter, valueName, method)
                if (optional) add("\n}")
                return@forEach
            }
            when (val typeInfo = parameter.typeInfo) {
                is KotlinTypeInfo.Array -> {
                    val itemValue = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) "it.value" else "it.toString()"
                    if (parameter.explode) {
                        add("\n%N.forEach { formBuilder.%L(%S, %L) }", valueName, method, parameter.fieldName, itemValue)
                    } else {
                        val delimiter =
                            when (parameter.style) {
                                "spaceDelimited" -> " "
                                "pipeDelimited" -> "|"
                                else -> ","
                            }
                        val transform = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) " { it.value }" else ""
                        add("\nformBuilder.%L(%S, %N.joinToString(%S)$transform)", method, parameter.fieldName, valueName, delimiter)
                    }
                }
                else -> {
                    val suffix = if (typeInfo is KotlinTypeInfo.Enum) ".value" else ".toString()"
                    add("\nformBuilder.%L(%S, %N%L)", method, parameter.fieldName, valueName, suffix)
                }
            }
            if (optional) add("\n}")
        }
        add("\nval formBody = formBuilder.build()")
    }

    private fun CodeBlock.Builder.addFormObjectParameter(
        parameter: FormParameter,
        valueName: String,
        method: String,
    ) {
        if (parameter.explode) {
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                val fieldName = parameter.formObjectFieldName(property)
                if (property.nullable) {
                    add("\n%L?.let { formBuilder.%L(%S, %L) }", expression, method, fieldName, formValue("it", property.typeInfo))
                } else {
                    add("\nformBuilder.%L(%S, %L)", method, fieldName, formValue(expression, property.typeInfo))
                }
            }
        } else {
            add("\nformBuilder.%L(%S, buildList {", method, parameter.fieldName)
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                if (property.nullable) {
                    add("\n%L?.let { add(%S); add(%L) }", expression, property.fieldName, formValue("it", property.typeInfo))
                } else {
                    add("\nadd(%S)", property.fieldName)
                    add("\nadd(%L)", formValue(expression, property.typeInfo))
                }
            }
            add("\n}.joinToString(%S))", ",")
        }
    }

    private fun FormParameter.formObjectFieldName(property: FormObjectProperty): String =
        if (style == "deepObject") "$fieldName[${property.fieldName}]" else property.fieldName

    private fun formValue(
        expression: String,
        typeInfo: KotlinTypeInfo,
    ): CodeBlock =
        when (typeInfo) {
            is KotlinTypeInfo.Enum -> CodeBlock.of("%L.value", expression)
            is KotlinTypeInfo.Array -> CodeBlock.of("%L.joinToString(%S)", expression, ",")
            else -> CodeBlock.of("%L.toString()", expression)
        }
}
