package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOAuthFlowParserTest {
    @Test
    fun `models every OpenAPI 3_2 OAuth flow`() {
        val flows = oauthFlows(openApi)

        assertThat(flows.location).isEqualTo("#/components/securitySchemes/OAuth/flows")
        assertThat(flows.extensions).containsOnlyKeys("x-provider")
        assertThat(flows.values.map(SourceOAuthFlow::key))
            .containsExactly("implicit", "password", "clientCredentials", "authorizationCode", "deviceAuthorization")
        assertThat(flows.values.map(SourceOAuthFlow::type))
            .containsExactly(
                SourceOAuthFlowType.Fixed(SourceFixedOAuthFlowType.IMPLICIT),
                SourceOAuthFlowType.Fixed(SourceFixedOAuthFlowType.PASSWORD),
                SourceOAuthFlowType.Fixed(SourceFixedOAuthFlowType.CLIENT_CREDENTIALS),
                SourceOAuthFlowType.Fixed(SourceFixedOAuthFlowType.AUTHORIZATION_CODE),
                SourceOAuthFlowType.Fixed(SourceFixedOAuthFlowType.DEVICE_AUTHORIZATION),
            )

        val authorizationCode = flows.values[3]
        assertThat(authorizationCode.authorizationUrl).isEqualTo("https://auth.example.com/authorize")
        assertThat(authorizationCode.tokenUrl).isEqualTo("https://auth.example.com/token")
        assertThat(authorizationCode.refreshUrl).isEqualTo("https://auth.example.com/refresh")
        assertThat(authorizationCode.scopes)
            .containsExactlyEntriesOf(linkedMapOf("read:subjects" to "Read subjects", "write:subjects" to "Write subjects"))
        assertThat(authorizationCode.extensions).containsOnlyKeys("x-pkce")

        val deviceAuthorization = flows.values.last()
        assertThat(deviceAuthorization.deviceAuthorizationUrl).isEqualTo("https://auth.example.com/device")
        assertThat(deviceAuthorization.tokenUrl).isEqualTo("https://auth.example.com/token")
    }

    @Test
    fun `preserves unknown flows and gates OpenAPI 3_2 flow semantics`() {
        val flows = oauthFlows(openApi.replace("openapi: 3.2.0", "openapi: 3.1.2"))
        val deviceAuthorization = flows.values.last()

        assertThat(deviceAuthorization.type)
            .isEqualTo(SourceOAuthFlowType.Unrecognised("deviceAuthorization"))
        assertThat(deviceAuthorization.deviceAuthorizationUrl).isNull()
    }

    private fun oauthFlows(input: String): SourceOAuthFlows =
        SourceOpenApiDocumentParser
            .parse(input)
            .operations
            .reusableSecuritySchemes
            .getValue("OAuth")
            .flows!!

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          securitySchemes:
            OAuth:
              type: oauth2
              flows:
                implicit:
                  authorizationUrl: https://auth.example.com/authorize
                  scopes: {}
                password:
                  tokenUrl: https://auth.example.com/token
                  scopes: {}
                clientCredentials:
                  tokenUrl: https://auth.example.com/token
                  scopes: {}
                authorizationCode:
                  authorizationUrl: https://auth.example.com/authorize
                  tokenUrl: https://auth.example.com/token
                  refreshUrl: https://auth.example.com/refresh
                  scopes:
                    read:subjects: Read subjects
                    write:subjects: Write subjects
                  x-pkce: true
                deviceAuthorization:
                  deviceAuthorizationUrl: https://auth.example.com/device
                  tokenUrl: https://auth.example.com/token
                  scopes: {}
                x-provider: example
        """.trimIndent()
}
