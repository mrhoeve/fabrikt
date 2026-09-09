package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorOperationEncodingAdapterTest {
    @Test
    fun `preserves named media type encoding semantics in native mode`() {
        val document = OpenApiDocumentParser.parse(openApi).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)
        val encoding =
            document.paths
                .first { it.path == "/search" }
                .operations
                .single()
                .requestBody!!
                .content
                .single()
                .encoding
                .getValue("filters")

        assertThat(encoding.contentType).isEqualTo("application/json")
        assertThat(encoding.style).isEqualTo("spaceDelimited")
        assertThat(encoding.explode).isFalse()
        assertThat(encoding.allowReserved).isTrue()
        assertThat(encoding.extensions).containsOnlyKeys("x-encoding")
        assertThat(encoding.headers.getValue("X-Encoding-Version").required).isTrue()
        assertThat(encoding.headers.getValue("X-Encoding-Version").schema).isNotNull()
        assertThat(encoding.encoding.getValue("nested").contentType).isEqualTo("text/plain")
    }

    @Test
    fun `preserves positional OpenAPI 3_2 media type encodings in native mode`() {
        val document = OpenApiDocumentParser.parse(openApi).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)
        val mediaType =
            document.paths
                .first { it.path == "/events" }
                .operations
                .single()
                .requestBody!!
                .content
                .single()

        assertThat(mediaType.prefixEncoding.map(GeneratorEncoding::contentType))
            .containsExactly("application/json", "image/png")
        assertThat(
            mediaType.prefixEncoding
                .first()
                .headers
                .getValue("X-Event-Type")
                .schema,
        ).isNotNull()
        assertThat(mediaType.itemEncoding?.contentType).isEqualTo("application/octet-stream")
        assertThat(mediaType.itemEncoding?.itemEncoding?.contentType).isEqualTo("application/cbor")
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Generator encoding adapter
          version: "1.0"
        paths:
          /search:
            post:
              requestBody:
                content:
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
                      properties:
                        filters:
                          type: array
                          items: { type: string }
                    encoding:
                      filters:
                        contentType: application/json
                        style: spaceDelimited
                        explode: false
                        allowReserved: true
                        headers:
                          X-Encoding-Version:
                            required: true
                            schema: { type: string }
                        encoding:
                          nested:
                            contentType: text/plain
                        x-encoding: retained
              responses:
                '204': { description: Accepted }
          /events:
            post:
              requestBody:
                content:
                  multipart/mixed:
                    schema:
                      type: array
                      prefixItems:
                        - type: object
                        - type: string
                          contentEncoding: binary
                      items:
                        type: string
                        contentEncoding: binary
                    prefixEncoding:
                      - contentType: application/json
                        headers:
                          X-Event-Type:
                            schema: { type: string }
                      - contentType: image/png
                    itemEncoding:
                      contentType: application/octet-stream
                      itemEncoding:
                        contentType: application/cbor
              responses:
                '204': { description: Accepted }
        """.trimIndent()
}
