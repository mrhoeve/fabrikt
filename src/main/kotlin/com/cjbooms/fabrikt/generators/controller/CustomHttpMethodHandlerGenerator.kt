package com.cjbooms.fabrikt.generators.controller

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec

internal class CustomHttpMethodHandlerGenerator(
    private val packages: Packages,
    private val context: GeneratorEndpointContext,
) {
    fun generate(): FileSpec? {
        val operations =
            context.operations.paths.flatMap { path ->
                path.operations
                    .filterNot { it.method.uppercase() in STANDARD_HTTP_METHODS }
                    .map { operation -> path to operation }
            }
        if (operations.isEmpty()) return null
        val type = TypeSpec.interfaceBuilder("CustomHttpMethodHandler")
        operations.map { (path, operation) -> function(path, operation) }.forEach(type::addFunction)
        return FileSpec.builder(packages.controllers, "CustomHttpMethodHandler").addType(type.build()).build()
    }

    private fun function(
        path: GeneratorPathItem,
        operation: GeneratorOperation,
    ): FunSpec {
        val parameters = context.incomingParameters(operation, path.parameters)
        return FunSpec
            .builder(context.methodName(operation, path.path))
            .addModifiers(KModifier.ABSTRACT)
            .addKdoc("Handles the `%L %L` operation using application-defined routing.\n", operation.method.uppercase(), path.path)
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

    private companion object {
        val STANDARD_HTTP_METHODS = setOf("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE")
    }
}
