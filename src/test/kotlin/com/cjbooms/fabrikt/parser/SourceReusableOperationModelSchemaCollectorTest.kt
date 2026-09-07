package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SourceReusableOperationModelSchemaCollectorTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `collects models from reusable callback components`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(callbackOpenApi(version))
        val callbackLocation = "#/components/callbacks/EventCallback/{${'$'}request.body#~1callbackUrl}/post"

        assertThat(document.modelSchemas.keys)
            .containsExactly("CallbackMode", "ReceiveEventRequest", "ReceiveEvent200Response")
        assertThat(document.modelSchemas.getValue("CallbackMode").location)
            .isEqualTo("$callbackLocation/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("ReceiveEventRequest").location)
            .isEqualTo("$callbackLocation/requestBody/content/application~1json/schema")
        assertThat(document.modelSchemas.getValue("ReceiveEvent200Response").location)
            .isEqualTo("$callbackLocation/responses/200/content/application~1json/schema")
    }

    private fun callbackOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          callbacks:
            EventCallback:
              '{${'$'}request.body#/callbackUrl}':
                post:
                  operationId: receiveEvent
                  parameters:
                    - name: callback-mode
                      in: query
                      schema:
                        type: string
                        enum: [single, batch]
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [eventId]
                          properties:
                            eventId: { type: string }
                  responses:
                    '200':
                      description: Received
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [accepted]
                            properties:
                              accepted: { type: boolean }
        """.trimIndent()
}
