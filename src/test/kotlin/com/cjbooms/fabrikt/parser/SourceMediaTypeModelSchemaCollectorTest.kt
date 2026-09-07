package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceMediaTypeModelSchemaCollectorTest {
    @Test
    fun `collects complete and item schemas from OpenAPI 32 component media types`() {
        val document = SourceOpenApiDocumentParser.parse(componentMediaTypesOpenApi("3.2.0"))

        assertThat(document.modelSchemas.keys).containsExactly("EventStream", "EventStreamItem", "ItemOnlyItem")
        assertThat(document.modelSchemas.getValue("EventStream").location)
            .isEqualTo("#/components/mediaTypes/EventStream/schema")
        assertThat(document.modelSchemas.getValue("EventStreamItem").location)
            .isEqualTo("#/components/mediaTypes/EventStream/itemSchema")
        assertThat(document.modelSchemas.getValue("ItemOnlyItem").location)
            .isEqualTo("#/components/mediaTypes/ItemOnly/itemSchema")
    }

    @Test
    fun `does not interpret component media types before OpenAPI 32`() {
        val document = SourceOpenApiDocumentParser.parse(componentMediaTypesOpenApi("3.1.2"))

        assertThat(document.modelSchemas).isEmpty()
    }

    @Test
    fun `collects component container media schemas without dropping alternatives`() {
        val document = SourceOpenApiDocumentParser.parse(componentContainerMediaOpenApi)

        assertThat(document.modelSchemas.keys)
            .containsExactly(
                "StreamParameterItem",
                "BatchInput",
                "BatchInputItem",
                "EventPageApplicationJson",
                "EventPageApplicationXml",
            )
    }

    private fun componentMediaTypesOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          mediaTypes:
            EventStream:
              schema:
                type: array
                items:
                  type: object
                  properties:
                    completeId: { type: string }
              itemSchema:
                type: object
                properties:
                  eventId: { type: string }
            ItemOnly:
              itemSchema:
                type: object
                properties:
                  value: { type: string }
        """.trimIndent()

    private val componentContainerMediaOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          parameters:
            StreamParameter:
              name: events
              in: query
              content:
                application/json-seq:
                  itemSchema:
                    type: object
                    properties:
                      eventId: { type: string }
          requestBodies:
            BatchInput:
              content:
                application/json-seq:
                  schema:
                    type: array
                    items:
                      type: object
                      properties:
                        completeId: { type: string }
                  itemSchema:
                    type: object
                    properties:
                      itemId: { type: string }
          responses:
            EventPage:
              description: Events
              content:
                application/json:
                  schema:
                    type: object
                    properties:
                      jsonValue: { type: string }
                application/xml:
                  schema:
                    type: object
                    properties:
                      xmlValue: { type: string }
        """.trimIndent()
}
