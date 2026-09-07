package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.cli.CodeGenerationType.CLIENT
import com.cjbooms.fabrikt.cli.CodeGenerationType.CONTROLLERS
import com.cjbooms.fabrikt.cli.CodeGenerationType.HTTP_MODELS
import com.cjbooms.fabrikt.cli.CodeGenerationType.QUARKUS_REFLECTION_CONFIG
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.generators.client.OkHttpClientGenerator
import com.cjbooms.fabrikt.generators.client.OpenFeignInterfaceGenerator
import com.cjbooms.fabrikt.generators.client.SpringHttpInterfaceGenerator
import com.cjbooms.fabrikt.generators.controller.KtorClientGenerator
import com.cjbooms.fabrikt.generators.controller.KtorControllerInterfaceGenerator
import com.cjbooms.fabrikt.generators.controller.MicronautControllerInterfaceGenerator
import com.cjbooms.fabrikt.generators.controller.NativeKtorClientGenerator
import com.cjbooms.fabrikt.generators.controller.SpringControllerInterfaceGenerator
import com.cjbooms.fabrikt.generators.model.ModelGenerator
import com.cjbooms.fabrikt.generators.model.NativeModelGenerator
import com.cjbooms.fabrikt.generators.model.QuarkusReflectionModelGenerator
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.model.ResourceFile
import com.cjbooms.fabrikt.model.ResourceSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorOperationDocument
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import com.squareup.kotlinpoet.FileSpec
import java.nio.file.Path

class CodeGenerator internal constructor(
    private val packages: Packages,
    private val sourceApi: SourceApi,
    private val srcPath: Path,
    private val resourcesPath: Path,
    private val schemaGenerationMode: SchemaGenerationMode,
) {
    constructor(
        packages: Packages,
        sourceApi: SourceApi,
        srcPath: Path,
        resourcesPath: Path,
    ) : this(packages, sourceApi, srcPath, resourcesPath, SchemaGenerationMode.LEGACY)

    fun generate(): Collection<GeneratedFile> = MutableSettings.generationTypes.map(::generateCode).flatten()

    private fun generateCode(generationType: CodeGenerationType): Collection<GeneratedFile> =
        when (generationType) {
            CLIENT -> generateClient()
            CONTROLLERS -> generateControllerInterfaces()
            HTTP_MODELS -> generateModels()
            QUARKUS_REFLECTION_CONFIG -> generateQuarkusReflectionResource()
        }

    private fun generateModels(): Collection<GeneratedFile> = sourceSet(models().files)

    private fun generateControllerInterfaces(): Collection<GeneratedFile> = sourceSet(controllers()).plus(sourceSet(models().files))

    private fun generateClient(): Collection<GeneratedFile> {
        val endpointContext = nativeEndpointContext()
        val clientGenerator =
            when (MutableSettings.clientTarget) {
                ClientCodeGenTargetType.OK_HTTP ->
                    endpointContext?.let { OkHttpClientGenerator(packages, sourceApi, srcPath, it) }
                        ?: OkHttpClientGenerator(packages, sourceApi, srcPath)
                ClientCodeGenTargetType.OPEN_FEIGN ->
                    endpointContext?.let { OpenFeignInterfaceGenerator(packages, sourceApi, it) }
                        ?: OpenFeignInterfaceGenerator(packages, sourceApi)
                ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                    endpointContext?.let { SpringHttpInterfaceGenerator(packages, sourceApi, it) }
                        ?: SpringHttpInterfaceGenerator(packages, sourceApi)
                ClientCodeGenTargetType.KTOR ->
                    endpointContext?.let { NativeKtorClientGenerator(packages, it) }
                        ?: KtorClientGenerator(packages, sourceApi)
            }
        val options = MutableSettings.clientOptions
        val clientFiles = clientGenerator.generate(options).files
        val libFiles = clientGenerator.generateLibrary(options)
        return sourceSet(clientFiles).plus(libFiles).plus(sourceSet(models().files))
    }

    private fun generateQuarkusReflectionResource(): Collection<GeneratedFile> = resourceSet(resources(models()))

    private fun sourceSet(fileSpec: Collection<FileSpec>) = setOf(KotlinSourceSet(fileSpec, srcPath))

    private fun resourceSet(resFiles: Collection<ResourceFile>) = setOf(ResourceSourceSet(resFiles, resourcesPath))

    private fun models(): Models =
        when (schemaGenerationMode) {
            SchemaGenerationMode.LEGACY -> ModelGenerator(packages, sourceApi).generate()
            SchemaGenerationMode.NATIVE ->
                NativeModelGenerator(packages.base).generate(
                    GeneratorModelDescriptorBuilder.build(
                        sourceApi.parsedDocument.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                    ),
                )
        }

    private fun resources(models: Models): List<ResourceFile> = listOfNotNull(QuarkusReflectionModelGenerator(models).generate())

    private fun controllers(): List<FileSpec> {
        val endpointContext = nativeEndpointContext()
        val generator =
            when (MutableSettings.controllerTarget) {
                ControllerCodeGenTargetType.SPRING ->
                    endpointContext?.let {
                        SpringControllerInterfaceGenerator(
                            packages,
                            sourceApi,
                            MutableSettings.validationLibrary.annotations,
                            MutableSettings.controllerOptions,
                            it,
                        )
                    } ?: SpringControllerInterfaceGenerator(
                        packages,
                        sourceApi,
                        MutableSettings.validationLibrary.annotations,
                        MutableSettings.controllerOptions,
                    )

                ControllerCodeGenTargetType.MICRONAUT ->
                    endpointContext?.let {
                        MicronautControllerInterfaceGenerator(
                            packages,
                            sourceApi,
                            MutableSettings.validationLibrary.annotations,
                            MutableSettings.controllerOptions,
                            it,
                        )
                    } ?: MicronautControllerInterfaceGenerator(
                        packages,
                        sourceApi,
                        MutableSettings.validationLibrary.annotations,
                        MutableSettings.controllerOptions,
                    )

                ControllerCodeGenTargetType.KTOR ->
                    endpointContext?.let {
                        KtorControllerInterfaceGenerator(packages, sourceApi, MutableSettings.controllerOptions, it)
                    } ?: KtorControllerInterfaceGenerator(packages, sourceApi, MutableSettings.controllerOptions)
            }

        val controllerFiles: Collection<FileSpec> = generator.generate().files
        val libFiles: Collection<FileSpec> =
            generator.generateLibrary().map {
                FileSpec
                    .builder(it.className)
                    .addType(it.spec)
                    .build()
            }

        return controllerFiles.plus(libFiles)
    }

    private fun nativeEndpointContext(): GeneratorEndpointContext? =
        schemaGenerationMode.takeIf { it == SchemaGenerationMode.NATIVE }?.let {
            GeneratorEndpointContext(
                sourceApi.parsedDocument.toGeneratorOperationDocument(it),
                sourceApi.parsedDocument.toGeneratorSchemaDocument(it),
                packages.base,
            )
        }
}
