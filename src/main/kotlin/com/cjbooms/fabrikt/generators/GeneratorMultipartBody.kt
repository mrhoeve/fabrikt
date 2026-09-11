package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.parser.GeneratorEncoding
import com.cjbooms.fabrikt.parser.GeneratorMediaType
import com.cjbooms.fabrikt.parser.GeneratorSchema

internal sealed interface GeneratorMultipartBody {
    val mediaType: GeneratorMediaType

    data class Named(
        override val mediaType: GeneratorMediaType,
    ) : GeneratorMultipartBody

    data class Sequential(
        override val mediaType: GeneratorMediaType,
        val prefixParts: List<GeneratorSequentialMultipartPart>,
        val remainingPart: GeneratorSequentialMultipartPart?,
        val minimumPartCount: Int,
        val maximumPartCount: Int?,
        val streaming: Boolean,
    ) : GeneratorMultipartBody
}

internal data class GeneratorSequentialMultipartPart(
    val index: Int?,
    val schema: GeneratorSchema?,
    val encoding: GeneratorEncoding?,
    val required: Boolean,
)
