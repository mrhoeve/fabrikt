package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceExternalDocumentationParserTest {
    @Test
    fun `models root and operation external documentation`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations

        val rootDocumentation = operations.externalDocumentation!!
        assertThat(rootDocumentation.location).isEqualTo("#/externalDocs")
        assertThat(rootDocumentation.description).isEqualTo("API handbook")
        assertThat(rootDocumentation.url).isEqualTo("./handbook")
        assertThat(rootDocumentation.extensions).containsOnlyKeys("x-audience")

        val operationDocumentation =
            operations.paths
                .single()
                .operations
                .single()
                .externalDocumentation!!
        assertThat(operationDocumentation.location).isEqualTo("#/paths/~1subjects/get/externalDocs")
        assertThat(operationDocumentation.description).isEqualTo("Subject lookup details")
        assertThat(operationDocumentation.url).isEqualTo("https://docs.example.com/subjects/get")
        assertThat(operationDocumentation.extensions).containsOnlyKeys("x-stability")
    }

    @Test
    fun `ignores absent and non-object external documentation`() {
        val operations =
            SourceOpenApiDocumentParser
                .parse(invalidExternalDocumentationOpenApi)
                .operations

        assertThat(operations.externalDocumentation).isNull()
    }

    private val rootExternalDocumentation =
        """externalDocs:
          description: API handbook
          url: ./handbook
          x-audience: integrators"""

    private val invalidExternalDocumentationOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        externalDocs: ignored
        paths: {}
        """.trimIndent()

    private val openApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        $rootExternalDocumentation
        paths:
          /subjects:
            get:
              externalDocs:
                description: Subject lookup details
                url: https://docs.example.com/subjects/get
                x-stability: stable
              responses: {}
        """.trimIndent()
}
