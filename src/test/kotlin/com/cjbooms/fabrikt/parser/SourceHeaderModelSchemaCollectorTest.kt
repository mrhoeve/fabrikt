package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `collects component and operation response header schemas`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(responseHeadersOpenApi(version))

        assertThat(document.modelSchemas.keys).containsExactly("ComponentStatus", "RequestId", "ResponseMetadata")
        assertThat(document.modelSchemas.getValue("ComponentStatus").location)
            .isEqualTo("#/components/responses/Accepted/headers/Component-Status/schema")
        assertThat(document.modelSchemas.getValue("RequestId").location)
            .isEqualTo("#/paths/~1subjects/get/responses/200/headers/Request-Id/schema")
        assertThat(document.modelSchemas.getValue("ResponseMetadata").location)
            .isEqualTo("#/paths/~1subjects/get/responses/200/headers/Response-Metadata/content/application~1json/schema")
    }

    @ParameterizedTest
    @ValueSource(strings = ["component", "response"])
    fun `collects sequential header item schemas`(location: String) {
        val componentHeader = location == "component"
        val document = SourceOpenApiDocumentParser.parse(sequentialHeaderOpenApi(componentHeader))

        assertThat(document.modelSchemas.keys).containsExactly("EventStreamItem")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2"])
    fun `collects encoding header schemas`(version: String) {
        val document = SourceOpenApiDocumentParser.parse(encodingHeaderOpenApi(version))

        assertThat(document.modelSchemas.keys).containsExactly("PostUploadsRequest", "Checksum")
        assertThat(document.modelSchemas.getValue("Checksum").location)
            .isEqualTo("#/paths/~1uploads/post/requestBody/content/multipart~1form-data/encoding/file/headers/Checksum/schema")
    }

    @Test
    fun `collects nested and positional OpenAPI 32 encoding header schemas`() {
        val document = SourceOpenApiDocumentParser.parse(nestedEncodingHeaderOpenApi)

        assertThat(document.modelSchemas.keys).containsExactly("MultipartEventsItem", "Item", "Nested", "Prefix")
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

    private fun responseHeadersOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              responses:
                '200':
                  description: Success
                  headers:
                    Request-Id:
                      schema:
                        type: string
                        enum: [primary, secondary]
                    Response-Metadata:
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              traceId: { type: string }
                    Content-Type:
                      schema:
                        type: object
                        properties:
                          ignored: { type: string }
        components:
          responses:
            Accepted:
              description: Accepted
              headers:
                Component-Status:
                  schema:
                    type: object
                    properties:
                      accepted: { type: boolean }
        """.trimIndent()

    private fun sequentialHeaderOpenApi(componentHeader: Boolean): String =
        if (componentHeader) {
            """
            openapi: 3.2.0
            info:
              title: Test
              version: "1.0"
            paths: {}
            components:
              headers:
                Event-Stream:
                  content:
                    application/json-seq:
                      itemSchema:
                        type: object
                        properties:
                          eventId: { type: string }
            """.trimIndent()
        } else {
            """
            openapi: 3.2.0
            info:
              title: Test
              version: "1.0"
            paths:
              /events:
                get:
                  responses:
                    '200':
                      description: Events
                      headers:
                        Event-Stream:
                          content:
                            application/json-seq:
                              itemSchema:
                                type: object
                                properties:
                                  eventId: { type: string }
            """.trimIndent()
        }

    private fun encodingHeaderOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /uploads:
            post:
              requestBody:
                content:
                  multipart/form-data:
                    schema:
                      type: object
                      properties:
                        file: { type: string, format: binary }
                    encoding:
                      file:
                        headers:
                          Checksum:
                            schema:
                              type: object
                              properties:
                                value: { type: string }
              responses:
                '204': { description: Success }
        """.trimIndent()

    private val nestedEncodingHeaderOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          mediaTypes:
            MultipartEvents:
              itemSchema:
                type: object
                properties:
                  value: { type: string }
              itemEncoding:
                headers:
                  Item:
                    schema:
                      type: object
                      properties:
                        itemValue: { type: string }
                encoding:
                  nested:
                    headers:
                      Nested:
                        schema:
                          type: object
                          properties:
                            nestedValue: { type: string }
                prefixEncoding:
                  - headers:
                      Prefix:
                        schema:
                          type: object
                          properties:
                            prefixValue: { type: string }
        """.trimIndent()
}
