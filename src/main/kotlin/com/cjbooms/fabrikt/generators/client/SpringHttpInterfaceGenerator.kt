package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.cli.ClientCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.generators.GeneratorUtils.functionName
import com.cjbooms.fabrikt.generators.GeneratorUtils.getPrimaryContentMediaType
import com.cjbooms.fabrikt.generators.GeneratorUtils.toKdoc
import com.cjbooms.fabrikt.generators.TypeFactory
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.ADDITIONAL_HEADERS_PARAMETER_NAME
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.ADDITIONAL_QUERY_PARAMETERS_PARAMETER_NAME
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.addIncomingParameters
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.addSuspendModifier
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.deriveClientParameters
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.getReturnType
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.groupedClientPaths
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.optionallyParameterizeWithResponseEntity
import com.cjbooms.fabrikt.generators.client.ClientGeneratorUtils.simpleClientName
import com.cjbooms.fabrikt.generators.client.metadata.SpringHttpInterfaceAnnotations
import com.cjbooms.fabrikt.model.ClientType
import com.cjbooms.fabrikt.model.Clients
import com.cjbooms.fabrikt.model.CookieParam
import com.cjbooms.fabrikt.model.Destinations
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.HeaderParam
import com.cjbooms.fabrikt.model.IncomingParameter
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.PathParam
import com.cjbooms.fabrikt.model.QueryParam
import com.cjbooms.fabrikt.model.RequestParameter
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.util.GroupingStrategy
import com.reprezen.kaizen.oasparser.model3.Operation
import com.reprezen.kaizen.oasparser.model3.Path
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.buildCodeBlock

class SpringHttpInterfaceGenerator(
    private val packages: Packages,
    private val api: SourceApi,
    private val srcPath: java.nio.file.Path = Destinations.MAIN_KT_SOURCE,
) : ClientGenerator {
    private var generatorContext: GeneratorEndpointContext? = null

    internal constructor(
        packages: Packages,
        api: SourceApi,
        generatorContext: GeneratorEndpointContext,
    ) : this(packages, api) {
        this.generatorContext = generatorContext
    }

    override fun generate(options: Set<ClientCodeGenOptionType>): Clients {
        generatorContext?.let { return generate(it, options) }
        val clientTypes =
            api
                .groupedClientPaths(options)
                .map { (resourceName, paths) ->
                    val funcSpecs: List<FunSpec> =
                        paths.flatMap { (resource, path) ->
                            path.operations.map { (verb, operation) ->
                                buildFunction(path, resource, operation, verb, options)
                            }
                        }

                    val clientType =
                        TypeSpec
                            .interfaceBuilder(simpleClientName(resourceName))
                            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
                            .addFunctions(funcSpecs)
                            .build()

                    ClientType(clientType, packages.base)
                }.toSet()

        return Clients(clientTypes)
    }

    private fun generate(
        context: GeneratorEndpointContext,
        options: Set<ClientCodeGenOptionType>,
    ): Clients {
        val strategy =
            if (ClientCodeGenOptionType.GROUP_BY_TAG in options) GroupingStrategy.BY_FIRST_TAG else GroupingStrategy.BY_FIRST_PATH_SEGMENT
        val clients =
            context
                .groupedPaths(strategy)
                .map { (resourceName, paths) ->
                    val functions = paths.flatMap { path -> path.operations.map { buildFunction(context, path, it, options) } }
                    ClientType(
                        TypeSpec
                            .interfaceBuilder(simpleClientName(resourceName))
                            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
                            .addFunctions(functions)
                            .build(),
                        packages.base,
                    )
                }.toSet()
        return Clients(clients)
    }

    private fun buildFunction(
        path: Path,
        resource: String,
        operation: Operation,
        verb: String,
        options: Set<ClientCodeGenOptionType>,
    ): FunSpec {
        val parameters = deriveClientParameters(path, operation, packages.base)
        return FunSpec
            .builder(functionName(operation, resource, verb))
            .addModifiers(KModifier.ABSTRACT)
            .addKdoc(operation.toKdoc(parameters))
            .addHttpExchangeAnnotation(operation, resource, parameters, verb)
            .addSuspendModifier(options)
            .addIncomingParameters(
                parameters,
                annotateRequestParameterWith = { parameter ->
                    when (parameter.parameterLocation) {
                        is QueryParam -> {
                            SpringHttpInterfaceAnnotations
                                .requestParamBuilder()
                                .addMember("%S", parameter.originalName)
                                .build()
                        }

                        is HeaderParam -> {
                            SpringHttpInterfaceAnnotations
                                .requestHeaderBuilder()
                                .addMember("%S", parameter.originalName)
                                .build()
                        }

                        is PathParam -> {
                            SpringHttpInterfaceAnnotations
                                .pathVariableBuilder()
                                .addMember("%S", parameter.originalName)
                                .build()
                        }

                        is CookieParam -> {
                            SpringHttpInterfaceAnnotations
                                .cookieValueBuilder()
                                .addMember("%S", parameter.originalName)
                                .addMember("required = %L", parameter.isRequired)
                                .build()
                        }
                    }
                },
                annotateBodyParameterWith = { _ ->
                    SpringHttpInterfaceAnnotations
                        .requestBodyBuilder()
                        .build()
                },
            ).addParameter(
                ParameterSpec
                    .builder(
                        ADDITIONAL_HEADERS_PARAMETER_NAME,
                        TypeFactory.createMapOfStringToNonNullType(Any::class.asTypeName()),
                    ).addAnnotation(SpringHttpInterfaceAnnotations.requestHeaderBuilder().build())
                    .defaultValue("emptyMap()")
                    .build(),
            ).addParameter(
                ParameterSpec
                    .builder(
                        ADDITIONAL_QUERY_PARAMETERS_PARAMETER_NAME,
                        TypeFactory.createMapOfStringToNonNullType(Any::class.asTypeName()),
                    ).addAnnotation(SpringHttpInterfaceAnnotations.requestParamBuilder().build())
                    .defaultValue("emptyMap()")
                    .build(),
            ).returns(
                operation
                    .getReturnType(packages)
                    .optionallyParameterizeWithResponseEntity(options),
            ).build()
    }

    private fun buildFunction(
        context: GeneratorEndpointContext,
        path: GeneratorPathItem,
        operation: GeneratorOperation,
        options: Set<ClientCodeGenOptionType>,
    ): FunSpec {
        val parameters = context.clientParameters(operation, path)
        return FunSpec
            .builder(context.functionName(operation, path.path))
            .addModifiers(KModifier.ABSTRACT)
            .addKdoc(context.toKdoc(operation, parameters))
            .addHttpExchangeAnnotation(
                path.path,
                parameters,
                operation.method,
                context.primaryResponseContentType(operation),
                context.requestContentType(operation)?.takeIf { it.startsWith("multipart/form-data") },
            ).addSuspendModifier(options)
            .addIncomingParameters(
                parameters,
                annotateRequestParameterWith = { parameter ->
                    when (parameter.parameterLocation) {
                        is QueryParam ->
                            SpringHttpInterfaceAnnotations
                                .requestParamBuilder()
                                .addMember(
                                    "%S",
                                    parameter.originalName,
                                ).build()
                        is HeaderParam ->
                            SpringHttpInterfaceAnnotations
                                .requestHeaderBuilder()
                                .addMember(
                                    "%S",
                                    parameter.originalName,
                                ).build()
                        is PathParam -> SpringHttpInterfaceAnnotations.pathVariableBuilder().addMember("%S", parameter.originalName).build()
                        is CookieParam ->
                            SpringHttpInterfaceAnnotations
                                .cookieValueBuilder()
                                .addMember("%S", parameter.originalName)
                                .addMember("required = %L", parameter.isRequired)
                                .build()
                    }
                },
                annotateBodyParameterWith = { SpringHttpInterfaceAnnotations.requestBodyBuilder().build() },
                multipartParameterToSpecBuilder = { parameter ->
                    parameter
                        .toParameterSpecBuilder(treatAnyTypeHeadersAsStrings = true)
                        .addAnnotation(
                            SpringHttpInterfaceAnnotations
                                .requestPartBuilder()
                                .addMember("%S", parameter.partName)
                                .build(),
                        )
                },
            ).addParameter(
                ParameterSpec
                    .builder(
                        ADDITIONAL_HEADERS_PARAMETER_NAME,
                        TypeFactory.createMapOfStringToNonNullType(Any::class.asTypeName()),
                    ).addAnnotation(SpringHttpInterfaceAnnotations.requestHeaderBuilder().build())
                    .defaultValue("emptyMap()")
                    .build(),
            ).addParameter(
                ParameterSpec
                    .builder(
                        ADDITIONAL_QUERY_PARAMETERS_PARAMETER_NAME,
                        TypeFactory.createMapOfStringToNonNullType(Any::class.asTypeName()),
                    ).addAnnotation(SpringHttpInterfaceAnnotations.requestParamBuilder().build())
                    .defaultValue("emptyMap()")
                    .build(),
            ).returns(context.successResponseType(operation, packages.base).optionallyParameterizeWithResponseEntity(options))
            .build()
    }

    private fun FunSpec.Builder.addHttpExchangeAnnotation(
        operation: Operation,
        resource: String,
        parameters: List<IncomingParameter>,
        verb: String,
    ): FunSpec.Builder =
        apply {
            val annotation =
                HttpExchangeAnnotationBuilder(
                    resource,
                    parameters,
                    verb,
                    operation.getPrimaryContentMediaType()?.key,
                ).build()
            addAnnotation(annotation)
        }

    private fun FunSpec.Builder.addHttpExchangeAnnotation(
        resource: String,
        parameters: List<IncomingParameter>,
        verb: String,
        defaultAcceptContentType: String?,
        defaultRequestContentType: String?,
    ): FunSpec.Builder =
        apply {
            addAnnotation(
                HttpExchangeAnnotationBuilder(
                    resource,
                    parameters,
                    verb,
                    defaultAcceptContentType,
                    defaultRequestContentType,
                ).build(),
            )
        }

    private class HttpExchangeAnnotationBuilder(
        private val resource: String,
        private val parameters: List<IncomingParameter>,
        private val verb: String,
        private val defaultAcceptContentType: String?,
        private val defaultRequestContentType: String? = null,
    ) {
        fun build(): AnnotationSpec {
            val headerParams = parameters.getHeaderParameters()

            return SpringHttpInterfaceAnnotations
                .httpExchangeBuilder()
                .addUrl()
                .addMember("method=%S", verb.uppercase())
                .addContentType(headerParams)
                .addAccepts(headerParams)
                .addHeaders(headerParams)
                .build()
        }

        private fun AnnotationSpec.Builder.addUrl(): AnnotationSpec.Builder =
            apply {
                addMember("url=%S", resource)
            }

        private fun AnnotationSpec.Builder.addContentType(headerParams: List<RequestParameter>): AnnotationSpec.Builder =
            apply {
                val contentType =
                    headerParams
                        .filter { header ->
                            header.typeInfo is KotlinTypeInfo.Enum && header.typeInfo.entries.size == 1
                        }.singleOrNull { header ->
                            header.name == ClientGeneratorUtils.CONTENT_TYPE_HEADER_NAME
                        }

                val value = (contentType?.typeInfo as? KotlinTypeInfo.Enum)?.entries?.firstOrNull() ?: defaultRequestContentType
                if (value != null) {
                    addMember("contentType=%S", value)
                }
            }

        private fun AnnotationSpec.Builder.addAccepts(headerParams: List<RequestParameter>): AnnotationSpec.Builder =
            apply {
                val acceptHeaders =
                    headerParams
                        .filter { header ->
                            header.typeInfo is KotlinTypeInfo.Enum && header.typeInfo.entries.size == 1
                        }.filter { header ->
                            header.name == ClientGeneratorUtils.ACCEPT_HEADER_NAME
                        }.map { header ->
                            header.typeInfo as KotlinTypeInfo.Enum
                            buildCodeBlock {
                                add("%S", header.typeInfo.entries.first())
                            }
                        }.takeIf { it.isNotEmpty() }
                        ?: run {
                            // Add default accept header
                            val block =
                                defaultAcceptContentType?.let { mediaType ->
                                    buildCodeBlock {
                                        add("%S", mediaType)
                                    }
                                }
                            listOfNotNull(block)
                        }

                if (acceptHeaders.isNotEmpty()) {
                    addMember("accept=%L", acceptHeaders)
                }
            }

        private fun AnnotationSpec.Builder.addHeaders(headerParams: List<RequestParameter>): AnnotationSpec.Builder =
            apply {
                val headerValues =
                    headerParams
                        .filter { header ->
                            header.typeInfo is KotlinTypeInfo.Enum && header.typeInfo.entries.size == 1
                        }.filterNot { header ->
                            header.name == ClientGeneratorUtils.ACCEPT_HEADER_NAME
                        }.map { header ->
                            header.typeInfo as KotlinTypeInfo.Enum
                            buildCodeBlock {
                                add("%S=%S", header.originalName, header.typeInfo.entries.first())
                            }
                        }

                if (headerValues.isNotEmpty()) {
                    addMember("headers=%L", headerValues)
                }
            }

        private fun List<IncomingParameter>.getHeaderParameters(): List<RequestParameter> =
            filterIsInstance<RequestParameter>()
                .filter { it.parameterLocation is HeaderParam }

        private fun List<IncomingParameter>.getPathParameters(): List<RequestParameter> =
            filterIsInstance<RequestParameter>()
                .filter { it.parameterLocation is PathParam }
    }

    override fun generateLibrary(options: Set<ClientCodeGenOptionType>): Collection<GeneratedFile> = setOf()
}
