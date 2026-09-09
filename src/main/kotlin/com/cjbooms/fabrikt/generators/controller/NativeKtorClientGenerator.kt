package com.cjbooms.fabrikt.generators.controller

import com.cjbooms.fabrikt.cli.ClientCodeGenOptionType
import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.generators.GeneratorUtils.splitByType
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.generators.client.ClientGenerator
import com.cjbooms.fabrikt.model.ClientType
import com.cjbooms.fabrikt.model.Clients
import com.cjbooms.fabrikt.model.Destinations
import com.cjbooms.fabrikt.model.FormObjectProperty
import com.cjbooms.fabrikt.model.FormParameter
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.MultipartParameter
import com.cjbooms.fabrikt.model.RequestParameter
import com.cjbooms.fabrikt.model.SimpleFile
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.util.GroupingStrategy
import com.cjbooms.fabrikt.util.NormalisedString.camelCase
import com.github.javaparser.utils.CodeGenerationUtils
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import java.nio.file.Path

internal class NativeKtorClientGenerator(
    private val packages: Packages,
    private val context: GeneratorEndpointContext,
    private val srcPath: Path = Destinations.MAIN_KT_SOURCE,
) : ClientGenerator {
    private val networkResult = ClassName(packages.client, "NetworkResult")
    private val networkError = ClassName(packages.client, "NetworkError")

    override fun generate(options: Set<ClientCodeGenOptionType>): Clients {
        val strategy =
            if (ClientCodeGenOptionType.GROUP_BY_TAG in options) GroupingStrategy.BY_FIRST_TAG else GroupingStrategy.BY_FIRST_PATH_SEGMENT
        val clients =
            context
                .groupedPaths(strategy)
                .map { (resourceName, paths) ->
                    val type =
                        TypeSpec
                            .classBuilder(resourceName + "Client")
                            .addProperty(
                                PropertySpec
                                    .builder("httpClient", ClassName("io.ktor.client", "HttpClient"))
                                    .addModifiers(KModifier.PRIVATE)
                                    .initializer("httpClient")
                                    .build(),
                            ).primaryConstructor(
                                FunSpec
                                    .constructorBuilder()
                                    .addParameter("httpClient", ClassName("io.ktor.client", "HttpClient"))
                                    .build(),
                            )
                    paths.forEach { path -> path.operations.forEach { type.addFunction(buildFunction(path, it)) } }
                    ClientType(type.build(), packages.base)
                }.toSet()
        return Clients(clients)
    }

    private fun buildFunction(
        path: GeneratorPathItem,
        operation: GeneratorOperation,
    ): FunSpec {
        val parameters = context.clientParameters(operation, path)
        val (pathParams, queryParams, headerParams, cookieParams, bodyParams) = parameters.splitByType()
        val multipartParams = parameters.filterIsInstance<MultipartParameter>()
        val formParams = parameters.filterIsInstance<FormParameter>()
        val requestBodies = bodyParams + multipartParams + formParams
        val responseType = context.successResponseType(operation, packages.base)
        val function =
            FunSpec
                .builder(clientRequestFunctionName(operation, pathParams))
                .addModifiers(KModifier.SUSPEND)
                .returns(networkResult.parameterizedBy(responseType))
                .addCode(
                    CodeBlock
                        .builder()
                        .addUrl(path.path, pathParams, queryParams)
                        .beginControlFlow("return try")
                        .addRequestStart(operation.method)
                        .apply {
                            addStatement(
                                "%M(\"Accept\", %S)",
                                MemberName("io.ktor.client.request", "header"),
                                context.primaryResponseContentType(operation) ?: "application/json",
                            )
                            if (requestBodies.isNotEmpty()) {
                                addStatement(
                                    "%M(\"Content-Type\", %S)",
                                    MemberName("io.ktor.client.request", "header"),
                                    context.requestContentType(operation) ?: "application/json",
                                )
                                if (formParams.isNotEmpty()) {
                                    addFormBody(formParams)
                                } else if (multipartParams.isEmpty()) {
                                    addStatement("%M(%L)", MemberName("io.ktor.client.request", "setBody"), bodyParams.first().name)
                                } else {
                                    addMultipartBody(multipartParams)
                                }
                            }
                            headerParams.forEach {
                                addStatement("%M(%S, %L)", MemberName("io.ktor.client.request", "header"), it.originalName, it.name)
                            }
                            addCookies(cookieParams)
                            addStatement("%M {", MemberName("io.ktor.client.request", "headers"))
                            indent()
                            addStatement("apiConfiguration.customHeaders.forEach { (name, value) ->")
                            indent()
                            addStatement("remove(name)")
                            addStatement("append(name, value)")
                            unindent()
                            addStatement("}")
                            unindent()
                            addStatement("}")
                        }.unindent()
                        .addStatement("}")
                        .beginControlFlow("if (response.status.%M())", MemberName("io.ktor.http", "isSuccess"))
                        .addStatement("%T.Success(response.%M())", networkResult, MemberName("io.ktor.client.call", "body"))
                        .nextControlFlow("else")
                        .addStatement(
                            "val errorBody = response.%M().ifBlank { null }",
                            MemberName("io.ktor.client.statement", "bodyAsText"),
                        ).addStatement(
                            "%T.Failure(%T.Http(statusCode = response.status.value, statusDescription = response.status.description, body = errorBody))",
                            networkResult,
                            networkError,
                        ).endControlFlow()
                        .nextControlFlow("catch (e: %T)", ClassName("io.ktor.client.plugins", "ResponseException"))
                        .addStatement("val status = e.response.status")
                        .addStatement(
                            "val body = runCatching { e.response.%M() }.getOrNull()?.ifBlank { null }",
                            MemberName("io.ktor.client.statement", "bodyAsText"),
                        ).addStatement("%T.Failure(%T.Http(status.value, status.description, body))", networkResult, networkError)
                        .nextControlFlow("catch (e: %T)", ClassName("kotlinx.io", "IOException"))
                        .addStatement("%T.Failure(%T.Network(e))", networkResult, networkError)
                        .nextControlFlow("catch (e: %T)", ClassName("io.ktor.serialization", "ContentConvertException"))
                        .addStatement("%T.Failure(%T.Serialization(e))", networkResult, networkError)
                        .nextControlFlow("catch (e: %T)", ClassName("io.ktor.client.call", "NoTransformationFoundException"))
                        .addStatement("%T.Failure(%T.Serialization(e))", networkResult, networkError)
                        .nextControlFlow("catch (e: %T)", ClassName("kotlinx.coroutines", "CancellationException"))
                        .addStatement("throw e")
                        .nextControlFlow("catch (e: Exception)")
                        .addStatement("%T.Failure(%T.Unknown(e))", networkResult, networkError)
                        .endControlFlow()
                        .build(),
                )
        requestBodies.forEach { function.addParameter(it.toParameterSpecBuilder().build()) }
        (pathParams + queryParams + headerParams + cookieParams).forEach { parameter ->
            function.addParameter(
                parameter
                    .toParameterSpecBuilder()
                    .apply { if (!parameter.isRequired) defaultValue("null") }
                    .build(),
            )
        }
        val apiConfiguration = ClassName(packages.client, "ApiConfiguration")
        function.addParameter(ParameterSpec.builder("apiConfiguration", apiConfiguration).defaultValue("%T()", apiConfiguration).build())
        function.addKdoc(context.toKdoc(operation, parameters))
        return function.build()
    }

    private fun CodeBlock.Builder.addCookies(parameters: List<RequestParameter>) {
        parameters.forEach { parameter ->
            val cookie = MemberName("io.ktor.client.request", "cookie")
            when (val typeInfo = parameter.typeInfo) {
                is KotlinTypeInfo.Array -> {
                    val itemValue = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) "it.value" else "it.toString()"
                    if (parameter.explode == false) {
                        val joinedValues =
                            if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) {
                                "%N.joinToString(%S) { it.value }"
                            } else {
                                "%N.joinToString(%S)"
                            }
                        if (parameter.isRequired) {
                            addStatement("%M(%S, $joinedValues)", cookie, parameter.originalName, parameter.name, ",")
                        } else {
                            add("%N?.let { values -> %M(%S, ", parameter.name, cookie, parameter.originalName)
                            if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) {
                                add("values.joinToString(%S) { it.value }", ",")
                            } else {
                                add("values.joinToString(%S)", ",")
                            }
                            add(") }\n")
                        }
                    } else if (parameter.isRequired) {
                        addStatement("%N.forEach { %M(%S, %L) }", parameter.name, cookie, parameter.originalName, itemValue)
                    } else {
                        addStatement("%N?.forEach { %M(%S, %L) }", parameter.name, cookie, parameter.originalName, itemValue)
                    }
                }
                else -> {
                    val valueSuffix = if (typeInfo is KotlinTypeInfo.Enum) ".value" else ".toString()"
                    if (parameter.isRequired) {
                        addStatement("%M(%S, %N%L)", cookie, parameter.originalName, parameter.name, valueSuffix)
                    } else {
                        addStatement("%N?.let { %M(%S, it%L) }", parameter.name, cookie, parameter.originalName, valueSuffix)
                    }
                }
            }
        }
    }

    private fun CodeBlock.Builder.addMultipartBody(parameters: List<MultipartParameter>) {
        if (parameters.any { it.contentType.isJsonMediaType() } && MutableSettings.serializationLibrary.isJackson) {
            val mapperPackage =
                when (MutableSettings.serializationLibrary) {
                    SerializationLibrary.JACKSON -> "com.fasterxml.jackson.databind.json"
                    SerializationLibrary.JACKSON_3 -> "tools.jackson.databind.json"
                    SerializationLibrary.KOTLINX_SERIALIZATION -> error("Kotlinx serialization does not use a Jackson mapper")
                }
            addStatement("val multipartObjectMapper = %T.builder().findAndAddModules().build()", ClassName(mapperPackage, "JsonMapper"))
        }
        addStatement("%M(", MemberName("io.ktor.client.request", "setBody"))
        indent()
        addStatement("%T(", ClassName("io.ktor.client.request.forms", "MultiPartFormDataContent"))
        indent()
        addStatement("%M {", MemberName("io.ktor.client.request.forms", "formData"))
        indent()
        parameters.forEach { addMultipartParameter(it) }
        unindent()
        addStatement("}")
        unindent()
        addStatement(")")
        unindent()
        addStatement(")")
    }

    private fun CodeBlock.Builder.addFormBody(parameters: List<FormParameter>) {
        addStatement("%M(", MemberName("io.ktor.client.request", "setBody"))
        indent()
        addStatement("%T(", ClassName("io.ktor.client.request.forms", "FormDataContent"))
        indent()
        addStatement("%M {", MemberName("io.ktor.http", "parameters"))
        indent()
        parameters.forEach { parameter -> addFormParameter(parameter) }
        unindent()
        addStatement("}")
        unindent()
        addStatement(")")
        unindent()
        addStatement(")")
    }

    private fun CodeBlock.Builder.addFormParameter(parameter: FormParameter) {
        val optional = !parameter.isRequired
        if (optional) {
            addStatement("%N?.let { value ->", parameter.name)
            indent()
        }
        val valueName = if (optional) "value" else parameter.name
        if (parameter.objectProperties.isNotEmpty()) {
            addFormObjectParameter(parameter, valueName)
            if (optional) {
                unindent()
                addStatement("}")
            }
            return
        }
        when (val typeInfo = parameter.typeInfo) {
            is KotlinTypeInfo.Array -> {
                val itemValue = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) "it.value" else "it.toString()"
                if (parameter.explode) {
                    addStatement("%N.forEach { append(%S, %L) }", valueName, parameter.fieldName, itemValue)
                } else {
                    val delimiter =
                        when (parameter.style) {
                            "spaceDelimited" -> " "
                            "pipeDelimited" -> "|"
                            else -> ","
                        }
                    val transform = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) " { it.value }" else ""
                    addStatement("append(%S, %N.joinToString(%S)$transform)", parameter.fieldName, valueName, delimiter)
                }
            }
            else -> {
                val suffix = if (typeInfo is KotlinTypeInfo.Enum) ".value" else ".toString()"
                addStatement("append(%S, %N%L)", parameter.fieldName, valueName, suffix)
            }
        }
        if (optional) {
            unindent()
            addStatement("}")
        }
    }

    private fun CodeBlock.Builder.addFormObjectParameter(
        parameter: FormParameter,
        valueName: String,
    ) {
        if (parameter.explode) {
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                val fieldName = parameter.formObjectFieldName(property)
                if (property.nullable) {
                    addStatement("%L?.let { append(%S, %L) }", expression, fieldName, formValue("it", property.typeInfo))
                } else {
                    addStatement("append(%S, %L)", fieldName, formValue(expression, property.typeInfo))
                }
            }
        } else {
            add("append(%S, buildList {\n", parameter.fieldName)
            indent()
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                if (property.nullable) {
                    addStatement("%L?.let { add(%S); add(%L) }", expression, property.fieldName, formValue("it", property.typeInfo))
                } else {
                    addStatement("add(%S)", property.fieldName)
                    addStatement("add(%L)", formValue(expression, property.typeInfo))
                }
            }
            unindent()
            addStatement("}.joinToString(%S))", ",")
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

    private fun CodeBlock.Builder.addMultipartParameter(parameter: MultipartParameter) {
        val wrapped = parameter.isArray || !parameter.isRequired
        if (wrapped) {
            addStatement(
                "%N%L.%L { part ->",
                parameter.name,
                if (parameter.isRequired) "" else "?",
                if (parameter.isArray) "forEach" else "let",
            )
            indent()
        }
        val valueName = if (wrapped) "part" else parameter.name
        addStatement("append(")
        indent()
        addStatement("%S,", parameter.partName)
        when {
            parameter.isBinaryFile -> addStatement("%N,", valueName)
            parameter.contentType.isJsonMediaType() -> addStatement("%L,", multipartJsonValue(valueName))
            else -> addStatement("%N.toString(),", valueName)
        }
        addStatement("%T.build {", ClassName("io.ktor.http", "Headers"))
        indent()
        addStatement(
            "append(%T.ContentType, %S)",
            ClassName("io.ktor.http", "HttpHeaders"),
            parameter.contentType ?: "text/plain",
        )
        if (parameter.isBinaryFile) {
            addStatement(
                "append(%T.ContentDisposition, %S)",
                ClassName("io.ktor.http", "HttpHeaders"),
                "filename=\"${parameter.partName}\"",
            )
        }
        unindent()
        addStatement("}")
        unindent()
        addStatement(")")
        if (wrapped) {
            unindent()
            addStatement("}")
        }
    }

    private fun multipartJsonValue(valueName: String): CodeBlock =
        when (MutableSettings.serializationLibrary) {
            SerializationLibrary.JACKSON,
            SerializationLibrary.JACKSON_3,
            -> CodeBlock.of("multipartObjectMapper.writeValueAsString(%N)", valueName)
            SerializationLibrary.KOTLINX_SERIALIZATION ->
                CodeBlock.of(
                    "%T.%M(%N)",
                    ClassName("kotlinx.serialization.json", "Json"),
                    MemberName("kotlinx.serialization", "encodeToString"),
                    valueName,
                )
        }

    private fun String?.isJsonMediaType(): Boolean = this == "application/json" || this?.substringBefore(';')?.endsWith("+json") == true

    private fun CodeBlock.Builder.addRequestStart(method: String): CodeBlock.Builder {
        val normalisedMethod = method.lowercase()
        if (normalisedMethod in STANDARD_HTTP_METHODS) {
            addStatement(
                "val response = httpClient.%M(url) {",
                MemberName("io.ktor.client.request", normalisedMethod, isExtension = true),
            )
        } else {
            addStatement("val response = httpClient.%M(url) {", MemberName("io.ktor.client.request", "request", isExtension = true))
            indent()
            addStatement("method = %T(%S)", ClassName("io.ktor.http", "HttpMethod"), method.uppercase())
            return this
        }
        indent()
        return this
    }

    private fun CodeBlock.Builder.addUrl(
        path: String,
        pathParams: List<RequestParameter>,
        queryParams: List<RequestParameter>,
    ): CodeBlock.Builder {
        val resolvedPath =
            buildString {
                append(path)
                pathParams.forEach { parameter ->
                    val placeholder = "{${parameter.originalName}}"
                    val index = indexOf(placeholder)
                    if (index >= 0) replace(index, index + placeholder.length, "\${${parameter.name}}")
                }
            }
        addStatement("val basePath = apiConfiguration.basePath.trimEnd('/')")
        if (queryParams.isEmpty()) return addStatement("val url = basePath + %P", resolvedPath)
        add("val url = buildString {\n")
        indent()
        addStatement("append(basePath)")
        addStatement("append(%P)", resolvedPath)
        addStatement("val params = buildList {")
        indent()
        queryParams.forEach { addQueryParameter(it) }
        unindent()
        addStatement("}")
        addStatement("if (params.isNotEmpty()) append(\"?\").append(params.joinToString(\"&\"))")
        unindent()
        addStatement("}")
        return this
    }

    private fun CodeBlock.Builder.addQueryParameter(parameter: RequestParameter) {
        if (parameter.objectProperties.isNotEmpty()) {
            addQueryObjectParameter(parameter)
            return
        }
        val typeInfo = parameter.typeInfo
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
            val itemValue = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) "it.value" else "it.toString()"
            if (explode) {
                addStatement(
                    if (parameter.isRequired) "%N.forEach { add(%L) }" else "%N?.forEach { add(%L) }",
                    parameter.name,
                    queryPart(parameter.originalName, CodeBlock.of("%L", itemValue), parameter.allowReserved),
                )
            } else if (parameter.isRequired) {
                val transform = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) " { it.value }" else ""
                addStatement(
                    "add(%L)",
                    queryPart(
                        parameter.originalName,
                        CodeBlock.of("%N.joinToString(%S)$transform", parameter.name, delimiter),
                        parameter.allowReserved,
                    ),
                )
            } else {
                addStatement("%N?.let { values ->", parameter.name)
                indent()
                val transform = if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) " { it.value }" else ""
                addStatement(
                    "add(%L)",
                    queryPart(
                        parameter.originalName,
                        CodeBlock.of("values.joinToString(%S)$transform", delimiter),
                        parameter.allowReserved,
                    ),
                )
                unindent()
                addStatement("}")
            }
            return
        }
        if (parameter.isRequired) {
            addStatement(
                "add(%L)",
                queryPart(parameter.originalName, formValue(parameter.name, typeInfo), parameter.allowReserved),
            )
        } else {
            addStatement(
                "%N?.let { add(%L) }",
                parameter.name,
                queryPart(parameter.originalName, formValue("it", typeInfo), parameter.allowReserved),
            )
        }
    }

    private fun CodeBlock.Builder.addQueryObjectParameter(parameter: RequestParameter) {
        val optional = !parameter.isRequired
        if (optional) {
            addStatement("%N?.let { value ->", parameter.name)
            indent()
        }
        val valueName = if (optional) "value" else parameter.name
        val explode = parameter.explode ?: (parameter.style == null || parameter.style == "form")
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
                    addStatement(
                        "%L?.let { add(%L) }",
                        expression,
                        queryPart(fieldName, formValue("it", property.typeInfo), parameter.allowReserved),
                    )
                } else {
                    addStatement(
                        "add(%L)",
                        queryPart(fieldName, formValue(expression, property.typeInfo), parameter.allowReserved),
                    )
                }
            }
        } else {
            val valueEncoder =
                if (parameter.allowReserved) {
                    MemberName(packages.client, "encodeReservedQueryValue")
                } else {
                    MemberName("io.ktor.http", "encodeURLParameter")
                }
            add("add(%S.%M() + \"=\" + buildList {\n", parameter.originalName, MemberName("io.ktor.http", "encodeURLParameter"))
            indent()
            parameter.objectProperties.forEach { property ->
                val expression = "$valueName.${property.propertyName}"
                if (property.nullable) {
                    addStatement("%L?.let { add(%S); add(%L) }", expression, property.fieldName, formValue("it", property.typeInfo))
                } else {
                    addStatement("add(%S)", property.fieldName)
                    addStatement("add(%L)", formValue(expression, property.typeInfo))
                }
            }
            unindent()
            addStatement("}.joinToString(%S).%M())", ",", valueEncoder)
        }
        if (optional) {
            unindent()
            addStatement("}")
        }
    }

    private fun queryPart(
        name: String,
        value: CodeBlock,
        allowReserved: Boolean,
    ): CodeBlock {
        val nameEncoder = MemberName("io.ktor.http", "encodeURLParameter")
        val valueEncoder =
            if (allowReserved) {
                MemberName(packages.client, "encodeReservedQueryValue")
            } else {
                MemberName("io.ktor.http", "encodeURLParameter")
            }
        return CodeBlock.of("%S.%M() + \"=\" + (%L).%M()", name, nameEncoder, value, valueEncoder)
    }

    private fun clientRequestFunctionName(
        operation: GeneratorOperation,
        pathParameters: List<RequestParameter>,
    ): String =
        operation.operationId?.camelCase() ?: buildString {
            append(operation.method.lowercase())
            if (pathParameters.isNotEmpty()) {
                append("By")
                append(pathParameters.joinToString("And") { it.name.replaceFirstChar(Char::uppercase) })
            }
        }

    override fun generateLibrary(options: Set<ClientCodeGenOptionType>): Collection<GeneratedFile> {
        val clientDir = srcPath.resolve(CodeGenerationUtils.packageToPath(packages.base)).resolve("client")
        return buildSet {
            add(SimpleFile(clientDir.resolve("KtorApiModels.kt"), KtorClientLibraryFiles.ktorApiModels(packages.client).toString()))
            add(
                SimpleFile(
                    clientDir.resolve("KtorApiConfiguration.kt"),
                    KtorClientLibraryFiles.ktorApiConfiguration(packages.client, context.operations.serverUrl.orEmpty()).toString(),
                ),
            )
            if (context.usesAllowReservedQueryParameters()) {
                add(SimpleFile(clientDir.resolve("KtorHttpUtil.kt"), KtorClientLibraryFiles.ktorHttpUtil(packages.client).toString()))
            }
        }
    }

    private companion object {
        val STANDARD_HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch")
    }

    private fun GeneratorEndpointContext.usesAllowReservedQueryParameters(): Boolean =
        operations.paths.any { path ->
            path.operations.any { operation ->
                (path.parameters + operation.parameters).any { parameter ->
                    parameter.placement == "query" && parameter.allowReserved == true
                }
            }
        }
}
