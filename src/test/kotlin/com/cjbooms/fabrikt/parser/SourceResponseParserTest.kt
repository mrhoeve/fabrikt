package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceResponseParserTest {
    @Test
    fun `models operation responses status ranges and media types in source order`() {
        val document = SourceOpenApiDocumentParser.parse(responseOpenApi)
        val responses =
            document.operations.paths
                .single()
                .operations
                .single()
                .responses!!

        assertThat(responses.location).isEqualTo("#/paths/~1subjects/get/responses")
        assertThat(responses.extensions).containsOnlyKeys("x-responses")
        assertThat(responses.values.map(SourceResponse::key)).containsExactly("2XX", "200", "default")

        val range = responses.values[0]
        assertThat(range.location).isEqualTo("#/paths/~1subjects/get/responses/2XX")
        assertThat(range.description).isEqualTo("Any successful response")
        assertThat(range.extensions).containsOnlyKeys("x-response")
        assertThat(range.content.map(SourceMediaType::key)).containsExactly("application/json", "application/xml")
        assertThat(range.content[0].schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1subjects/get/responses/2XX/content/application~1json/schema",
                ),
            )

        assertThat(responses.values[1].reference).isEqualTo("#/components/responses/Subject")
        assertThat(responses.values[1].content).isEmpty()
        assertThat(responses.values[2].description).isEqualTo("Unexpected error")
    }

    @Test
    fun `distinguishes absent and empty response collections`() {
        val operations =
            SourceOpenApiDocumentParser
                .parse(emptyResponsesOpenApi)
                .operations.paths
                .single()
                .operations

        assertThat(operations[0].responses).isNull()
        assertThat(operations[1].responses).isNotNull()
        assertThat(operations[1].responses!!.values).isEmpty()
    }

    @Test
    fun `models reusable responses and references`() {
        val document = SourceOpenApiDocumentParser.parse(reusableResponseOpenApi)

        assertThat(document.operations.reusableResponses).containsOnlyKeys("Subject~/Response", "Alias")
        val response = document.operations.reusableResponses.getValue("Subject~/Response")
        assertThat(response.location).isEqualTo("#/components/responses/Subject~0~1Response")
        assertThat(response.key).isEqualTo("Subject~/Response")
        assertThat(response.description).isEqualTo("Subject response")
        assertThat(response.content.single().schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/components/responses/Subject~0~1Response/content/application~1json/schema",
                ),
            )
        assertThat(
            document.operations.reusableResponses
                .getValue("Alias")
                .reference,
        ).isEqualTo("#/components/responses/Subject~0~1Response")
    }

    private val responseOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              responses:
                x-responses: retained
                2XX:
                  description: Any successful response
                  x-response: retained
                  content:
                    application/json:
                      schema:
                        type: object
                    application/xml:
                      schema:
                        type: string
                '200':
                  ${'$'}ref: '#/components/responses/Subject'
                default:
                  description: Unexpected error
        components:
          responses:
            Subject:
              description: Subject
        """.trimIndent()

    private val emptyResponsesOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post: {}
            get:
              responses: {}
        """.trimIndent()

    private val reusableResponseOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          responses:
            Subject~/Response:
              description: Subject response
              content:
                application/json:
                  schema:
                    type: object
            Alias:
              ${'$'}ref: '#/components/responses/Subject~0~1Response'
        """.trimIndent()
}
