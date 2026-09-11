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

internal object KtorSequentialMultipartLibrary {
    fun file(packages: Packages): FileSpec {
        val multipartPart = ClassName(packages.client, "MultipartPart")
        val multipartEncoding = ClassName(packages.client, "MultipartEncoding")
        val encodedPart = ClassName(packages.client, "EncodedMultipartPart")
        val contentType = ClassName("io.ktor.http", "ContentType")
        val stringList = List::class.asClassName().parameterizedBy(String::class.asTypeName())
        val stringSet = Set::class.asClassName().parameterizedBy(String::class.asTypeName())
        val partList = List::class.asClassName().parameterizedBy(multipartPart)
        val encodingList = List::class.asClassName().parameterizedBy(multipartEncoding)

        return FileSpec
            .builder(packages.client, "SequentialMultipart")
            .addType(multipartPartType(multipartPart, partList))
            .addType(multipartEncodingType(multipartEncoding, stringList, stringSet, encodingList))
            .addType(encodedPartType(encodedPart, contentType))
            .addFunction(buildContentFunction(multipartEncoding, partList))
            .addFunction(encodeMultipartFunction(multipartEncoding, encodedPart, partList, contentType))
            .addFunction(encodePartFunction(multipartPart, multipartEncoding, encodedPart, contentType))
            .addFunction(contentTypeMatchFunction())
            .build()
    }

    private fun multipartPartType(
        multipartPart: ClassName,
        partList: com.squareup.kotlinpoet.TypeName,
    ): TypeSpec =
        TypeSpec
            .classBuilder(multipartPart)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter(
                        ParameterSpec
                            .builder("body", ByteArray::class.asTypeName().copy(nullable = true))
                            .defaultValue("null")
                            .build(),
                    ).addParameter(ParameterSpec.builder("parts", partList.copy(nullable = true)).defaultValue("null").build())
                    .addParameter(
                        ParameterSpec
                            .builder("contentType", String::class.asTypeName().copy(nullable = true))
                            .defaultValue("null")
                            .build(),
                    ).addParameter(
                        ParameterSpec
                            .builder(
                                "headers",
                                Map::class.asClassName().parameterizedBy(String::class.asTypeName(), String::class.asTypeName()),
                            ).defaultValue("emptyMap()")
                            .build(),
                    ).build(),
            ).addProperty(PropertySpec.builder("body", ByteArray::class.asTypeName().copy(nullable = true)).initializer("body").build())
            .addProperty(PropertySpec.builder("parts", partList.copy(nullable = true)).initializer("parts").build())
            .addProperty(
                PropertySpec.builder("contentType", String::class.asTypeName().copy(nullable = true)).initializer("contentType").build(),
            ).addProperty(
                PropertySpec
                    .builder(
                        "headers",
                        Map::class.asClassName().parameterizedBy(String::class.asTypeName(), String::class.asTypeName()),
                    ).initializer("headers")
                    .build(),
            ).addInitializerBlock(
                CodeBlock.of("require((body == null) != (parts == null)) { %S }", "Exactly one of body or parts must be supplied"),
            ).build()

    private fun multipartEncodingType(
        multipartEncoding: ClassName,
        stringList: com.squareup.kotlinpoet.TypeName,
        stringSet: com.squareup.kotlinpoet.TypeName,
        encodingList: com.squareup.kotlinpoet.TypeName,
    ): TypeSpec =
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
            .addProperty(PropertySpec.builder("itemEncoding", multipartEncoding.copy(nullable = true)).initializer("itemEncoding").build())
            .addProperty(PropertySpec.builder("minimumPartCount", Int::class).initializer("minimumPartCount").build())
            .addProperty(
                PropertySpec
                    .builder(
                        "maximumPartCount",
                        Int::class.asTypeName().copy(nullable = true),
                    ).initializer("maximumPartCount")
                    .build(),
            ).build()

    private fun encodedPartType(
        encodedPart: ClassName,
        contentType: ClassName,
    ): TypeSpec =
        TypeSpec
            .classBuilder(encodedPart)
            .addModifiers(KModifier.PRIVATE, KModifier.DATA)
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter("body", ByteArray::class)
                    .addParameter("contentType", contentType)
                    .build(),
            ).addProperty(PropertySpec.builder("body", ByteArray::class).initializer("body").build())
            .addProperty(PropertySpec.builder("contentType", contentType).initializer("contentType").build())
            .build()

    private fun buildContentFunction(
        multipartEncoding: ClassName,
        partList: com.squareup.kotlinpoet.TypeName,
    ): FunSpec =
        FunSpec
            .builder("buildSequentialMultipartContent")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("parts", partList)
            .addParameter("mediaType", String::class)
            .addParameter("encoding", multipartEncoding)
            .returns(ClassName("io.ktor.http.content", "ByteArrayContent"))
            .addStatement("val encoded = encodeSequentialMultipart(parts, mediaType, encoding)")
            .addStatement("return %T(encoded.body, encoded.contentType)", ClassName("io.ktor.http.content", "ByteArrayContent"))
            .build()

    private fun encodeMultipartFunction(
        multipartEncoding: ClassName,
        encodedPart: ClassName,
        partList: com.squareup.kotlinpoet.TypeName,
        contentType: ClassName,
    ): FunSpec =
        FunSpec
            .builder("encodeSequentialMultipart")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("parts", partList)
            .addParameter("mediaType", String::class)
            .addParameter("encoding", multipartEncoding)
            .returns(encodedPart)
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement(
                        "require(parts.size >= encoding.minimumPartCount) { %P }",
                        "Expected at least \${encoding.minimumPartCount} multipart parts, but received \${parts.size}",
                    ).addStatement(
                        "encoding.maximumPartCount?.let { maximum -> require(parts.size <= maximum) { %P } }",
                        "Expected at most \$maximum multipart parts, but received \${parts.size}",
                    ).addStatement("val boundary = %S + %T.randomUUID()", "fabrikt-", ClassName("java.util", "UUID"))
                    .addStatement("val output = %T()", ClassName("java.io", "ByteArrayOutputStream"))
                    .beginControlFlow("parts.forEachIndexed { index, part ->")
                    .addStatement("val partEncoding = encoding.prefixEncodings.getOrNull(index) ?: encoding.itemEncoding")
                    .addStatement("val encodedPart = part.encode(partEncoding)")
                    .addStatement("output.write(%S.toByteArray())", "--")
                    .addStatement("output.write(boundary.toByteArray())")
                    .addStatement("output.write(byteArrayOf(13, 10))")
                    .beginControlFlow("part.headers.forEach { (name, value) ->")
                    .addStatement(
                        "require(!name.equals(%S, ignoreCase = true) && !name.equals(%S, ignoreCase = true)) { %P }",
                        "Content-Type",
                        "Content-Length",
                        "Multipart part headers must not contain Content-Type or Content-Length",
                    ).addStatement(
                        "require('\\r' !in name && '\\n' !in name && '\\r' !in value && '\\n' !in value) { %S }",
                        "Multipart part headers must not contain line breaks",
                    ).addStatement("output.write(name.toByteArray())")
                    .addStatement("output.write(%S.toByteArray())", ": ")
                    .addStatement("output.write(value.toByteArray())")
                    .addStatement("output.write(byteArrayOf(13, 10))")
                    .endControlFlow()
                    .addStatement("output.write(%S.toByteArray())", "Content-Type: ")
                    .addStatement("output.write(encodedPart.contentType.toString().toByteArray())")
                    .addStatement("output.write(byteArrayOf(13, 10, 13, 10))")
                    .addStatement("output.write(encodedPart.body)")
                    .addStatement("output.write(byteArrayOf(13, 10))")
                    .endControlFlow()
                    .addStatement("output.write(%S.toByteArray())", "--")
                    .addStatement("output.write(boundary.toByteArray())")
                    .addStatement("output.write(%S.toByteArray())", "--")
                    .addStatement("output.write(byteArrayOf(13, 10))")
                    .addStatement(
                        "return %T(output.toByteArray(), %T.parse(mediaType).withParameter(%S, boundary))",
                        encodedPart,
                        contentType,
                        "boundary",
                    ).build(),
            ).build()

    private fun encodePartFunction(
        multipartPart: ClassName,
        multipartEncoding: ClassName,
        encodedPart: ClassName,
        contentType: ClassName,
    ): FunSpec =
        FunSpec
            .builder("encode")
            .addModifiers(KModifier.PRIVATE)
            .receiver(multipartPart)
            .addParameter("encoding", multipartEncoding.copy(nullable = true))
            .returns(encodedPart)
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
                    .addStatement(
                        "require(selectedContentType.substringBefore(';').startsWith(%S)) { %S }",
                        "multipart/",
                        "Nested parts require a multipart content type",
                    ).addStatement("return encodeSequentialMultipart(nestedParts, selectedContentType, requireNotNull(encoding))")
                    .endControlFlow()
                    .addStatement("return %T(requireNotNull(body), %T.parse(selectedContentType))", encodedPart, contentType)
                    .build(),
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
