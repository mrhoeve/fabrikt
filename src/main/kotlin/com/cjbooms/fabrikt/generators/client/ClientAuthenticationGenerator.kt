package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.ClientAuthenticationType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import java.util.Base64

internal class ClientAuthenticationGenerator(
    private val packages: Packages,
) {
    fun generate(types: Set<ClientAuthenticationType>): List<FileSpec> =
        listOfNotNull(
            basicCredentials().takeIf { ClientAuthenticationType.BASIC in types },
            bearerToken().takeIf { ClientAuthenticationType.BEARER in types },
        )

    private fun basicCredentials(): FileSpec =
        FileSpec
            .builder(packages.client, "BasicCredentials")
            .addType(
                TypeSpec
                    .classBuilder("BasicCredentials")
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec
                            .constructorBuilder()
                            .addParameter("username", String::class)
                            .addParameter("password", String::class)
                            .build(),
                    ).addProperty(PropertySpec.builder("username", String::class).initializer("username").build())
                    .addProperty(PropertySpec.builder("password", String::class).initializer("password").build())
                    .addFunction(
                        FunSpec
                            .builder("toString")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(String::class)
                            .addStatement(
                                "return %S + %T.getEncoder().encodeToString(%P.toByteArray(Charsets.UTF_8))",
                                "Basic ",
                                Base64::class,
                                "${'$'}username:${'$'}password",
                            ).build(),
                    ).build(),
            ).build()

    private fun bearerToken(): FileSpec =
        FileSpec
            .builder(packages.client, "BearerToken")
            .addType(
                TypeSpec
                    .classBuilder("BearerToken")
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder().addParameter("value", String::class).build(),
                    ).addProperty(PropertySpec.builder("value", String::class).initializer("value").build())
                    .addFunction(
                        FunSpec
                            .builder("toString")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(String::class)
                            .addStatement("return %S + value", "Bearer ")
                            .build(),
                    ).build(),
            ).build()
}
