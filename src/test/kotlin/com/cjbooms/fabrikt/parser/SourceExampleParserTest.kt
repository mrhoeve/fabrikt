package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceExampleParserTest {
    @Test
    fun `models reusable and operation examples`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations

        val reusable = operations.reusableExamples.getValue("Reusable")
        assertThat(reusable.location).isEqualTo("#/components/examples/Reusable")
        assertThat(reusable.name).isEqualTo("Reusable")
        assertThat(reusable.summary).isEqualTo("Reusable example")
        assertThat(reusable.description).isEqualTo("Shared example data")
        assertThat(reusable.dataValue!!.path("id").intValue()).isEqualTo(42)
        assertThat(reusable.serializedValue).isEqualTo("{\"id\":42}")
        assertThat(reusable.extensions).containsOnlyKeys("x-audience")

        val operation =
            operations.paths
                .single()
                .operations
                .single()
        val parameter = operation.parameters.single()
        assertThat(parameter.example!!.textValue()).isEqualTo("short")
        assertThat(
            parameter.examples
                .getValue("long")
                .value!!
                .textValue(),
        ).isEqualTo("expanded")

        val header =
            operation.responses!!
                .values
                .single()
                .headers
                .getValue("X-Result")
        assertThat(header.example!!.intValue()).isEqualTo(1)
        assertThat(
            header.examples
                .getValue("two")
                .value!!
                .intValue(),
        ).isEqualTo(2)

        val mediaType =
            operation.responses!!
                .values
                .single()
                .content
                .single()
        assertThat(mediaType.example!!.path("id").intValue()).isEqualTo(1)
        assertThat(mediaType.examples.getValue("shared").reference).isEqualTo("#/components/examples/Reusable")
        assertThat(mediaType.examples.getValue("external").externalValue).isEqualTo("examples/result.json")
    }

    @Test
    fun `only models OpenAPI 3_2 example fields for OpenAPI 3_2`() {
        val reusable =
            SourceOpenApiDocumentParser
                .parse(openApi.replace("openapi: 3.2.0", "openapi: 3.1.2"))
                .operations
                .reusableExamples
                .getValue("Reusable")

        assertThat(reusable.value).isNull()
        assertThat(reusable.dataValue).isNull()
        assertThat(reusable.serializedValue).isNull()
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /items:
            get:
              parameters:
                - name: mode
                  in: query
                  schema: { type: string }
                  example: short
                  examples:
                    long:
                      value: expanded
              responses:
                '200':
                  description: ok
                  headers:
                    X-Result:
                      schema: { type: integer }
                      example: 1
                      examples:
                        two:
                          value: 2
                  content:
                    application/json:
                      schema: { type: object }
                      example: { id: 1 }
                      examples:
                        shared:
                          ${'$'}ref: '#/components/examples/Reusable'
                        external:
                          externalValue: examples/result.json
        components:
          examples:
            Reusable:
              summary: Reusable example
              description: Shared example data
              dataValue: { id: 42 }
              serializedValue: '{"id":42}'
              x-audience: public
        """.trimIndent()
}
