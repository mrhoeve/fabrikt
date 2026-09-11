package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.configurations.Packages
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName

internal object OkHttpSequentialMultipartLibrary {
    fun file(packages: Packages): FileSpec {
        val multipartPart = ClassName(packages.client, "MultipartPart")
        val multipartEncoding = ClassName(packages.client, "MultipartEncoding")
        val stringList = List::class.asClassName().parameterizedBy(String::class.asTypeName())
        val stringSet = Set::class.asClassName().parameterizedBy(String::class.asTypeName())
        val parts = Iterable::class.asClassName().parameterizedBy(multipartPart)
        val encodingList = List::class.asClassName().parameterizedBy(multipartEncoding)

        return FileSpec
            .builder(packages.client, "SequentialMultipart")
            .addType(
                TypeSpec
                    .classBuilder(multipartPart)
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec
                            .constructorBuilder()
                            .addParameter(
                                ParameterSpec
                                    .builder(
                                        "body",
                                        ByteArray::class.asTypeName().copy(nullable = true),
                                    ).defaultValue("null")
                                    .build(),
                            ).addParameter(ParameterSpec.builder("parts", parts.copy(nullable = true)).defaultValue("null").build())
                            .addParameter(
                                ParameterSpec
                                    .builder(
                                        "contentType",
                                        String::class.asTypeName().copy(nullable = true),
                                    ).defaultValue("null")
                                    .build(),
                            ).addParameter(
                                ParameterSpec
                                    .builder(
                                        "headers",
                                        Map::class.asClassName().parameterizedBy(String::class.asTypeName(), String::class.asTypeName()),
                                    ).defaultValue("emptyMap()")
                                    .build(),
                            ).build(),
                    ).addProperty(
                        PropertySpec.builder("body", ByteArray::class.asTypeName().copy(nullable = true)).initializer("body").build(),
                    ).addProperty(PropertySpec.builder("parts", parts.copy(nullable = true)).initializer("parts").build())
                    .addProperty(
                        PropertySpec
                            .builder(
                                "contentType",
                                String::class.asTypeName().copy(nullable = true),
                            ).initializer("contentType")
                            .build(),
                    ).addProperty(
                        PropertySpec
                            .builder(
                                "headers",
                                Map::class.asClassName().parameterizedBy(String::class.asTypeName(), String::class.asTypeName()),
                            ).initializer("headers")
                            .build(),
                    ).addInitializerBlock(
                        CodeBlock.of("require((body == null) != (parts == null)) { %S }", "Exactly one of body or parts must be supplied"),
                    ).build(),
            ).addType(
                TypeSpec
                    .classBuilder(multipartEncoding)
                    .addModifiers(KModifier.INTERNAL, KModifier.DATA)
                    .primaryConstructor(
                        FunSpec
                            .constructorBuilder()
                            .addParameter("contentTypes", stringList)
                            .addParameter("requiredHeaders", stringSet)
                            .addParameter("prefixEncodings", encodingList)
                            .addParameter("itemEncoding", multipartEncoding.copy(nullable = true))
                            .addParameter("minimumPartCount", Int::class)
                            .addParameter("maximumPartCount", Int::class.asTypeName().copy(nullable = true))
                            .build(),
                    ).addProperty(PropertySpec.builder("contentTypes", stringList).initializer("contentTypes").build())
                    .addProperty(PropertySpec.builder("requiredHeaders", stringSet).initializer("requiredHeaders").build())
                    .addProperty(PropertySpec.builder("prefixEncodings", encodingList).initializer("prefixEncodings").build())
                    .addProperty(
                        PropertySpec.builder("itemEncoding", multipartEncoding.copy(nullable = true)).initializer("itemEncoding").build(),
                    ).addProperty(PropertySpec.builder("minimumPartCount", Int::class).initializer("minimumPartCount").build())
                    .addProperty(
                        PropertySpec
                            .builder(
                                "maximumPartCount",
                                Int::class.asTypeName().copy(nullable = true),
                            ).initializer("maximumPartCount")
                            .build(),
                    ).build(),
            ).addFunction(buildBodyFunction(packages, multipartPart, multipartEncoding, parts))
            .addFunction(partBodyFunction(packages, multipartPart, multipartEncoding))
            .addFunction(contentTypeMatchFunction())
            .build()
    }

    private fun buildBodyFunction(
        packages: Packages,
        multipartPart: ClassName,
        multipartEncoding: ClassName,
        parts: com.squareup.kotlinpoet.TypeName,
    ): FunSpec =
        FunSpec
            .builder("buildSequentialMultipartBody")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("parts", parts)
            .addParameter("mediaType", String::class)
            .addParameter("encoding", multipartEncoding)
            .returns(ClassName("okhttp3", "RequestBody"))
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement(
                        "val builder = %T.Builder().setType(mediaType.%M())",
                        ClassName("okhttp3", "MultipartBody"),
                        com.squareup.kotlinpoet.MemberName("okhttp3.MediaType.Companion", "toMediaType"),
                    ).addStatement("var partCount = 0")
                    .beginControlFlow("parts.forEachIndexed { index, part ->")
                    .addStatement("partCount++")
                    .addStatement(
                        "encoding.maximumPartCount?.let { maximum -> require(partCount <= maximum) { %P } }",
                        "Expected at most \$maximum multipart parts, but received at least \$partCount",
                    ).addStatement("val partEncoding = encoding.prefixEncodings.getOrNull(index) ?: encoding.itemEncoding")
                    .addStatement("val headers = %T.Builder()", ClassName("okhttp3", "Headers"))
                    .beginControlFlow("part.headers.forEach { (name, value) ->")
                    .addStatement(
                        "require(!name.equals(%S, ignoreCase = true) && !name.equals(%S, ignoreCase = true)) { %P }",
                        "Content-Type",
                        "Content-Length",
                        "Multipart part headers must not contain Content-Type or Content-Length",
                    ).addStatement("headers.add(name, value)")
                    .endControlFlow()
                    .addStatement("builder.addPart(headers.build(), part.toRequestBody(partEncoding))")
                    .endControlFlow()
                    .addStatement(
                        "require(partCount >= encoding.minimumPartCount) { %P }",
                        "Expected at least \${encoding.minimumPartCount} multipart parts, but received \$partCount",
                    ).addStatement("return builder.build()")
                    .build(),
            ).build()

    private fun partBodyFunction(
        packages: Packages,
        multipartPart: ClassName,
        multipartEncoding: ClassName,
    ): FunSpec =
        FunSpec
            .builder("toRequestBody")
            .addModifiers(KModifier.PRIVATE)
            .receiver(multipartPart)
            .addParameter("encoding", multipartEncoding.copy(nullable = true))
            .returns(ClassName("okhttp3", "RequestBody"))
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement(
                        "val selectedContentType = contentType ?: encoding?.contentTypes?.firstOrNull() ?: %S",
                        "application/octet-stream",
                    ).addStatement(
                        "require('*' !in selectedContentType) { %P }",
                        "A concrete content type must be selected for this multipart part",
                    ).beginControlFlow("encoding?.contentTypes?.takeIf { it.isNotEmpty() }?.let { allowed ->")
                    .addStatement(
                        "require(allowed.any { selectedContentType.matchesContentType(it) }) { %P }",
                        "Content type '\$selectedContentType' is not allowed; expected one of \$allowed",
                    ).endControlFlow()
                    .beginControlFlow("encoding?.requiredHeaders?.forEach { requiredHeader ->")
                    .addStatement(
                        "require(headers.keys.any { it.equals(requiredHeader, ignoreCase = true) }) { %P }",
                        "Multipart part header '\$requiredHeader' is required",
                    ).endControlFlow()
                    .addStatement("val nestedParts = parts")
                    .beginControlFlow("if (nestedParts != null)")
                    .addStatement("return buildSequentialMultipartBody(nestedParts, selectedContentType, requireNotNull(encoding))")
                    .endControlFlow()
                    .addStatement(
                        "return requireNotNull(body).%M(selectedContentType.%M())",
                        com.squareup.kotlinpoet.MemberName("okhttp3.RequestBody.Companion", "toRequestBody"),
                        com.squareup.kotlinpoet.MemberName("okhttp3.MediaType.Companion", "toMediaType"),
                    ).build(),
            ).build()

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
