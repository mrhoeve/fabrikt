package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceLinkParserTest {
    @Test
    fun `models reusable and response links`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations

        val reusable = operations.reusableLinks.getValue("UserById")
        assertThat(reusable.location).isEqualTo("#/components/links/UserById")
        assertThat(reusable.name).isEqualTo("UserById")
        assertThat(reusable.operationId).isEqualTo("getUser")
        assertThat(reusable.parameters.getValue("path.id").textValue()).isEqualTo("${'$'}response.body#/id")
        assertThat(reusable.requestBody!!.path("audit").booleanValue()).isTrue()
        assertThat(reusable.description).isEqualTo("Fetch the created user")
        assertThat(reusable.server!!.url).isEqualTo("https://api.example.com")
        assertThat(reusable.server!!.location).isEqualTo("#/components/links/UserById/server")
        assertThat(reusable.extensions).containsOnlyKeys("x-relation")

        val links =
            operations.paths
                .single()
                .operations
                .single()
                .responses!!
                .values
                .single()
                .links
        assertThat(links.getValue("createdUser").reference).isEqualTo("#/components/links/UserById")
        assertThat(links.getValue("alternate").operationReference).isEqualTo("#/paths/~1users~1{id}/get")
    }

    private val openApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /users:
            post:
              responses:
                '201':
                  description: created
                  links:
                    createdUser:
                      ${'$'}ref: '#/components/links/UserById'
                    alternate:
                      operationRef: '#/paths/~1users~1{id}/get'
        components:
          links:
            UserById:
              operationId: getUser
              parameters:
                path.id: '${'$'}response.body#/id'
              requestBody:
                audit: true
              description: Fetch the created user
              server:
                url: https://api.example.com
              x-relation: item
        """.trimIndent()
}
