package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOperationParameterParserTest {
    @Test
    fun `models path and operation parameters with their serialization metadata`() {
        val document = SourceOpenApiDocumentParser.parse(parameterOpenApi)
        val path = document.operations.paths.single()

        val pathParameter = path.parameters.single()
        assertThat(pathParameter.location).isEqualTo("#/paths/~1subjects~1{id}/parameters/0")
        assertThat(pathParameter.name).isEqualTo("id")
        assertThat(pathParameter.placement)
            .isEqualTo(SourceParameterPlacement.Fixed(SourceFixedParameterPlacement.PATH))
        assertThat(pathParameter.description).isEqualTo("Subject identifier")
        assertThat(pathParameter.required).isTrue()
        assertThat(pathParameter.deprecated).isTrue()
        assertThat(pathParameter.allowEmptyValue).isFalse()
        assertThat(pathParameter.style).isEqualTo("simple")
        assertThat(pathParameter.explode).isFalse()
        assertThat(pathParameter.allowReserved).isTrue()
        assertThat(pathParameter.extensions).containsOnlyKeys("x-kotlin-name")
        assertThat(pathParameter.schema)
            .isSameAs(document.schemaEntryPoints.getValue("#/paths/~1subjects~1{id}/parameters/0/schema"))

        val operationParameters = path.operations.single().parameters
        assertThat(operationParameters).hasSize(2)
        assertThat(operationParameters[0].reference).isEqualTo("#/components/parameters/TraceId")
        assertThat(operationParameters[0].name).isNull()
        assertThat(operationParameters[0].schema).isNull()
        assertThat(operationParameters[1].name).isEqualTo("filter")
        assertThat(operationParameters[1].placement)
            .isEqualTo(SourceParameterPlacement.Fixed(SourceFixedParameterPlacement.QUERY))
        assertThat(operationParameters[1].required).isFalse()
        assertThat(operationParameters[1].deprecated).isFalse()
    }

    private val parameterOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects/{id}:
            parameters:
              - name: id
                in: path
                description: Subject identifier
                required: true
                deprecated: true
                allowEmptyValue: false
                style: simple
                explode: false
                allowReserved: true
                schema:
                  type: string
                x-kotlin-name: subjectId
            get:
              parameters:
                - ${'$'}ref: '#/components/parameters/TraceId'
                - name: filter
                  in: query
                  schema:
                    type: object
              responses: {}
        components:
          parameters:
            TraceId:
              name: X-Trace-Id
              in: header
              schema:
                type: string
        """.trimIndent()
}
