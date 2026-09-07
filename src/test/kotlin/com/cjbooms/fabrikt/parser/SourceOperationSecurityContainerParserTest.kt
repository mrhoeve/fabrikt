package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOperationSecurityContainerParserTest {
    @Test
    fun `resolves security inheritance across operation containers`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations
        val pathOperations = operations.paths.single().operations
        val pathOperation = pathOperations.first { it.method.wireName == "POST" }
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
                pathOperations.first { it.method.wireName == "QUERY" },
                pathOperations.first { it.method.wireName == "pOlL" },
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
        assertThat(collected.map { operation -> operations.effectiveSecurityFor(operation)?.values?.map { it.schemes } })
            .containsExactly(
                listOf(mapOf("Global" to emptyList())),
                emptyList(),
                listOf(mapOf("OAuth" to listOf("poll"))),
                listOf(mapOf("Global" to emptyList())),
                listOf(emptyMap()),
                listOf(mapOf("Global" to emptyList())),
                listOf(mapOf("ApiKey" to emptyList(), "MutualTls" to emptyList())),
            )
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        security:
          - Global: []
        paths:
          /root:
            post:
              callbacks:
                changes:
                  '{${'$'}request.body#/callbackUrl}':
                    patch:
                      responses: {}
              responses: {}
            query:
              security: []
              responses: {}
            additionalOperations:
              pOlL:
                security:
                  - OAuth: [poll]
                responses: {}
        webhooks:
          changed:
            put:
              security:
                - {}
              responses: {}
        components:
          pathItems:
            SharedPath:
              get:
                responses: {}
          callbacks:
            SharedCallback:
              '{${'$'}request.body#/callbackUrl}':
                delete:
                  security:
                    - ApiKey: []
                      MutualTls: []
                  responses: {}
        """.trimIndent()
}
