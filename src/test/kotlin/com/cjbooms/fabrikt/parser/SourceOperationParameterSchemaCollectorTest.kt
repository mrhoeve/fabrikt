package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
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
}
