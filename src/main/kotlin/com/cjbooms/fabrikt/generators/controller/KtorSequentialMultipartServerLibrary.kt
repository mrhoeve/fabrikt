package com.cjbooms.fabrikt.generators.controller

import com.cjbooms.fabrikt.configurations.Packages
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName

internal object KtorSequentialMultipartServerLibrary {
    fun type(packages: Packages): TypeSpec {
        val multipart = ClassName(packages.controllers, "SequentialMultipart")
        val part = multipart.nestedClass("Part")
        val encoding = multipart.nestedClass("Encoding")
        val byteReadChannel = ClassName("io.ktor.utils.io", "ByteReadChannel")
        val flow = ClassName("kotlinx.coroutines.flow", "Flow")
        val partFlow = flow.parameterizedBy(part)
        val stringList = List::class.asClassName().parameterizedBy(String::class.asTypeName())
        val headers = Map::class.asClassName().parameterizedBy(String::class.asTypeName(), stringList)
        val fixedHeaders = Map::class.asClassName().parameterizedBy(String::class.asTypeName(), String::class.asTypeName())
        val encodings = List::class.asClassName().parameterizedBy(encoding)

        return TypeSpec
            .objectBuilder(multipart)
            .addType(partType(part, byteReadChannel, partFlow, headers))
            .addType(encodingType(encoding, stringList, fixedHeaders, encodings))
            .addFunction(readFunction(part, encoding, byteReadChannel, partFlow))
            .addFunction(headerFunction(headers))
            .addFunction(contentTypeMatchFunction())
            .build()
    }

    private fun partType(
        part: ClassName,
        byteReadChannel: ClassName,
        partFlow: com.squareup.kotlinpoet.TypeName,
        headers: com.squareup.kotlinpoet.TypeName,
    ): TypeSpec =
        TypeSpec
            .classBuilder(part)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter("headers", headers)
                    .addParameter(
                        ParameterSpec.builder("body", byteReadChannel.copy(nullable = true)).defaultValue("null").build(),
                    ).addParameter(
                        ParameterSpec.builder("parts", partFlow.copy(nullable = true)).defaultValue("null").build(),
                    ).build(),
            ).addProperty(PropertySpec.builder("headers", headers).initializer("headers").build())
            .addProperty(PropertySpec.builder("body", byteReadChannel.copy(nullable = true)).initializer("body").build())
            .addProperty(PropertySpec.builder("parts", partFlow.copy(nullable = true)).initializer("parts").build())
            .addInitializerBlock(
                CodeBlock.of("require((body == null) != (parts == null)) { %S }", "Exactly one of body or parts is available"),
            ).build()

    private fun encodingType(
        encoding: ClassName,
        stringList: com.squareup.kotlinpoet.TypeName,
        fixedHeaders: com.squareup.kotlinpoet.TypeName,
        encodings: com.squareup.kotlinpoet.TypeName,
    ): TypeSpec =
        TypeSpec
            .classBuilder(encoding)
            .addModifiers(KModifier.INTERNAL, KModifier.DATA)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter("contentTypes", stringList)
                    .addParameter("requiredHeaders", Set::class.asClassName().parameterizedBy(String::class.asTypeName()))
                    .addParameter("fixedHeaders", fixedHeaders)
                    .addParameter("prefixEncodings", encodings)
                    .addParameter("itemEncoding", encoding.copy(nullable = true))
                    .addParameter("minimumPartCount", Int::class)
                    .addParameter("maximumPartCount", Int::class.asTypeName().copy(nullable = true))
                    .build(),
            ).addProperty(PropertySpec.builder("contentTypes", stringList).initializer("contentTypes").build())
            .addProperty(
                PropertySpec
                    .builder("requiredHeaders", Set::class.asClassName().parameterizedBy(String::class.asTypeName()))
                    .initializer("requiredHeaders")
                    .build(),
            ).addProperty(PropertySpec.builder("fixedHeaders", fixedHeaders).initializer("fixedHeaders").build())
            .addProperty(PropertySpec.builder("prefixEncodings", encodings).initializer("prefixEncodings").build())
            .addProperty(PropertySpec.builder("itemEncoding", encoding.copy(nullable = true)).initializer("itemEncoding").build())
            .addProperty(PropertySpec.builder("minimumPartCount", Int::class).initializer("minimumPartCount").build())
            .addProperty(
                PropertySpec
                    .builder("maximumPartCount", Int::class.asTypeName().copy(nullable = true))
                    .initializer("maximumPartCount")
                    .build(),
            ).build()

    private fun readFunction(
        part: ClassName,
        encoding: ClassName,
        byteReadChannel: ClassName,
        partFlow: com.squareup.kotlinpoet.TypeName,
    ): FunSpec =
        FunSpec
            .builder("read")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("input", byteReadChannel)
            .addParameter("contentType", String::class)
            .addParameter("contentLength", Long::class.asTypeName().copy(nullable = true))
            .addParameter("encoding", encoding)
            .returns(partFlow)
            .addCode(
                CodeBlock
                    .builder()
                    .add("return %M {\n", MemberName("kotlinx.coroutines.flow", "flow"))
                    .indent()
                    .add("%M {\n", MemberName("kotlinx.coroutines", "coroutineScope"))
                    .indent()
                    .addStatement("val events = %M(input, contentType, contentLength)", MemberName("io.ktor.http.cio", "parseMultipart"))
                    .addStatement("var partCount = 0")
                    .beginControlFlow("try")
                    .beginControlFlow("for (event in events)")
                    .beginControlFlow("when (event)")
                    .beginControlFlow("is %T ->", ClassName("io.ktor.http.cio", "MultipartEvent", "MultipartPart"))
                    .addStatement("partCount++")
                    .addStatement(
                        "encoding.maximumPartCount?.let { maximum -> require(partCount <= maximum) { %P } }",
                        "Expected at most \$maximum multipart parts, but received at least \$partCount",
                    ).addStatement("val partEncoding = encoding.prefixEncodings.getOrNull(partCount - 1) ?: encoding.itemEncoding")
                    .addStatement("val rawHeaders = event.headers.await()")
                    .beginControlFlow("val headers = try")
                    .addStatement(
                        "(0 until rawHeaders.size).groupBy({ rawHeaders.nameAt(it).toString() }, { rawHeaders.valueAt(it).toString() })",
                    ).nextControlFlow("finally")
                    .addStatement("rawHeaders.release()")
                    .endControlFlow()
                    .addStatement(
                        "val selectedContentType = headers.header(%S) ?: partEncoding?.contentTypes?.firstOrNull() ?: %S",
                        "Content-Type",
                        "application/octet-stream",
                    ).addStatement(
                        "require('*' !in selectedContentType) { %P }",
                        "A concrete content type is required for multipart part \$partCount",
                    ).beginControlFlow("partEncoding?.contentTypes?.takeIf { it.isNotEmpty() }?.let { allowed ->")
                    .addStatement(
                        "require(allowed.any { selectedContentType.matchesContentType(it) }) { %P }",
                        "Content type '\$selectedContentType' is not allowed for multipart part \$partCount; expected one of \$allowed",
                    ).endControlFlow()
                    .beginControlFlow("partEncoding?.requiredHeaders?.forEach { requiredHeader ->")
                    .addStatement(
                        "require(headers.keys.any { it.equals(requiredHeader, ignoreCase = true) }) { %P }",
                        "Multipart part header '\$requiredHeader' is required for part \$partCount",
                    ).endControlFlow()
                    .beginControlFlow("partEncoding?.fixedHeaders?.forEach { (name, expectedValue) ->")
                    .addStatement(
                        "require(headers.header(name)?.equals(expectedValue, ignoreCase = true) == true) { %P }",
                        "Multipart part header '\$name' must have value '\$expectedValue' for part \$partCount",
                    ).endControlFlow()
                    .addStatement("val nested = selectedContentType.substringBefore(';').trim().startsWith(%S)", "multipart/")
                    .addStatement(
                        "val nestedParts = if (nested) read(event.body, selectedContentType, headers.header(%S)?.toLongOrNull(), requireNotNull(partEncoding)) else null",
                        "Content-Length",
                    ).beginControlFlow("try")
                    .addStatement("emit(%T(headers, event.body.takeUnless { nested }, nestedParts))", part)
                    .nextControlFlow("finally")
                    .addStatement("event.body.%M()", MemberName("io.ktor.utils.io", "discard"))
                    .endControlFlow()
                    .endControlFlow()
                    .beginControlFlow("else ->")
                    .addStatement("event.release()")
                    .endControlFlow()
                    .endControlFlow()
                    .endControlFlow()
                    .addStatement(
                        "require(partCount >= encoding.minimumPartCount) { %P }",
                        "Expected at least \${encoding.minimumPartCount} multipart parts, but received \$partCount",
                    ).nextControlFlow("finally")
                    .addStatement("events.cancel()")
                    .endControlFlow()
                    .unindent()
                    .add("}\n")
                    .unindent()
                    .add("}\n")
                    .build(),
            ).build()

    private fun headerFunction(headers: com.squareup.kotlinpoet.TypeName): FunSpec =
        FunSpec
            .builder("header")
            .addModifiers(KModifier.PRIVATE)
            .receiver(headers)
            .addParameter("name", String::class)
            .returns(String::class.asTypeName().copy(nullable = true))
            .addStatement("return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()")
            .build()

    private fun contentTypeMatchFunction(): FunSpec =
        FunSpec
            .builder("matchesContentType")
            .addModifiers(KModifier.PRIVATE)
            .receiver(String::class)
            .addParameter("allowed", String::class)
            .returns(Boolean::class)
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement("val actualType = substringBefore(';').trim()")
                    .addStatement("val allowedType = allowed.substringBefore(';').trim()")
                    .addStatement(
                        "return allowedType == %S || actualType == allowedType || (allowedType.endsWith(%S) && actualType.startsWith(allowedType.substringBefore('/').plus('/')))",
                        "*/*",
                        "/*",
                    ).build(),
            ).build()
}
