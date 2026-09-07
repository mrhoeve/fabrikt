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
}
