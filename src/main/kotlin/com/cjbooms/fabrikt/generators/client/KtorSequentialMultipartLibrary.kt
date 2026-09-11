package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.configurations.Packages
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
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
        val multipartContent = ClassName(packages.client, "SequentialMultipartContent")
        val contentType = ClassName("io.ktor.http", "ContentType")
        val stringList = List::class.asClassName().parameterizedBy(String::class.asTypeName())
        val stringSet = Set::class.asClassName().parameterizedBy(String::class.asTypeName())
        val parts = Iterable::class.asClassName().parameterizedBy(multipartPart)
        val encodingList = List::class.asClassName().parameterizedBy(multipartEncoding)

        return FileSpec
            .builder(packages.client, "SequentialMultipart")
            .addType(multipartPartType(multipartPart, parts))
            .addType(multipartEncodingType(multipartEncoding, stringList, stringSet, encodingList))
            .addType(multipartContentType(multipartContent, multipartEncoding, parts, contentType))
            .addFunction(buildContentFunction(multipartContent, multipartEncoding, parts))
            .addFunction(writeMultipartFunction(multipartEncoding, parts, contentType))
            .addFunction(selectContentTypeFunction(multipartPart, multipartEncoding))
            .addFunction(contentTypeMatchFunction())
            .build()
    }

    private fun multipartPartType(
        multipartPart: ClassName,
        parts: com.squareup.kotlinpoet.TypeName,
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
                    ).addParameter(ParameterSpec.builder("parts", parts.copy(nullable = true)).defaultValue("null").build())
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
            .addProperty(PropertySpec.builder("parts", parts.copy(nullable = true)).initializer("parts").build())
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
                    .builder("maximumPartCount", Int::class.asTypeName().copy(nullable = true))
                    .initializer("maximumPartCount")
                    .build(),
            ).build()

    private fun multipartContentType(
        multipartContent: ClassName,
        multipartEncoding: ClassName,
        parts: com.squareup.kotlinpoet.TypeName,
        contentType: ClassName,
    ): TypeSpec =
        TypeSpec
            .classBuilder(multipartContent)
            .addModifiers(KModifier.PRIVATE)
            .superclass(ClassName("io.ktor.http.content", "OutgoingContent", "WriteChannelContent"))
            .primaryConstructor(
                FunSpec
                    .constructorBuilder()
                    .addParameter("parts", parts)
                    .addParameter("mediaType", String::class)
                    .addParameter("encoding", multipartEncoding)
                    .addParameter(
                        ParameterSpec
                            .builder("boundary", String::class)
                            .defaultValue("%S + %T.randomUUID()", "fabrikt-", ClassName("java.util", "UUID"))
                            .build(),
                    ).build(),
            ).addProperty(
                PropertySpec
                    .builder("parts", parts)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("parts")
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder("encoding", multipartEncoding)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("encoding")
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder("boundary", String::class)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("boundary")
                    .build(),
            ).addProperty(
                PropertySpec
                    .builder("contentType", contentType)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T.parse(mediaType).withParameter(%S, boundary)", contentType, "boundary")
                    .build(),
            ).addFunction(
                FunSpec
                    .builder("writeTo")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("channel", ClassName("io.ktor.utils.io", "ByteWriteChannel"))
                    .addStatement("writeSequentialMultipart(channel, parts, encoding, boundary)")
                    .build(),
            ).build()

    private fun buildContentFunction(
        multipartContent: ClassName,
        multipartEncoding: ClassName,
        parts: com.squareup.kotlinpoet.TypeName,
    ): FunSpec =
        FunSpec
            .builder("buildSequentialMultipartContent")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("parts", parts)
            .addParameter("mediaType", String::class)
            .addParameter("encoding", multipartEncoding)
            .returns(ClassName("io.ktor.http.content", "OutgoingContent"))
            .addStatement("return %T(parts, mediaType, encoding)", multipartContent)
            .build()

    private fun writeMultipartFunction(
        multipartEncoding: ClassName,
        parts: com.squareup.kotlinpoet.TypeName,
        contentType: ClassName,
    ): FunSpec {
        val writeStringUtf8 = MemberName("io.ktor.utils.io", "writeStringUtf8")
        val writeFully = MemberName("io.ktor.utils.io", "writeFully")
        return FunSpec
            .builder("writeSequentialMultipart")
            .addModifiers(KModifier.PRIVATE, KModifier.SUSPEND)
            .addParameter("channel", ClassName("io.ktor.utils.io", "ByteWriteChannel"))
            .addParameter("parts", parts)
            .addParameter("encoding", multipartEncoding)
            .addParameter("boundary", String::class)
            .addCode(
                CodeBlock
                    .builder()
                    .addStatement("var partCount = 0")
                    .beginControlFlow("parts.forEachIndexed { index, part ->")
                    .addStatement("partCount++")
                    .addStatement(
                        "encoding.maximumPartCount?.let { maximum -> require(partCount <= maximum) { %P } }",
                        "Expected at most \$maximum multipart parts, but received at least \$partCount",
                    ).addStatement("val partEncoding = encoding.prefixEncodings.getOrNull(index) ?: encoding.itemEncoding")
                    .addStatement("val selectedContentType = part.selectContentType(partEncoding)")
                    .addStatement("val nestedParts = part.parts")
                    .addStatement(
                        "val nestedBoundary = nestedParts?.let { %S + %T.randomUUID() }",
                        "fabrikt-",
                        ClassName("java.util", "UUID"),
                    ).addStatement(
                        "val partContentType = nestedBoundary?.let { %T.parse(selectedContentType).withParameter(%S, it) } ?: %T.parse(selectedContentType)",
                        contentType,
                        "boundary",
                        contentType,
                    ).addStatement("channel.%M(%S)", writeStringUtf8, "--")
                    .addStatement("channel.%M(boundary)", writeStringUtf8)
                    .addStatement("channel.%M(byteArrayOf(13, 10))", writeFully)
                    .beginControlFlow("part.headers.forEach { (name, value) ->")
                    .addStatement(
                        "require(!name.equals(%S, ignoreCase = true) && !name.equals(%S, ignoreCase = true)) { %P }",
                        "Content-Type",
                        "Content-Length",
                        "Multipart part headers must not contain Content-Type or Content-Length",
                    ).addStatement(
                        "require((name + value).none { it.code == 13 || it.code == 10 }) { %S }",
                        "Multipart part headers must not contain line breaks",
                    ).addStatement("channel.%M(name)", writeStringUtf8)
                    .addStatement("channel.%M(%S)", writeStringUtf8, ": ")
                    .addStatement("channel.%M(value)", writeStringUtf8)
                    .addStatement("channel.%M(byteArrayOf(13, 10))", writeFully)
                    .endControlFlow()
                    .addStatement("channel.%M(%S)", writeStringUtf8, "Content-Type: ")
                    .addStatement("channel.%M(partContentType.toString())", writeStringUtf8)
                    .addStatement("channel.%M(byteArrayOf(13, 10, 13, 10))", writeFully)
                    .beginControlFlow("if (nestedParts != null)")
                    .addStatement(
                        "writeSequentialMultipart(channel, nestedParts, requireNotNull(partEncoding), requireNotNull(nestedBoundary))",
                    ).nextControlFlow("else")
                    .addStatement("channel.%M(requireNotNull(part.body))", writeFully)
                    .endControlFlow()
                    .addStatement("channel.%M(byteArrayOf(13, 10))", writeFully)
                    .endControlFlow()
                    .addStatement(
                        "require(partCount >= encoding.minimumPartCount) { %P }",
                        "Expected at least \${encoding.minimumPartCount} multipart parts, but received \$partCount",
                    ).addStatement("channel.%M(%S)", writeStringUtf8, "--")
                    .addStatement("channel.%M(boundary)", writeStringUtf8)
                    .addStatement("channel.%M(%S)", writeStringUtf8, "--")
                    .addStatement("channel.%M(byteArrayOf(13, 10))", writeFully)
                    .build(),
            ).build()
    }

    private fun selectContentTypeFunction(
        multipartPart: ClassName,
        multipartEncoding: ClassName,
    ): FunSpec =
        FunSpec
            .builder("selectContentType")
            .addModifiers(KModifier.PRIVATE)
            .receiver(multipartPart)
            .addParameter("encoding", multipartEncoding.copy(nullable = true))
            .returns(String::class)
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
                    .beginControlFlow("if (parts != null)")
                    .addStatement(
                        "require(selectedContentType.substringBefore(';').startsWith(%S)) { %S }",
                        "multipart/",
                        "Nested parts require a multipart content type",
                    ).addStatement("requireNotNull(encoding) { %S }", "Nested parts require multipart encoding metadata")
                    .endControlFlow()
                    .addStatement("return selectedContentType")
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
