package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegacyGeneratorOperationAdapterTest {
    @Test
    fun `adapts legacy endpoints without exposing Kaizen types`() {
        val document = LegacyGeneratorOperationAdapter().adapt(OpenApiDocumentParser.parse(openApi).kaizenModel)

        assertThat(document.basePath).isEqualTo("/v1")
        assertThat(
            document.security!!
                .values
                .single()
                .schemes,
        ).containsKey("oauth")

        val path = document.paths.single()
        assertThat(path.path).isEqualTo("/items/{id}")
        assertThat(path.parameters.single().name).isEqualTo("id")

        val operation = path.operations.single()
        assertThat(operation.method).isEqualTo("get")
        assertThat(operation.operationId).isEqualTo("getItem")
        assertThat(operation.tags).containsExactly("items")
        assertThat(
            operation.security!!
                .values
                .single()
                .schemes
                .getValue("oauth"),
        ).containsExactly("items:read")
        assertThat(operation.parameters.single().schema).isInstanceOf(GeneratorObjectSchema::class.java)
        assertThat(
            operation.responses
                .single()
                .content
                .single()
                .key,
        ).isEqualTo("application/json")
        assertThat(
            operation.responses
                .single()
                .headers
                .getValue("X-Trace")
                .schema,
        ).isInstanceOf(GeneratorObjectSchema::class.java)
        assertThat(operation.extensions).containsOnlyKeys("x-audience")
    }

    private val openApi =
        """
        openapi: 3.0.4
        info:
          title: Test
          version: "1.0"
        servers:
          - url: https://api.example.com/v1/
        security:
          - oauth: []
        paths:
          /items/{id}:
            parameters:
              - name: id
                in: path
                required: true
                schema: { type: string }
            get:
              operationId: getItem
              summary: Get an item
              tags: [items]
              parameters:
                - name: verbose
                  in: query
                  schema: { type: boolean }
              security:
                - oauth: [items:read]
              responses:
                '200':
                  description: ok
                  headers:
                    X-Trace:
                      schema: { type: string }
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Item'
              x-audience: public
        components:
          schemas:
            Item:
              type: object
              properties:
                id: { type: string }
          securitySchemes:
            oauth:
              type: oauth2
              flows:
                clientCredentials:
                  tokenUrl: https://auth.example.com/token
                  scopes:
                    items:read: Read items
        """.trimIndent()
}
