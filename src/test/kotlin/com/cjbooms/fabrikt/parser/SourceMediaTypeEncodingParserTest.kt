package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceMediaTypeEncodingParserTest {
    @Test
    fun `models named media type encodings and their headers`() {
        val document = SourceOpenApiDocumentParser.parse(encodedRequestBodyOpenApi)
        val mediaType =
            document.operations.paths
                .single()
                .operations
                .single()
                .requestBody!!
                .content
                .single()

        assertThat(mediaType.encoding).containsOnlyKeys("profileImage")
        val encoding = mediaType.encoding.getValue("profileImage")
        assertThat(encoding.location)
            .isEqualTo("#/paths/~1subjects/post/requestBody/content/multipart~1form-data/encoding/profileImage")
        assertThat(encoding.key).isEqualTo("profileImage")
        assertThat(encoding.contentType).isEqualTo("image/png, image/jpeg")
        assertThat(encoding.style).isEqualTo("form")
        assertThat(encoding.explode).isTrue()
        assertThat(encoding.allowReserved).isFalse()
        assertThat(encoding.extensions).containsOnlyKeys("x-encoding")
        val header = encoding.headers.getValue("X-Image-Id")
        assertThat(header.schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1subjects/post/requestBody/content/multipart~1form-data/encoding/profileImage/headers/X-Image-Id/schema",
                ),
            )
    }

    @Test
    fun `models OpenAPI 3_2 positional and nested encodings`() {
        val document = SourceOpenApiDocumentParser.parse(sequentialEncodingOpenApi)
        val mediaType =
            document.operations.paths
                .single()
                .operations
                .single()
                .requestBody!!
                .content
                .single()

        assertThat(mediaType.prefixEncoding).hasSize(2)
        assertThat(mediaType.prefixEncoding[0].location)
            .isEqualTo("#/paths/~1documents/post/requestBody/content/multipart~1mixed/prefixEncoding/0")
        assertThat(mediaType.prefixEncoding[0].key).isNull()
        assertThat(mediaType.prefixEncoding[0].contentType).isEqualTo("application/json")

        val nested = mediaType.prefixEncoding[1]
        assertThat(nested.contentType).isEqualTo("multipart/mixed")
        assertThat(nested.prefixEncoding.single().contentType).isEqualTo("application/xml")
        assertThat(nested.prefixEncoding.single().location)
            .endsWith("/prefixEncoding/1/prefixEncoding/0")
        assertThat(nested.itemEncoding!!.contentType).isEqualTo("text/plain")

        val itemEncoding = mediaType.itemEncoding!!
        assertThat(itemEncoding.location)
            .isEqualTo("#/paths/~1documents/post/requestBody/content/multipart~1mixed/itemEncoding")
        assertThat(itemEncoding.contentType).isEqualTo("image/*")
        assertThat(itemEncoding.extensions).containsOnlyKeys("x-item")
        assertThat(itemEncoding.headers.getValue("X-Part-Id").schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1documents/post/requestBody/content/multipart~1mixed/itemEncoding/headers/X-Part-Id/schema",
                ),
            )
    }

    @Test
    fun `ignores positional and nested encodings before OpenAPI 3_2`() {
        val document =
            SourceOpenApiDocumentParser.parse(
                sequentialEncodingOpenApi.replace("openapi: 3.2.0", "openapi: 3.1.2"),
            )
        val mediaType =
            document.operations.paths
                .single()
                .operations
                .single()
                .requestBody!!
                .content
                .single()

        assertThat(mediaType.prefixEncoding).isEmpty()
        assertThat(mediaType.itemEncoding).isNull()
        assertThat(document.schemaEntryPoints)
            .doesNotContainKey(
                "#/paths/~1documents/post/requestBody/content/multipart~1mixed/itemEncoding/headers/X-Part-Id/schema",
            )
    }

    private val encodedRequestBodyOpenApi =
        """
        openapi: 3.0.4
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              requestBody:
                content:
                  multipart/form-data:
                    schema:
                      type: object
                      properties:
                        profileImage:
                          type: string
                          format: binary
                    encoding:
                      profileImage:
                        contentType: image/png, image/jpeg
                        style: form
                        explode: true
                        allowReserved: false
                        headers:
                          X-Image-Id:
                            schema:
                              type: string
                        x-encoding: retained
              responses:
                '204':
                  description: Accepted
        """.trimIndent()

    private val sequentialEncodingOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /documents:
            post:
              requestBody:
                content:
                  multipart/mixed:
                    schema:
                      type: array
                      prefixItems:
                        - type: object
                        - type: array
                      items: {}
                    prefixEncoding:
                      - contentType: application/json
                      - contentType: multipart/mixed
                        prefixEncoding:
                          - contentType: application/xml
                        itemEncoding:
                          contentType: text/plain
                    itemEncoding:
                      contentType: image/*
                      headers:
                        X-Part-Id:
                          schema:
                            type: string
                      x-item: retained
              responses:
                '204':
                  description: Accepted
        """.trimIndent()
}
