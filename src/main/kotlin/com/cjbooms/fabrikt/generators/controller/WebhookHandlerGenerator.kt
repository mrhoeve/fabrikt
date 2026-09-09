package com.cjbooms.fabrikt.generators.controller

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.parser.GeneratorOperation
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec

internal class WebhookHandlerGenerator(
    private val packages: Packages,
    private val context: GeneratorEndpointContext,
    private val suspending: Boolean,
) {
    fun generate(): List<FileSpec> = context.operations.webhooks.map(::handler)

    private fun handler(webhook: GeneratorPathItem): FileSpec {
        val name = "${webhook.path.toModelClassName()}WebhookHandler"
        val type = TypeSpec.interfaceBuilder(name)
        webhook.operations.map { operation -> function(webhook, operation) }.forEach(type::addFunction)
        return FileSpec.builder(packages.controllers, name).addType(type.build()).build()
    }

    private fun function(
        webhook: GeneratorPathItem,
        operation: GeneratorOperation,
    ): FunSpec {
        val parameters = context.incomingParameters(operation, webhook.parameters)
        return FunSpec
            .builder(context.methodName(operation, webhook.path))
            .addModifiers(KModifier.ABSTRACT)
            .apply { if (suspending) addModifiers(KModifier.SUSPEND) }
            .addKdoc(context.toKdoc(operation, parameters))
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
}
