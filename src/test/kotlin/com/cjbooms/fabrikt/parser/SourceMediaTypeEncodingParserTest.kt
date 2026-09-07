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
}
