package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceServerParserTest {
    @Test
    fun `models root servers and variables`() {
        val servers = SourceOpenApiDocumentParser.parse(openApi).operations.servers!!

        assertThat(servers.location).isEqualTo("#/servers")
        assertThat(servers.values).hasSize(2)

        val templated = servers.values.first()
        assertThat(templated.location).isEqualTo("#/servers/0")
        assertThat(templated.url).isEqualTo("https://{region}.example.com/{version}")
        assertThat(templated.description).isEqualTo("Regional endpoint")
        assertThat(templated.name).isEqualTo("regional")
        assertThat(templated.extensions).containsOnlyKeys("x-tier")
        assertThat(templated.variables.keys).containsExactly("region", "version")

        val region = templated.variables.getValue("region")
        assertThat(region.location).isEqualTo("#/servers/0/variables/region")
        assertThat(region.name).isEqualTo("region")
        assertThat(region.enumValues).containsExactly("eu", "us")
        assertThat(region.defaultValue).isEqualTo("eu")
        assertThat(region.description).isEqualTo("Deployment region")
        assertThat(region.extensions).containsOnlyKeys("x-public")
        assertThat(templated.variables.getValue("version").enumValues).isEmpty()
        assertThat(servers.values.last().url).isEqualTo("/sandbox")
    }

    @Test
    fun `distinguishes absent and empty server declarations`() {
        assertThat(
            SourceOpenApiDocumentParser
                .parse(minimalOpenApi("servers: []"))
                .operations.servers!!
                .values,
        ).isEmpty()
        assertThat(SourceOpenApiDocumentParser.parse(minimalOpenApi("")).operations.servers)
            .isNull()
    }

    @Test
    fun `only models server names for OpenAPI 3_2`() {
        val server =
            SourceOpenApiDocumentParser
                .parse(openApi.replace("openapi: 3.2.0", "openapi: 3.1.2"))
                .operations
                .servers!!
                .values
                .first()

        assertThat(server.name).isNull()
    }

    private val serverDefinitions =
        """servers:
          - url: https://{region}.example.com/{version}
            description: Regional endpoint
            name: regional
            variables:
              region:
                enum: [eu, us]
                default: eu
                description: Deployment region
                x-public: true
              version:
                default: v1
            x-tier: production
          - url: /sandbox"""

    private fun minimalOpenApi(serverDeclaration: String): String =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        $serverDeclaration
        paths: {}
        """.trimIndent()

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        $serverDefinitions
        paths: {}
        """.trimIndent()
}
