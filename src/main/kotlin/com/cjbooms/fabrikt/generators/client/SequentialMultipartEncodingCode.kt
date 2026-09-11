package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.model.MultipartPartEncoding
import com.cjbooms.fabrikt.model.SequentialMultipartParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

internal fun SequentialMultipartParameter.toEncodingCodeBlock(clientPackage: String): CodeBlock =
    toEncodingCodeBlock(ClassName(clientPackage, "MultipartEncoding"))

internal fun SequentialMultipartParameter.toEncodingCodeBlock(multipartEncoding: ClassName): CodeBlock =
    CodeBlock.of(
        "%T(contentTypes = listOf(%S), requiredHeaders = emptySet(), prefixEncodings = %L, itemEncoding = %L, minimumPartCount = %L, maximumPartCount = %L)",
        multipartEncoding,
        mediaType,
        prefixEncodings.toEncodingListCodeBlock(multipartEncoding),
        itemEncoding?.toEncodingCodeBlock(multipartEncoding) ?: CodeBlock.of("null"),
        minimumPartCount,
        maximumPartCount?.let { CodeBlock.of("%L", it) } ?: CodeBlock.of("null"),
    )

private fun List<MultipartPartEncoding>.toEncodingListCodeBlock(multipartEncoding: ClassName): CodeBlock =
    if (isEmpty()) {
        CodeBlock.of("emptyList()")
    } else {
        CodeBlock
            .builder()
            .add("listOf(")
            .apply {
                this@toEncodingListCodeBlock.forEachIndexed { index, encoding ->
                    if (index > 0) add(", ")
                    add("%L", encoding.toEncodingCodeBlock(multipartEncoding))
                }
            }.add(")")
            .build()
    }

private fun MultipartPartEncoding.toEncodingCodeBlock(multipartEncoding: ClassName): CodeBlock =
    CodeBlock.of(
        "%T(contentTypes = %L, requiredHeaders = %L, prefixEncodings = %L, itemEncoding = %L, minimumPartCount = %L, maximumPartCount = %L)",
        multipartEncoding,
        contentTypes.toStringCollectionCodeBlock("listOf"),
        requiredHeaders.toStringCollectionCodeBlock("setOf"),
        prefixEncodings.toEncodingListCodeBlock(multipartEncoding),
        itemEncoding?.toEncodingCodeBlock(multipartEncoding) ?: CodeBlock.of("null"),
        minimumPartCount,
        maximumPartCount?.let { CodeBlock.of("%L", it) } ?: CodeBlock.of("null"),
    )

private fun Collection<String>.toStringCollectionCodeBlock(factory: String): CodeBlock =
    CodeBlock
        .builder()
        .add("$factory(")
        .apply {
            this@toStringCollectionCodeBlock.forEachIndexed { index, value ->
                if (index > 0) add(", ")
                add("%S", value)
            }
        }.add(")")
        .build()
