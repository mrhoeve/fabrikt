package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.parser.GeneratorOperationDocument
import com.cjbooms.fabrikt.parser.GeneratorPathItem
import com.cjbooms.fabrikt.parser.GeneratorServer
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec

internal class ClientServerGenerator(
    private val packageName: String,
) {
    fun generate(document: GeneratorOperationDocument): List<FileSpec> {
        val catalog = ServerCatalog.create(document)
        if (catalog.servers.isEmpty()) return emptyList()

        val serverVariableType = ClassName(packageName, "ApiServerVariable")
        val serverType = ClassName(packageName, "ApiServer")
        return listOf(
            FileSpec
                .builder(packageName, "ApiServers")
                .addType(serverVariableType(serverVariableType))
                .addType(serverType(serverType, serverVariableType))
                .addType(serverCatalogType(catalog, serverType, serverVariableType))
                .build(),
        )
    }

    private fun serverVariableType(type: ClassName): TypeSpec {
        val allowedValuesType = SET.parameterizedBy(STRING)
        val constructor =
            FunSpec
                .constructorBuilder()
                .addParameter("defaultValue", STRING.copy(nullable = true))
                .addParameter(
                    ParameterSpec
                        .builder("allowedValues", allowedValuesType)
                        .defaultValue("emptySet()")
                        .build(),
                ).addParameter(
                    ParameterSpec
                        .builder("description", STRING.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                ).build()
        return TypeSpec
            .classBuilder(type)
            .addKdoc("Describes a variable used by an OpenAPI server URL template.\n")
            .addModifiers(KModifier.DATA)
            .primaryConstructor(constructor)
            .addProperty(PropertySpec.builder("defaultValue", STRING.copy(nullable = true)).initializer("defaultValue").build())
            .addProperty(PropertySpec.builder("allowedValues", allowedValuesType).initializer("allowedValues").build())
            .addProperty(PropertySpec.builder("description", STRING.copy(nullable = true)).initializer("description").build())
            .build()
    }

    private fun serverType(
        type: ClassName,
        variableType: ClassName,
    ): TypeSpec {
        val variablesType = MAP.parameterizedBy(STRING, variableType)
        val constructor =
            FunSpec
                .constructorBuilder()
                .addParameter("urlTemplate", STRING)
                .addParameter(
                    ParameterSpec
                        .builder("description", STRING.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                ).addParameter(
                    ParameterSpec
                        .builder("name", STRING.copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                ).addParameter(
                    ParameterSpec
                        .builder("variables", variablesType)
                        .defaultValue("emptyMap()")
                        .build(),
                ).build()
        return TypeSpec
            .classBuilder(type)
            .addKdoc("Describes an OpenAPI server and resolves its URL template.\n")
            .addModifiers(KModifier.DATA)
            .primaryConstructor(constructor)
            .addProperty(PropertySpec.builder("urlTemplate", STRING).initializer("urlTemplate").build())
            .addProperty(PropertySpec.builder("description", STRING.copy(nullable = true)).initializer("description").build())
            .addProperty(PropertySpec.builder("name", STRING.copy(nullable = true)).initializer("name").build())
            .addProperty(PropertySpec.builder("variables", variablesType).initializer("variables").build())
            .addFunction(resolveFunction())
            .build()
    }

    private fun resolveFunction(): FunSpec {
        val overridesType = MAP.parameterizedBy(STRING, STRING)
        return FunSpec
            .builder("resolve")
            .addKdoc("Resolves the URL template using declared defaults and the supplied [overrides].\n")
            .addParameter(
                ParameterSpec
                    .builder("overrides", overridesType)
                    .defaultValue("emptyMap()")
                    .build(),
            ).returns(STRING)
            .beginControlFlow("require(overrides.keys.all(variables::containsKey))")
            .addStatement("%P", "Unknown server variables: \${overrides.keys - variables.keys}")
            .endControlFlow()
            .beginControlFlow("variables.forEach { (variableName, variable) ->")
            .addStatement("val value = overrides[variableName] ?: variable.defaultValue ?: return@forEach")
            .beginControlFlow("require(variable.allowedValues.isEmpty() || value in variable.allowedValues)")
            .addStatement(
                "%P",
                "Invalid value '\$value' for server variable '\$variableName': expected one of \${variable.allowedValues}",
            ).endControlFlow()
            .endControlFlow()
            .addStatement(
                "val values = variables.mapValues { (variableName, variable) -> " +
                    "overrides[variableName] ?: variable.defaultValue ?: \"{\$variableName}\" } + overrides",
            ).addStatement(
                "return values.entries.fold(urlTemplate) { url, (variableName, value) -> " +
                    "url.replace(\"{\$variableName}\", value) }",
            ).build()
    }

    private fun serverCatalogType(
        catalog: ServerCatalog,
        serverType: ClassName,
        variableType: ClassName,
    ): TypeSpec {
        val serverListType = LIST.parameterizedBy(serverType)
        val operationMapType = MAP.parameterizedBy(STRING, serverListType)
        return TypeSpec
            .objectBuilder("ApiServers")
            .addKdoc("Provides every server declared by the API and the effective servers for each operation.\n")
            .addProperty(
                PropertySpec
                    .builder("all", serverListType)
                    .addKdoc("All distinct servers in declaration order.\n")
                    .initializer(serverList(catalog.servers, serverType, variableType))
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder("defaultServer", serverType.copy(nullable = true))
                    .addKdoc("The first root server, when the API declares one.\n")
                    .initializer(catalog.defaultServerIndex?.let { CodeBlock.of("all[%L]", it) } ?: CodeBlock.of("null"))
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder("byOperation", operationMapType)
                    .addKdoc("Effective servers keyed by operationId, or by HTTP method and path when no operationId exists.\n")
                    .initializer(operationMap(catalog))
                    .build(),
            ).build()
    }

    private fun serverList(
        servers: List<GeneratorServer>,
        serverType: ClassName,
        variableType: ClassName,
    ): CodeBlock =
        CodeBlock
            .builder()
            .add("listOf(\n")
            .indent()
            .apply { servers.forEach { server -> add("%L,\n", serverInitializer(server, serverType, variableType)) } }
            .unindent()
            .add(")")
            .build()

    private fun serverInitializer(
        server: GeneratorServer,
        serverType: ClassName,
        variableType: ClassName,
    ): CodeBlock =
        CodeBlock.of(
            "%T(urlTemplate = %S, description = %S, name = %S, variables = %L)",
            serverType,
            server.url.orEmpty(),
            server.description,
            server.name,
            variableMap(server, variableType),
        )

    private fun variableMap(
        server: GeneratorServer,
        variableType: ClassName,
    ): CodeBlock {
        if (server.variables.isEmpty()) return CodeBlock.of("emptyMap()")
        return CodeBlock
            .builder()
            .add("mapOf(\n")
            .indent()
            .apply {
                server.variables.forEach { (name, variable) ->
                    add(
                        "%S to %T(defaultValue = %S, allowedValues = %L, description = %S),\n",
                        name,
                        variableType,
                        variable.defaultValue,
                        stringSet(variable.enumValues),
                        variable.description,
                    )
                }
            }.unindent()
            .add(")")
            .build()
    }

    private fun stringSet(values: List<String>): CodeBlock =
        if (values.isEmpty()) {
            CodeBlock.of("emptySet()")
        } else {
            CodeBlock
                .builder()
                .add("setOf(")
                .apply { values.forEachIndexed { index, value -> add(if (index == 0) "%S" else ", %S", value) } }
                .add(")")
                .build()
        }

    private fun operationMap(catalog: ServerCatalog): CodeBlock {
        if (catalog.operationServerIndexes.isEmpty()) return CodeBlock.of("emptyMap()")
        return CodeBlock
            .builder()
            .add("mapOf(\n")
            .indent()
            .apply {
                catalog.operationServerIndexes.forEach { (operation, indexes) ->
                    add("%S to listOf(", operation)
                    indexes.forEachIndexed { index, serverIndex -> add(if (index == 0) "all[%L]" else ", all[%L]", serverIndex) }
                    add("),\n")
                }
            }.unindent()
            .add(")")
            .build()
    }

    private data class ServerCatalog(
        val servers: List<GeneratorServer>,
        val defaultServerIndex: Int?,
        val operationServerIndexes: Map<String, List<Int>>,
    ) {
        companion object {
            fun create(document: GeneratorOperationDocument): ServerCatalog {
                val servers = linkedSetOf<GeneratorServer>()
                servers.addAll(document.servers)
                val operations = linkedMapOf<String, List<GeneratorServer>>()
                (document.paths + document.webhooks).forEach { path -> collect(path, servers, operations) }
                val all = servers.toList()
                val indexes = all.withIndex().associate { (index, server) -> server to index }
                val byOperation =
                    operations
                        .mapValues { (_, operationServers) -> operationServers.mapNotNull(indexes::get) }
                        .filterValues(List<Int>::isNotEmpty)
                return ServerCatalog(all, document.servers.firstOrNull()?.let(indexes::get), byOperation)
            }

            private fun collect(
                path: GeneratorPathItem,
                servers: MutableSet<GeneratorServer>,
                operations: MutableMap<String, List<GeneratorServer>>,
                parent: String? = null,
            ) {
                servers.addAll(path.servers)
                path.operations.forEach { operation ->
                    servers.addAll(operation.servers)
                    val fallback = "${operation.method.uppercase()} ${path.path}"
                    val key = operation.operationId ?: listOfNotNull(parent, fallback).joinToString(" > ")
                    if (operation.servers.isNotEmpty()) operations[key] = operation.servers
                    operation.callbacks.forEach { callback ->
                        callback.pathItems.forEach { callbackPath ->
                            collect(callbackPath, servers, operations, "$key > ${callback.name}")
                        }
                    }
                }
            }
        }
    }
}
