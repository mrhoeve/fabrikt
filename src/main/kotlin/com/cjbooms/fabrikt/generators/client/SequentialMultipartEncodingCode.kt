package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.model.MultipartPartEncoding
import com.cjbooms.fabrikt.model.SequentialMultipartParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock

internal fun SequentialMultipartParameter.toEncodingCodeBlock(clientPackage: String): CodeBlock =
    CodeBlock.of(
        "%T(contentTypes = listOf(%S), requiredHeaders = emptySet(), prefixEncodings = %L, itemEncoding = %L, minimumPartCount = %L, maximumPartCount = %L)",
        ClassName(clientPackage, "MultipartEncoding"),
        mediaType,
        prefixEncodings.toEncodingListCodeBlock(clientPackage),
        itemEncoding?.toEncodingCodeBlock(clientPackage) ?: CodeBlock.of("null"),
        minimumPartCount,
        maximumPartCount?.let { CodeBlock.of("%L", it) } ?: CodeBlock.of("null"),
    )

private fun List<MultipartPartEncoding>.toEncodingListCodeBlock(clientPackage: String): CodeBlock =
    if (isEmpty()) {
        CodeBlock.of("emptyList()")
    } else {
        CodeBlock
            .builder()
            .add("listOf(")
            .apply {
                this@toEncodingListCodeBlock.forEachIndexed { index, encoding ->
                    if (index > 0) add(", ")
                    add("%L", encoding.toEncodingCodeBlock(clientPackage))
                }
            }.add(")")
            .build()
    }

private fun MultipartPartEncoding.toEncodingCodeBlock(clientPackage: String): CodeBlock =
    CodeBlock.of(
        "%T(contentTypes = %L, requiredHeaders = %L, prefixEncodings = %L, itemEncoding = %L, minimumPartCount = %L, maximumPartCount = %L)",
        ClassName(clientPackage, "MultipartEncoding"),
        contentTypes.toStringCollectionCodeBlock("listOf"),
        requiredHeaders.toStringCollectionCodeBlock("setOf"),
        prefixEncodings.toEncodingListCodeBlock(clientPackage),
        itemEncoding?.toEncodingCodeBlock(clientPackage) ?: CodeBlock.of("null"),
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
