package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceRequestBodyParserTest {
    @Test
    fun `models inline request bodies and their media types`() {
        val document = SourceOpenApiDocumentParser.parse(requestBodyOpenApi)
        val requestBody =
            document.operations.paths
                .single()
                .operations
                .single()
                .requestBody!!

        assertThat(requestBody.location).isEqualTo("#/paths/~1subjects/post/requestBody")
        assertThat(requestBody.reference).isNull()
        assertThat(requestBody.description).isEqualTo("Subject payload")
        assertThat(requestBody.required).isTrue()
        assertThat(requestBody.extensions).containsOnlyKeys("x-body")
        assertThat(requestBody.content.map(SourceMediaType::key))
            .containsExactly("application/json", "application/xml")

        val json = requestBody.content[0]
        assertThat(json.location)
            .isEqualTo("#/paths/~1subjects/post/requestBody/content/application~1json")
        assertThat(json.schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1subjects/post/requestBody/content/application~1json/schema",
                ),
            )
        assertThat(json.extensions).containsOnlyKeys("x-media")
        assertThat(requestBody.content[1].schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1subjects/post/requestBody/content/application~1xml/schema",
                ),
            )
    }

    @Test
    fun `models referenced and absent request bodies`() {
        val operations =
            SourceOpenApiDocumentParser
                .parse(referencedRequestBodyOpenApi)
                .operations.paths
                .single()
                .operations

        assertThat(operations[0].requestBody!!.reference)
            .isEqualTo("#/components/requestBodies/CreateSubject")
        assertThat(operations[0].requestBody!!.content).isEmpty()
        assertThat(operations[1].requestBody).isNull()
    }

    private val requestBodyOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              requestBody:
                description: Subject payload
                required: true
                x-body: retained
                content:
                  application/json:
                    schema:
                      type: object
                    x-media: retained
                  application/xml:
                    schema:
                      type: string
              responses: {}
        """.trimIndent()

    private val referencedRequestBodyOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              requestBody:
                ${'$'}ref: '#/components/requestBodies/CreateSubject'
              responses: {}
            get:
              responses: {}
        components:
          requestBodies:
            CreateSubject:
              content:
                application/json:
                  schema:
                    type: object
        """.trimIndent()
}
