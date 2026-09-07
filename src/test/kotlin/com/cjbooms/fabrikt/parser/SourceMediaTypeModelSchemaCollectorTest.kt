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
}
