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
}
