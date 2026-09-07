package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `collects models from reusable path item components`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(pathItemOpenApi(version))

        assertThat(document.modelSchemas.keys)
            .containsExactly("SharedFilter", "DetailLevel", "GetSubjectRequest", "GetSubject200Response")
        assertThat(document.modelSchemas.getValue("SharedFilter").location)
            .isEqualTo("#/components/pathItems/SubjectOperations/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("DetailLevel").location)
            .isEqualTo("#/components/pathItems/SubjectOperations/get/parameters/0/schema")
        assertThat(document.modelSchemas.getValue("GetSubjectRequest").location)
            .isEqualTo("#/components/pathItems/SubjectOperations/get/requestBody/content/application~1json/schema")
    }

    @Test
    fun `does not interpret reusable path item components before OpenAPI 31`() {
        val document = SourceOpenApiDocumentParser.parse(pathItemOpenApi("3.0.4"))

        assertThat(document.modelSchemas).isEmpty()
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

    private fun pathItemOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          pathItems:
            SubjectOperations:
              parameters:
                - name: shared-filter
                  in: query
                  schema:
                    type: object
                    properties:
                      query: { type: string }
              get:
                operationId: getSubject
                parameters:
                  - name: detail-level
                    in: query
                    schema:
                      type: string
                      enum: [summary, complete]
                requestBody:
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [subjectId]
                        properties:
                          subjectId: { type: string }
                responses:
                  '200':
                    description: Subject
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name]
                          properties:
                            name: { type: string }
        """.trimIndent()
}
