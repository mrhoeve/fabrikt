package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.toGeneratorOperationDocument
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GeneratorSequentialMultipartBodyTest {
    @Test
    fun `maps positional schemas and encodings by index`() {
        val context = context(sequentialMultipartOpenApi)
        val body = context.multipartBody(context.operations.paths.single().operations.single())

        assertThat(body).isInstanceOf(GeneratorMultipartBody.Sequential::class.java)
        body as GeneratorMultipartBody.Sequential
        assertThat(body.mediaType.key).isEqualTo("multipart/mixed")
        assertThat(body.minimumPartCount).isEqualTo(1)
        assertThat(body.maximumPartCount).isEqualTo(4)
        assertThat(body.streaming).isFalse()
        assertThat(body.prefixParts).hasSize(2)
        assertThat(body.prefixParts[0].index).isZero()
        assertThat(body.prefixParts[0].required).isTrue()
        assertThat(body.prefixParts[0].schema?.location).endsWith("/schema/prefixItems/0")
        assertThat(body.prefixParts[0].encoding?.contentType).isEqualTo("application/json")
        assertThat(body.prefixParts[1].required).isFalse()
        assertThat(body.prefixParts[1].schema?.location).endsWith("/schema/prefixItems/1")
        assertThat(body.prefixParts[1].encoding?.contentType).isEqualTo("image/png")
        assertThat(body.remainingPart?.index).isNull()
        assertThat(body.remainingPart?.required).isFalse()
        assertThat(body.remainingPart?.schema?.location).endsWith("/schema/items")
        assertThat(body.remainingPart?.encoding?.contentType).isEqualTo("text/plain")
    }

    @Test
    fun `models streaming multipart item schemas`() {
        val context = context(streamingMultipartOpenApi)
        val body = context.multipartBody(context.operations.paths.single().operations.single())

        assertThat(body).isInstanceOf(GeneratorMultipartBody.Sequential::class.java)
        body as GeneratorMultipartBody.Sequential
        assertThat(body.streaming).isTrue()
        assertThat(body.prefixParts).isEmpty()
        assertThat(body.remainingPart?.schema?.location).endsWith("/itemSchema")
        assertThat(body.remainingPart?.encoding?.contentType).isEqualTo("image/jpeg")
    }

    @Test
    fun `rejects positional encoding without an item schema or array schema`() {
        val context = context(invalidSequentialMultipartOpenApi)

        assertThatThrownBy { context.multipartBody(context.operations.paths.single().operations.single()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires an array schema")
    }

    private fun context(openApi: String): GeneratorEndpointContext {
        val document = OpenApiDocumentParser.parse(openApi)
        return GeneratorEndpointContext(
            document.toGeneratorOperationDocument(SchemaGenerationMode.NATIVE),
            document.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
            "com.example",
        )
    }

    private val sequentialMultipartOpenApi =
        """
        openapi: 3.2.0
        info: { title: Sequential multipart, version: "1.0" }
        paths:
          /documents:
            post:
              requestBody:
                content:
                  multipart/mixed:
                    schema:
                      type: array
                      minItems: 1
                      maxItems: 4
                      prefixItems:
                        - type: object
                          properties:
                            title: { type: string }
                        - {}
                      items: { type: string }
                    prefixEncoding:
                      - contentType: application/json
                      - contentType: image/png
                    itemEncoding:
                      contentType: text/plain
              responses:
                '204': { description: Accepted }
        """.trimIndent()

    private val streamingMultipartOpenApi =
        """
        openapi: 3.2.0
        info: { title: Streaming multipart, version: "1.0" }
        paths:
          /images:
            post:
              requestBody:
                content:
                  multipart/mixed:
                    itemSchema: {}
                    itemEncoding:
                      contentType: image/jpeg
              responses:
                '204': { description: Accepted }
        """.trimIndent()

    private val invalidSequentialMultipartOpenApi =
        """
        openapi: 3.2.0
        info: { title: Invalid sequential multipart, version: "1.0" }
        paths:
          /documents:
            post:
              requestBody:
                content:
                  multipart/mixed:
                    schema: { type: object }
                    prefixEncoding:
                      - contentType: application/json
              responses:
                '204': { description: Accepted }
        """.trimIndent()
}
