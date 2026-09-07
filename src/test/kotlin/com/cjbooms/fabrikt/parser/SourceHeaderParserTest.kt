package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceHeaderParserTest {
    @Test
    fun `models response headers with schema content and reference variants`() {
        val document = SourceOpenApiDocumentParser.parse(headerOpenApi)
        val headers =
            document.operations.paths
                .single()
                .operations
                .single()
                .responses!!
                .values
                .single()
                .headers

        assertThat(headers.keys).containsExactly("X-Rate-Limit", "X-Payload", "X-Alias", "Content-Type")
        val rateLimit = headers.getValue("X-Rate-Limit")
        assertThat(rateLimit.location)
            .isEqualTo("#/paths/~1subjects/get/responses/200/headers/X-Rate-Limit")
        assertThat(rateLimit.description).isEqualTo("Remaining requests")
        assertThat(rateLimit.required).isTrue()
        assertThat(rateLimit.deprecated).isTrue()
        assertThat(rateLimit.allowEmptyValue).isFalse()
        assertThat(rateLimit.style).isEqualTo("simple")
        assertThat(rateLimit.explode).isFalse()
        assertThat(rateLimit.extensions).containsOnlyKeys("x-header")
        assertThat(rateLimit.schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1subjects/get/responses/200/headers/X-Rate-Limit/schema",
                ),
            )

        val payload = headers.getValue("X-Payload")
        assertThat(payload.schema).isNull()
        assertThat(payload.content.single().schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1subjects/get/responses/200/headers/X-Payload/content/application~1json/schema",
                ),
            )
        assertThat(headers.getValue("X-Alias").reference).isEqualTo("#/components/headers/Trace")
        assertThat(headers).containsKey("Content-Type")
    }

    @Test
    fun `models reusable component headers`() {
        val document = SourceOpenApiDocumentParser.parse(headerOpenApi)

        assertThat(document.operations.reusableHeaders).containsOnlyKeys("Trace")
        val header = document.operations.reusableHeaders.getValue("Trace")
        assertThat(header.location).isEqualTo("#/components/headers/Trace")
        assertThat(header.name).isEqualTo("Trace")
        assertThat(header.schema)
            .isSameAs(document.schemaEntryPoints.getValue("#/components/headers/Trace/schema"))
    }

    private val headerOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              responses:
                '200':
                  description: Subject
                  headers:
                    X-Rate-Limit:
                      description: Remaining requests
                      required: true
                      deprecated: true
                      allowEmptyValue: false
                      style: simple
                      explode: false
                      schema:
                        type: integer
                      x-header: retained
                    X-Payload:
                      content:
                        application/json:
                          schema:
                            type: object
                    X-Alias:
                      ${'$'}ref: '#/components/headers/Trace'
                    Content-Type:
                      schema:
                        type: string
        components:
          headers:
            Trace:
              schema:
                type: string
        """.trimIndent()
}
