package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceSecuritySchemeParserTest {
    @Test
    fun `models reusable OpenAPI 3_2 security schemes`() {
        val schemes = SourceOpenApiDocumentParser.parse(openApi).operations.reusableSecuritySchemes

        assertThat(schemes.keys)
            .containsExactly("ApiKey", "Bearer", "MutualTls", "OAuth", "OpenId", "Referenced")

        val apiKey = schemes.getValue("ApiKey")
        assertThat(apiKey.location).isEqualTo("#/components/securitySchemes/ApiKey")
        assertThat(apiKey.type)
            .isEqualTo(SourceSecuritySchemeType.Fixed(SourceFixedSecuritySchemeType.API_KEY))
        assertThat(apiKey.name).isEqualTo("X-API-Key")
        assertThat(apiKey.placement)
            .isEqualTo(SourceApiKeyPlacement.Fixed(SourceFixedApiKeyPlacement.HEADER))
        assertThat(apiKey.description).isEqualTo("Application key")
        assertThat(apiKey.deprecated).isTrue()
        assertThat(apiKey.extensions).containsOnlyKeys("x-managed")

        val bearer = schemes.getValue("Bearer")
        assertThat(bearer.type)
            .isEqualTo(SourceSecuritySchemeType.Fixed(SourceFixedSecuritySchemeType.HTTP))
        assertThat(bearer.scheme).isEqualTo("Bearer")
        assertThat(bearer.bearerFormat).isEqualTo("JWT")

        assertThat(schemes.getValue("MutualTls").type)
            .isEqualTo(SourceSecuritySchemeType.Fixed(SourceFixedSecuritySchemeType.MUTUAL_TLS))
        assertThat(schemes.getValue("OAuth").oauth2MetadataUrl)
            .isEqualTo("https://auth.example.com/.well-known/oauth-authorization-server")
        assertThat(schemes.getValue("OpenId").openIdConnectUrl)
            .isEqualTo("https://auth.example.com/.well-known/openid-configuration")
        assertThat(schemes.getValue("Referenced").reference)
            .isEqualTo("#/components/securitySchemes/Bearer")
    }

    @Test
    fun `applies security scheme fields according to the OpenAPI version`() {
        val schemes =
            SourceOpenApiDocumentParser
                .parse(openApi.replace("openapi: 3.2.0", "openapi: 3.0.4"))
                .operations
                .reusableSecuritySchemes

        assertThat(schemes.getValue("MutualTls").type)
            .isEqualTo(SourceSecuritySchemeType.Unrecognised("mutualTLS"))
        assertThat(schemes.getValue("OAuth").oauth2MetadataUrl).isNull()
        assertThat(schemes.getValue("ApiKey").deprecated).isFalse()
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          securitySchemes:
            ApiKey:
              type: apiKey
              description: Application key
              name: X-API-Key
              in: header
              deprecated: true
              x-managed: true
            Bearer:
              type: http
              scheme: Bearer
              bearerFormat: JWT
            MutualTls:
              type: mutualTLS
            OAuth:
              type: oauth2
              oauth2MetadataUrl: https://auth.example.com/.well-known/oauth-authorization-server
              flows: {}
            OpenId:
              type: openIdConnect
              openIdConnectUrl: https://auth.example.com/.well-known/openid-configuration
            Referenced:
              ${'$'}ref: '#/components/securitySchemes/Bearer'
        """.trimIndent()
}
