package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SourceOperationModelSchemaCollectorTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `collects named request and response model schemas from operations`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(operationOpenApi(version))

        assertThat(document.modelSchemas.keys)
            .containsExactly("CreateSubjectRequest", "CreateSubject201Response", "GetSubjectsId200Response")
        assertThat(document.modelSchemas.getValue("CreateSubjectRequest").location)
            .isEqualTo("#/paths/~1subjects/post/requestBody/content/application~1json/schema")
        assertThat(document.modelSchemas.getValue("CreateSubject201Response").location)
            .isEqualTo("#/paths/~1subjects/post/responses/201/content/application~1json/schema")
    }

    @Test
    fun `uses media types to distinguish multiple operation schemas`() {
        val document = SourceOpenApiDocumentParser.parse(multipleMediaOpenApi)

        assertThat(document.modelSchemas.keys)
            .containsExactly(
                "SearchSubjectsApplicationJsonRequest",
                "SearchSubjectsApplicationXmlRequest",
                "SearchSubjects200ApplicationJsonResponse",
                "SearchSubjects200ApplicationXmlResponse",
            )
    }

    @Test
    fun `allocates bounded names when operation models collide with components`() {
        val document =
            SourceOpenApiDocumentParser.parse(
                """
                openapi: 3.1.2
                info:
                  title: Test
                  version: "1.0"
                paths:
                  /subjects:
                    post:
                      operationId: createSubject
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                requestValue: { type: string }
                      responses:
                        '204':
                          description: Created
                components:
                  schemas:
                    CreateSubjectRequest:
                      type: object
                      properties:
                        componentValue: { type: string }
                """.trimIndent(),
            )

        assertThat(document.modelSchemas.keys).containsExactly("CreateSubjectRequest", "CreateSubjectRequestExtra")
    }

    @Test
    fun `collects webhook and additional operation body schemas`() {
        val document = SourceOpenApiDocumentParser.parse(modernOperationsOpenApi)

        assertThat(document.modelSchemas.keys)
            .containsExactly(
                "CopySubjectRequest",
                "CopySubject202Response",
                "NotifySubjectRequest",
                "NotifySubject200Response",
                "SubjectChangedRequest",
                "SubjectChanged204Response",
            )
    }

    @Test
    fun `collects complete and item schemas from sequential media types`() {
        val document = SourceOpenApiDocumentParser.parse(sequentialMediaOpenApi)

        assertThat(document.modelSchemas.keys)
            .containsExactly("StreamEventsRequestItem", "StreamEvents200Response", "StreamEvents200ResponseItem")
        assertThat(document.modelSchemas.getValue("StreamEventsRequestItem").location)
            .isEqualTo("#/paths/~1events/query/requestBody/content/application~1json-seq/itemSchema")
        assertThat(document.modelSchemas.getValue("StreamEvents200Response").location)
            .isEqualTo("#/paths/~1events/query/responses/200/content/application~1json-seq/schema")
        assertThat(document.modelSchemas.getValue("StreamEvents200ResponseItem").location)
            .isEqualTo("#/paths/~1events/query/responses/200/content/application~1json-seq/itemSchema")
    }

    private fun operationOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        name: { type: string }
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id: { type: string }
          /subjects/{id}:
            get:
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id: { type: string }
        """.trimIndent()

    private val multipleMediaOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            query:
              operationId: searchSubjects
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        jsonQuery: { type: string }
                  application/xml:
                    schema:
                      type: object
                      properties:
                        xmlQuery: { type: string }
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          jsonResult: { type: string }
                    application/xml:
                      schema:
                        type: object
                        properties:
                          xmlResult: { type: string }
        """.trimIndent()

    private val modernOperationsOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            additionalOperations:
              copy:
                operationId: copySubject
                requestBody:
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          sourceId: { type: string }
                responses:
                  '202':
                    description: Accepted
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            jobId: { type: string }
                callbacks:
                  notify:
                    '{${'$'}request.body#/callbackUrl}':
                      post:
                        operationId: notifySubject
                        requestBody:
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  notificationId: { type: string }
                        responses:
                          '200':
                            description: Received
                            content:
                              application/json:
                                schema:
                                  type: object
                                  properties:
                                    received: { type: boolean }
        webhooks:
          subjectChanged:
            post:
              operationId: subjectChanged
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        subjectId: { type: string }
              responses:
                '204':
                  description: Accepted
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          accepted: { type: boolean }
        """.trimIndent()

    private val sequentialMediaOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /events:
            query:
              operationId: streamEvents
              requestBody:
                content:
                  application/json-seq:
                    itemSchema:
                      type: object
                      properties:
                        requestId: { type: string }
              responses:
                '200':
                  description: Stream
                  content:
                    application/json-seq:
                      schema:
                        type: array
                        items:
                          type: object
                          properties:
                            completeId: { type: string }
                      itemSchema:
                        type: object
                        properties:
                          eventId: { type: string }
        """.trimIndent()
}
