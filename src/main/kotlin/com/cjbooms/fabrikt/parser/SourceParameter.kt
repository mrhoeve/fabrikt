package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceParameter(
    val location: String,
    val node: JsonNode,
    val reference: String?,
    val name: String?,
    val placement: SourceParameterPlacement?,
    val description: String?,
    val required: Boolean,
    val deprecated: Boolean,
    val allowEmptyValue: Boolean?,
    val style: String?,
    val explode: Boolean?,
    val allowReserved: Boolean?,
    val schema: SourceSchema?,
    val content: List<SourceParameterContent>,
    val extensions: Map<String, JsonNode>,
)

internal sealed interface SourceParameterPlacement {
    val value: String

    data class Fixed(
        val location: SourceFixedParameterPlacement,
    ) : SourceParameterPlacement {
        override val value: String = location.value
    }

    data class Unrecognised(
        override val value: String,
    ) : SourceParameterPlacement
}

internal enum class SourceFixedParameterPlacement(
    val value: String,
) {
    QUERY("query"),
    HEADER("header"),
    PATH("path"),
    COOKIE("cookie"),
    QUERYSTRING("querystring"),
}

internal data class SourceParameterContent(
    val location: String,
    val mediaType: String,
    val node: JsonNode,
    val schema: SourceSchema?,
    val extensions: Map<String, JsonNode>,
)
