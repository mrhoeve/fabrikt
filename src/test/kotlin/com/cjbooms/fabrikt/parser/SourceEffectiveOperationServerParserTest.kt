package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceEffectiveOperationServerParserTest {
    @Test
    fun `resolves server overrides across operation containers`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations
        val path = operations.paths.single()
        val callbackPath =
            path.operations
                .first { it.method.wireName == "POST" }
                .callbacks
                .single()
                .pathItems
                .single()
        val webhook = operations.webhooks.single()
        val reusablePath = operations.reusablePathItems.getValue("SharedPath")
        val reusableCallbackPath =
            operations.reusableCallbacks
                .getValue("SharedCallback")
                .pathItems
                .single()
        val collected =
            listOf(
                path to path.operations.first { it.method.wireName == "POST" },
                path to path.operations.first { it.method.wireName == "QUERY" },
                path to path.operations.first { it.method.wireName == "pOlL" },
                callbackPath to callbackPath.operations.single(),
                webhook to webhook.operations.single(),
                reusablePath to reusablePath.operations.single(),
                reusableCallbackPath to reusableCallbackPath.operations.single(),
            )

        assertThat(collected.map { (_, operation) -> operation.method.wireName })
            .containsExactly("POST", "QUERY", "pOlL", "PATCH", "PUT", "GET", "DELETE")
        assertThat(collected.map { (pathItem, operation) -> operations.effectiveServersFor(pathItem, operation)?.location })
            .containsExactly(
                "#/paths/~1root/servers",
                "#/paths/~1root/query/servers",
                "#/paths/~1root/servers",
                "#/paths/~1root/post/callbacks/changes/{${'$'}request.body#~1callbackUrl}/servers",
                "#/servers",
                "#/components/pathItems/SharedPath/servers",
                "#/components/callbacks/SharedCallback/{${'$'}request.body#~1callbackUrl}/delete/servers",
            )
        assertThat(operations.effectiveServersFor(reusablePath, reusablePath.operations.single())!!.values)
            .isEmpty()
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        servers:
          - url: https://root.example.com
        paths:
          /root:
            servers:
              - url: https://path.example.com
            post:
              callbacks:
                changes:
                  '{${'$'}request.body#/callbackUrl}':
                    servers:
                      - url: https://callback.example.com
                    patch:
                      responses: {}
              responses: {}
            query:
              servers:
                - url: https://query.example.com
              responses: {}
            additionalOperations:
              pOlL:
                responses: {}
        webhooks:
          changed:
            put:
              responses: {}
        components:
          pathItems:
            SharedPath:
              servers: []
              get:
                responses: {}
          callbacks:
            SharedCallback:
              '{${'$'}request.body#/callbackUrl}':
                delete:
                  servers:
                    - url: https://component-callback.example.com
                  responses: {}
        """.trimIndent()
}
