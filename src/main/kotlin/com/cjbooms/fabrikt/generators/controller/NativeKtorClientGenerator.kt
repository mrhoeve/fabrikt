package com.cjbooms.fabrikt.generators.controller

import com.cjbooms.fabrikt.cli.ClientCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.generators.GeneratorUtils.splitByType
import com.cjbooms.fabrikt.generators.client.ClientGenerator
import com.cjbooms.fabrikt.model.ClientType
import com.cjbooms.fabrikt.model.Clients
import com.cjbooms.fabrikt.model.Destinations
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.KotlinTypeInfo
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
        val (pathParams, queryParams, headerParams, bodyParams) = parameters.splitByType()
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
                        .addStatement(
                            "val response = httpClient.%M(url) {",
                            MemberName("io.ktor.client.request", operation.method, isExtension = true),
                        ).indent()
                        .apply {
                            addStatement(
                                "%M(\"Accept\", %S)",
                                MemberName("io.ktor.client.request", "header"),
                                context.primaryResponseContentType(operation) ?: "application/json",
                            )
                            if (bodyParams.isNotEmpty()) {
                                addStatement(
                                    "%M(\"Content-Type\", %S)",
                                    MemberName("io.ktor.client.request", "header"),
                                    context.requestContentType(operation) ?: "application/json",
                                )
                                addStatement("%M(%L)", MemberName("io.ktor.client.request", "setBody"), bodyParams.first().name)
                            }
                            headerParams.forEach {
                                addStatement("%M(%S, %L)", MemberName("io.ktor.client.request", "header"), it.originalName, it.name)
                            }
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
        bodyParams.firstOrNull()?.let { function.addParameter(it.toParameterSpecBuilder().build()) }
        (pathParams + queryParams + headerParams).forEach { parameter ->
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
        queryParams.forEach { parameter ->
            if (parameter.typeInfo is KotlinTypeInfo.Array) {
                addStatement(
                    if (parameter.isRequired) "%N.forEach { add(\"%L=\${it}\") }" else "%N?.forEach { add(\"%L=\${it}\") }",
                    parameter.name,
                    parameter.originalName,
                )
            } else {
                addStatement(
                    if (parameter.isRequired) "add(\"%L=\${%N}\")" else "%N?.let { add(\"%L=\${it}\") }",
                    parameter.originalName,
                    parameter.name,
                )
            }
        }
        unindent()
        addStatement("}")
        addStatement("if (params.isNotEmpty()) append(\"?\").append(params.joinToString(\"&\"))")
        unindent()
        addStatement("}")
        return this
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
        return setOf(
            SimpleFile(clientDir.resolve("KtorApiModels.kt"), KtorClientLibraryFiles.ktorApiModels(packages.client).toString()),
            SimpleFile(
                clientDir.resolve("KtorApiConfiguration.kt"),
                KtorClientLibraryFiles.ktorApiConfiguration(packages.client, context.operations.serverUrl.orEmpty()).toString(),
            ),
        )
    }
}
