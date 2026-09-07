package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOperationPayloadContainerParserTest {
    @Test
    fun `models payloads for every OpenAPI 3_2 operation container`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations

        val pathOperation =
            operations.paths
                .single()
                .operations
                .first { it.method.wireName == "POST" }
        val callbackOperation =
            pathOperation.callbacks
                .single()
                .pathItems
                .single()
                .operations
                .single()
        val collected =
            listOf(
                pathOperation,
                operations.paths
                    .single()
                    .operations
                    .first { it.method.wireName == "QUERY" },
                operations.paths
                    .single()
                    .operations
                    .first { it.method.wireName == "pOlL" },
                callbackOperation,
                operations.webhooks
                    .single()
                    .operations
                    .single(),
                operations.reusablePathItems
                    .getValue("SharedPath")
                    .operations
                    .single(),
                operations.reusableCallbacks
                    .getValue("SharedCallback")
                    .pathItems
                    .single()
                    .operations
                    .single(),
            )

        assertThat(collected.map { it.method.wireName })
            .containsExactly("POST", "QUERY", "pOlL", "PATCH", "PUT", "GET", "DELETE")
        assertThat(collected)
            .allSatisfy { operation ->
                assertThat(operation.requestBody).isNotNull
                assertThat(operation.requestBody!!.content.map(SourceMediaType::key))
                    .containsExactly("application/json")
                assertThat(operation.responses!!.values.map(SourceResponse::key)).containsExactly("200")
                assertThat(
                    operation.responses!!
                        .values
                        .single()
                        .content
                        .map(SourceMediaType::key),
                ).containsExactly("application/json")
            }
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /root:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: string
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: string
              callbacks:
                changes:
                  '{${'$'}request.body#/callbackUrl}':
                    patch:
                      requestBody: { required: true, content: { application/json: { schema: { type: string } } } }
                      responses: { '200': { description: ok, content: { application/json: { schema: { type: string } } } } }
            query:
              requestBody: { required: true, content: { application/json: { schema: { type: string } } } }
              responses: { '200': { description: ok, content: { application/json: { schema: { type: string } } } } }
            additionalOperations:
              pOlL:
                requestBody: { required: true, content: { application/json: { schema: { type: string } } } }
                responses: { '200': { description: ok, content: { application/json: { schema: { type: string } } } } }
        webhooks:
          changed:
            put:
              requestBody: { required: true, content: { application/json: { schema: { type: string } } } }
              responses: { '200': { description: ok, content: { application/json: { schema: { type: string } } } } }
        components:
          pathItems:
            SharedPath:
              get:
                requestBody: { required: true, content: { application/json: { schema: { type: string } } } }
                responses: { '200': { description: ok, content: { application/json: { schema: { type: string } } } } }
          callbacks:
            SharedCallback:
              '{${'$'}request.body#/callbackUrl}':
                delete:
                  requestBody: { required: true, content: { application/json: { schema: { type: string } } } }
                  responses: { '200': { description: ok, content: { application/json: { schema: { type: string } } } } }
        """.trimIndent()
}
