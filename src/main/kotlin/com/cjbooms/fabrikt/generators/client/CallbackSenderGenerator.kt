package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.parser.GeneratorCallback
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec

internal class CallbackSenderGenerator(
    private val packages: Packages,
    private val context: GeneratorEndpointContext,
    private val suspending: Boolean,
) {
    fun generate(): List<FileSpec> =
        context.operations.paths
            .flatMap { path -> path.operations.flatMap { operation -> operation.callbacks.map { CallbackSource(path, operation, it) } } }
            .groupBy { it.callback.name }
            .values
            .flatMap { sources ->
                val distinct = sources.distinctBy(CallbackSource::callback)
                distinct.map { source ->
                    val prefix =
                        if (distinct.size == 1) {
                            ""
                        } else {
                            (source.operation.operationId ?: "${source.operation.method} ${source.path.path}").toModelClassName()
                        }
                    sender(prefix, source.callback)
                }
            }

    private fun sender(
        prefix: String,
        callback: GeneratorCallback,
    ): FileSpec {
        val name = "$prefix${callback.name.toModelClassName().removeSuffix("Callback")}CallbackSender"
        val type = TypeSpec.interfaceBuilder(name)
        callback.pathItems
            .flatMap { path -> path.operations.map { operation -> function(path, operation) } }
            .forEach(type::addFunction)
        return FileSpec.builder(packages.client, name).addType(type.build()).build()
    }

    private fun function(
        path: GeneratorPathItem,
        operation: GeneratorOperation,
    ): FunSpec {
        val parameters = context.incomingParameters(operation, path.parameters)
        val destinationName = allocateDestinationName(parameters.map { it.name }.toSet())
        return FunSpec
            .builder(context.methodName(operation, path.path))
            .addModifiers(KModifier.ABSTRACT)
            .apply { if (suspending) addModifiers(KModifier.SUSPEND) }
            .addKdoc("Sends this callback to the URL obtained by evaluating `%L`.\n", path.path)
            .addParameter(ParameterSpec.builder(destinationName, String::class).build())
            .apply {
                parameters.forEach { parameter ->
                    addParameter(
                        parameter
                            .toParameterSpecBuilder()
                            .apply { if (parameter.isNullable) defaultValue("null") }
                            .build(),
                    )
                }
            }.returns(context.successResponseType(operation, packages.base))
            .build()
    }

    private fun allocateDestinationName(allocated: Set<String>): String {
        var candidate = "callbackUrl"
        var suffix = 2
        while (candidate in allocated) candidate = "callbackUrl${suffix++}"
        return candidate
    }

    private data class CallbackSource(
        val path: GeneratorPathItem,
        val operation: GeneratorOperation,
        val callback: GeneratorCallback,
    )
}
