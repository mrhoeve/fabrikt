package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SourceOperationParameterSchemaCollectorTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `collects named path and operation parameter schemas`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(openApi(version))

        assertThat(document.modelSchemas.keys).containsExactly("SharedState", "Filter", "Entries", "Payload")
        assertThat(document.modelSchemas.getValue("SharedState").location)
            .isEqualTo("#/paths/~1subjects/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("Filter").location)
            .isEqualTo("#/paths/~1subjects/get/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("Entries").location)
            .isEqualTo("#/paths/~1subjects/get/parameters/1/schema")
        assertThat(document.modelSchemas.getValue("Payload").location)
            .isEqualTo("#/paths/~1subjects/get/parameters/2/content/application~1json/schema")
    }

    @Test
    fun `collects parameter schemas from modern operations`() {
        val document = SourceOpenApiDocumentParser.parse(modernOperationsOpenApi)

        assertThat(document.modelSchemas.keys)
            .containsExactly("CallbackFilter", "CopyFilter", "WebhookState")
        assertThat(document.modelSchemas.getValue("CallbackFilter").location)
            .isEqualTo("#/paths/~1subjects/post/callbacks/updates/{${'$'}request.body#~1callbackUrl}/post/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("CopyFilter").location)
            .isEqualTo("#/paths/~1subjects/additionalOperations/copy/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("WebhookState").location)
            .isEqualTo("#/webhooks/subjectChanged/post/parameters/0/schema")
    }

    @Test
    fun `collects sequential parameter item schemas`() {
        val document =
            SourceOpenApiDocumentParser.parse(
                """
                openapi: 3.2.0
                info:
                  title: Test
                  version: "1.0"
                paths:
                  /events:
                    query:
                      parameters:
                        - name: event-stream
                          in: query
                          content:
                            application/json-seq:
                              itemSchema:
                                type: object
                                properties:
                                  eventId: { type: string }
                      responses:
                        '204': { description: Success }
                """.trimIndent(),
            )

        assertThat(document.modelSchemas.keys).containsExactly("EventStreamItem")
    }

    private fun openApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            parameters:
              - name: shared-state
                in: query
                schema:
                  type: string
                  enum: [active, inactive]
            get:
              parameters:
                - name: filter
                  in: query
                  schema:
                    type: object
                    properties:
                      value: { type: string }
                - name: entries
                  in: query
                  schema:
                    type: array
                    items:
                      type: object
                      properties:
                        id: { type: string }
                - name: payload
                  in: query
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          query: { type: string }
              responses:
                '204':
                  description: Success
        """.trimIndent()

    private val modernOperationsOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              callbacks:
                updates:
                  '{${'$'}request.body#/callbackUrl}':
                    post:
                      parameters:
                        - name: callback-filter
                          in: query
                          schema:
                            type: object
                            properties:
                              query: { type: string }
                      responses:
                        '204': { description: Success }
              responses:
                '204': { description: Success }
            additionalOperations:
              copy:
                parameters:
                  - name: copy-filter
                    in: query
                    schema:
                      type: object
                      properties:
                        query: { type: string }
                responses:
                  '204': { description: Success }
        webhooks:
          subjectChanged:
            post:
              parameters:
                - name: webhook-state
                  in: query
                  schema:
                    type: string
                    enum: [active, inactive]
              responses:
                '204': { description: Success }
        """.trimIndent()
}
