package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.JsonNode

internal data class SourceSecurityScheme(
    val location: String,
    val key: String,
    val node: JsonNode,
    val reference: String?,
    val type: SourceSecuritySchemeType?,
    val description: String?,
    val name: String?,
    val placement: SourceApiKeyPlacement?,
    val scheme: String?,
    val bearerFormat: String?,
    val openIdConnectUrl: String?,
    val oauth2MetadataUrl: String?,
    val deprecated: Boolean,
    val extensions: Map<String, JsonNode>,
)

internal sealed interface SourceSecuritySchemeType {
    val value: String

    data class Fixed(
        val type: SourceFixedSecuritySchemeType,
    ) : SourceSecuritySchemeType {
        override val value: String = type.value
    }

    data class Unrecognised(
        override val value: String,
    ) : SourceSecuritySchemeType
}

internal enum class SourceFixedSecuritySchemeType(
    val value: String,
) {
    API_KEY("apiKey"),
    HTTP("http"),
    MUTUAL_TLS("mutualTLS"),
    OAUTH2("oauth2"),
    OPEN_ID_CONNECT("openIdConnect"),
}

internal sealed interface SourceApiKeyPlacement {
    val value: String

    data class Fixed(
        val placement: SourceFixedApiKeyPlacement,
    ) : SourceApiKeyPlacement {
        override val value: String = placement.value
    }

    data class Unrecognised(
        override val value: String,
    ) : SourceApiKeyPlacement
}

internal enum class SourceFixedApiKeyPlacement(
    val value: String,
) {
    QUERY("query"),
    HEADER("header"),
    COOKIE("cookie"),
}
