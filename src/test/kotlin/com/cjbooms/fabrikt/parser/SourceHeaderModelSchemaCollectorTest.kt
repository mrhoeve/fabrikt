package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SourceHeaderModelSchemaCollectorTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `collects named component header schemas`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(openApi(version))

        assertThat(document.modelSchemas.keys).containsExactly("RateLimit", "TraceContext")
        assertThat(document.modelSchemas.getValue("RateLimit").location)
            .isEqualTo("#/components/headers/RateLimit/schema")
        assertThat(document.modelSchemas.getValue("TraceContext").location)
            .isEqualTo("#/components/headers/TraceContext/content/application~1json/schema")
    }

    private fun openApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          headers:
            RateLimit:
              schema:
                type: object
                properties:
                  limit: { type: integer }
            TraceContext:
              content:
                application/json:
                  schema:
                    type: object
                    properties:
                      traceId: { type: string }
        """.trimIndent()
}
