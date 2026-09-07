package com.cjbooms.fabrikt.generators.controller

import com.cjbooms.fabrikt.cli.ControllerCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.generators.GeneratorUtils.groupingStrategyFrom
import com.cjbooms.fabrikt.generators.GeneratorUtils.toIncomingParameters
import com.cjbooms.fabrikt.generators.GeneratorUtils.toKdoc
import com.cjbooms.fabrikt.generators.ValidationAnnotations
import com.cjbooms.fabrikt.generators.controller.ControllerGeneratorUtils.isSseResponse
import com.cjbooms.fabrikt.generators.controller.ControllerGeneratorUtils.methodName
import com.cjbooms.fabrikt.generators.controller.ControllerGeneratorUtils.securitySupport
import com.cjbooms.fabrikt.generators.controller.ControllerGeneratorUtils.toSuccessResponseType
import com.cjbooms.fabrikt.generators.controller.metadata.SpringAnnotations
import com.cjbooms.fabrikt.generators.controller.metadata.SpringImports
import com.cjbooms.fabrikt.model.BodyParameter
import com.cjbooms.fabrikt.model.ControllerLibraryType
import com.cjbooms.fabrikt.model.ControllerType
import com.cjbooms.fabrikt.model.CookieParam
import com.cjbooms.fabrikt.model.HeaderParam
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.KotlinTypes
import com.cjbooms.fabrikt.model.MultipartParameter
import com.cjbooms.fabrikt.model.PathParam
import com.cjbooms.fabrikt.model.QueryParam
import com.cjbooms.fabrikt.model.RequestParameter
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.util.FileUtils.addFileDisclaimer
import com.cjbooms.fabrikt.util.GroupingStrategy
import com.cjbooms.fabrikt.util.KaizenParserExtensions.groupedPaths
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isSingleResource
import com.cjbooms.fabrikt.util.toUpperCase
import com.reprezen.kaizen.oasparser.model3.Operation
import com.reprezen.kaizen.oasparser.model3.Path
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName

class SpringControllerInterfaceGenerator(
    private val packages: Packages,
    private val api: SourceApi,
    private val validationAnnotations: ValidationAnnotations,
    private val options: Set<ControllerCodeGenOptionType> = emptySet(),
) : AnnotationBasedControllerInterfaceGenerator(packages, api, validationAnnotations),
    ControllerInterfaceGenerator {
    companion object {
        private const val EXTENSION_ASYNC_SUPPORT = "x-async-support"
    }

    private val addAuthenticationParameter: Boolean
        get() = options.any { it == ControllerCodeGenOptionType.AUTHENTICATION }
    private val groupingStrategy: GroupingStrategy
        get() = groupingStrategyFrom(options)

    private var generatorContext: GeneratorEndpointContext? = null

    internal constructor(
        packages: Packages,
        api: SourceApi,
        validationAnnotations: ValidationAnnotations,
        options: Set<ControllerCodeGenOptionType>,
        generatorContext: GeneratorEndpointContext,
    ) : this(packages, api, validationAnnotations, options) {
        this.generatorContext = generatorContext
    }

    override fun generate(): SpringControllers =
        SpringControllers(
            generatorContext?.let(::generateControllers)
                ?: api.openApi3
                    .groupedPaths(groupingStrategy)
                    .map { (resourceName, paths) ->
                        buildController(resourceName, paths.values)
                    }.toSet(),
        )

    private fun generateControllers(context: GeneratorEndpointContext): Set<ControllerType> =
        context
            .groupedPaths(groupingStrategy)
            .map { (resourceName, paths) ->
                val builder = controllerBuilder(ControllerGeneratorUtils.controllerName(resourceName), context.operations.basePath)
                paths
                    .flatMap { path ->
                        path.operations
                            .filterNot { it.method.equals("HEAD", ignoreCase = true) }
                            .map { operation -> buildFunction(context, path, operation) }
                    }.forEach(builder::addFunction)
                ControllerType(builder.build(), packages.base)
            }.toSet()

    override fun generateLibrary(): Collection<ControllerLibraryType> = emptySet()

    override fun controllerBuilder(
        className: String,
        basePath: String,
    ) = TypeSpec
        .interfaceBuilder(className)
        .addAnnotation(SpringAnnotations.CONTROLLER)
        .addAnnotation(SpringAnnotations.VALIDATED)
        .addAnnotation(SpringAnnotations.requestMappingBuilder().addMember("%S", basePath).build())

    override fun buildFunction(
        path: Path,
        op: Operation,
        verb: String,
    ): FunSpec {
        val methodName = methodName(op, verb, path.pathString.isSingleResource())
        val returnType = op.toSuccessResponseType(packages.base)
        val parameters = op.toIncomingParameters(packages.base, path.parameters, emptyList())
        val globalSecurity = api.openApi3.securityRequirements.securitySupport()

        // Main method builder
        val baseFunSpec =
            FunSpec
                .builder(methodName)
                .addModifiers(KModifier.ABSTRACT)
                .addKdoc(op.toKdoc(parameters))
                .addSpringFunAnnotation(op, verb, path.pathString)
                .addSuspendModifier()

        val explicitAsyncSupport = op.extensions[EXTENSION_ASYNC_SUPPORT] as? Boolean
        val asyncSupport = explicitAsyncSupport ?: options.contains(ControllerCodeGenOptionType.COMPLETION_STAGE)
        val springSseSupport = options.contains(ControllerCodeGenOptionType.SSE_EMITTER)

        val funcSpec =
            when {
                springSseSupport && op.isSseResponse() -> baseFunSpec.returns(SpringImports.SSE_EMITTER)
                asyncSupport ->
                    baseFunSpec.returns(
                        SpringImports.COMPLETION_STAGE.parameterizedBy(
                            SpringImports.RESPONSE_ENTITY.parameterizedBy(returnType),
                        ),
                    )

                else -> baseFunSpec.returns(SpringImports.RESPONSE_ENTITY.parameterizedBy(returnType))
            }

        parameters
            .map {
                when (it) {
                    is MultipartParameter ->
                        toParameterSpecBuilder(it)
                            .addSpringParamAnnotation(it)
                            .maybeAddAnnotation(validationAnnotations.parameterValid())
                            .build()

                    is BodyParameter ->
                        it
                            .toParameterSpecBuilder()
                            .addAnnotation(SpringAnnotations.requestBodyBuilder().build())
                            .maybeAddAnnotation(validationAnnotations.parameterValid())
                            .build()

                    is RequestParameter ->
                        it
                            .toParameterSpecBuilder()
                            .addValidationAnnotations(it)
                            .addSpringParamAnnotation(it)
                            .build()
                }
            }.forEach { funcSpec.addParameter(it) }

        // Add authentication
        if (addAuthenticationParameter) {
            val securityOption = op.securitySupport(globalSecurity)

            if (securityOption.allowsAuthenticated) {
                val typeName =
                    SpringImports.AUTHENTICATION
                        .copy(nullable = securityOption == ControllerGeneratorUtils.SecuritySupport.AUTHENTICATION_OPTIONAL)
                funcSpec.addParameter(
                    ParameterSpec
                        .builder("authentication", typeName)
                        .build(),
                )
            }
        }

        return funcSpec.build()
    }

    private fun buildFunction(
        context: GeneratorEndpointContext,
        path: GeneratorPathItem,
        operation: GeneratorOperation,
    ): FunSpec {
        val returnType = context.successResponseType(operation, packages.base)
        val parameters = context.incomingParameters(operation, path.parameters)
        val globalSecurity = context.operations.security.securitySupport()
        val baseFunSpec =
            FunSpec
                .builder(context.methodName(operation, path.path))
                .addModifiers(KModifier.ABSTRACT)
                .addKdoc(context.toKdoc(operation, parameters))
                .addSpringFunAnnotation(operation, path.path)
                .addSuspendModifier()

        val explicitAsyncSupport = operation.extensions[EXTENSION_ASYNC_SUPPORT]?.takeIf { it.isBoolean }?.booleanValue()
        val asyncSupport = explicitAsyncSupport ?: options.contains(ControllerCodeGenOptionType.COMPLETION_STAGE)
        val funcSpec =
            when {
                options.contains(ControllerCodeGenOptionType.SSE_EMITTER) && context.isSseResponse(operation) ->
                    baseFunSpec.returns(SpringImports.SSE_EMITTER)
                asyncSupport ->
                    baseFunSpec.returns(
                        SpringImports.COMPLETION_STAGE.parameterizedBy(SpringImports.RESPONSE_ENTITY.parameterizedBy(returnType)),
                    )
                else -> baseFunSpec.returns(SpringImports.RESPONSE_ENTITY.parameterizedBy(returnType))
            }

        parameters
            .map {
                when (it) {
                    is MultipartParameter ->
                        toParameterSpecBuilder(it)
                            .addSpringParamAnnotation(it)
                            .maybeAddAnnotation(validationAnnotations.parameterValid())
                            .build()
                    is BodyParameter ->
                        it
                            .toParameterSpecBuilder()
                            .addAnnotation(SpringAnnotations.requestBodyBuilder().build())
                            .maybeAddAnnotation(validationAnnotations.parameterValid())
                            .build()
                    is RequestParameter ->
                        it
                            .toParameterSpecBuilder()
                            .addValidationAnnotations(it)
                            .addSpringParamAnnotation(it)
                            .build()
                }
            }.forEach(funcSpec::addParameter)

        if (addAuthenticationParameter) {
            val security = operation.securitySupport(globalSecurity)
            if (security.allowsAuthenticated) {
                funcSpec.addParameter(
                    ParameterSpec
                        .builder(
                            "authentication",
                            SpringImports.AUTHENTICATION.copy(
                                nullable =
                                    security == ControllerGeneratorUtils.SecuritySupport.AUTHENTICATION_OPTIONAL,
                            ),
                        ).build(),
                )
            }
        }
        return funcSpec.build()
    }

    private val springMultipartFileType = ClassName.bestGuess("org.springframework.web.multipart.MultipartFile")
    private val springMultipartFileTypeList = List::class.asClassName().parameterizedBy(springMultipartFileType)

    private fun toParameterSpecBuilder(parameter: MultipartParameter): ParameterSpec.Builder =
        ParameterSpec.builder(
            name = parameter.name,
            type =
                when {
                    parameter.isBinaryFile && parameter.isArray -> springMultipartFileTypeList
                    parameter.isBinaryFile -> springMultipartFileType
                    else -> parameter.type
                }.copy(nullable = !parameter.isRequired),
        )

    private fun FunSpec.Builder.addSpringFunAnnotation(
        op: Operation,
        verb: String,
        path: String,
    ): FunSpec.Builder {
        val produces =
            op.responses
                .flatMap { it.value.contentMediaTypes.keys }
                .distinct()
                .toTypedArray()

        val consumes =
            op.requestBody
                .contentMediaTypes.keys
                .toTypedArray()

        val funcAnnotation =
            SpringAnnotations
                .requestMappingBuilder()
                .addMember("value = [%S]", path)
                .addMember(
                    "produces = %L",
                    produces.joinToString(prefix = "[", postfix = "]", separator = ", ", transform = { "\"$it\"" }),
                ).addMember("method = [RequestMethod.%L]", verb.toUpperCase())

        if (consumes.isNotEmpty()) {
            funcAnnotation.addMember(
                "consumes = %L",
                consumes.joinToString(prefix = "[", postfix = "]", separator = ", ", transform = { "\"$it\"" }),
            )
        }

        this.addAnnotation(funcAnnotation.build())
        return this
    }

    private fun FunSpec.Builder.addSpringFunAnnotation(
        operation: GeneratorOperation,
        path: String,
    ): FunSpec.Builder {
        val produces =
            operation.responses
                .flatMap { it.content }
                .map { it.key }
                .distinct()
                .toTypedArray()
        val consumes =
            operation.requestBody
                ?.content
                ?.map { it.key }
                .orEmpty()
                .toTypedArray()
        val annotation =
            SpringAnnotations
                .requestMappingBuilder()
                .addMember("value = [%S]", path)
                .addMember(
                    "produces = %L",
                    produces.joinToString(prefix = "[", postfix = "]", separator = ", ", transform = { "\"$it\"" }),
                ).addMember("method = [RequestMethod.%L]", operation.method.toUpperCase())
        if (consumes.isNotEmpty()) {
            annotation.addMember(
                "consumes = %L",
                consumes.joinToString(prefix = "[", postfix = "]", separator = ", ", transform = { "\"$it\"" }),
            )
        }
        return addAnnotation(annotation.build())
    }

    private fun ParameterSpec.Builder.addSpringParamAnnotation(parameter: RequestParameter): ParameterSpec.Builder =
        when (parameter.parameterLocation) {
            QueryParam -> SpringAnnotations.requestParamBuilder()
            HeaderParam -> SpringAnnotations.requestHeaderBuilder()
            PathParam -> SpringAnnotations.requestPathVariableBuilder()
            CookieParam -> SpringAnnotations.cookieValueBuilder()
        }.let {
            it.addMember("value = %S", parameter.oasName)
            it.addMember("required = %L", parameter.isRequired)

            if (parameter.defaultValue != null) {
                it.addMember("defaultValue = %S", parameter.defaultValue)
            }

            if (parameter.typeInfo is KotlinTypeInfo.Date) {
                this.addAnnotation(SpringAnnotations.dateTimeFormat(SpringImports.DateTimeFormat.ISO_DATE))
            } else if (parameter.typeInfo is KotlinTypeInfo.DateTime) {
                this.addAnnotation(SpringAnnotations.dateTimeFormat(SpringImports.DateTimeFormat.ISO_DATE_TIME))
            }

            this.addAnnotation(it.build())
        }

    private fun ParameterSpec.Builder.addSpringParamAnnotation(parameter: MultipartParameter): ParameterSpec.Builder =
        when {
            parameter.isBinaryFile -> SpringAnnotations.requestPartBuilder()
            parameter.contentType == "application/json" -> SpringAnnotations.requestPartBuilder()
            else -> SpringAnnotations.requestParamBuilder()
        }.let {
            it.addMember("value = %S", parameter.oasName)
            it.addMember("required = %L", parameter.isRequired)
            this.addAnnotation(it.build())
        }

    private fun FunSpec.Builder.addSuspendModifier(): FunSpec.Builder {
        if (options.any { it == ControllerCodeGenOptionType.SUSPEND_MODIFIER }) {
            this.addModifiers(KModifier.SUSPEND)
        }
        return this
    }
}

data class SpringControllers(
    val controllers: Collection<ControllerType>,
) : KotlinTypes(controllers) {
    override val files: Collection<FileSpec> =
        super.files.map {
            it
                .toBuilder()
                .addFileDisclaimer()
                .addImport(SpringImports.Static.REQUEST_METHOD.first, SpringImports.Static.REQUEST_METHOD.second)
                .addImport(
                    SpringImports.Static.RESPONSE_STATUS.first,
                    SpringImports.Static.RESPONSE_STATUS.second,
                ).build()
        }
}
