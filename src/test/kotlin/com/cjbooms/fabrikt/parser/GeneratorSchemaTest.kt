package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorSchemaTest {
    @Test
    fun `source schemas expose the generator schema contract`() {
        val schema: GeneratorSchema =
            SourceOpenApiDocumentParser
                .parse(
                    """
                    openapi: 3.1.2
                    info:
                      title: Test
                      version: "1.0"
                    paths: {}
                    components:
                      schemas:
                        Root:
                          type: object
                          properties:
                            value:
                              type: string
                          additionalProperties: false
                    """.trimIndent(),
                ).componentSchemas
                .getValue("Root")

        val objectSchema = schema as GeneratorObjectSchema
        assertThat(objectSchema.types).containsExactly(SourceSchemaType.OBJECT)
        assertThat(objectSchema.properties).containsOnlyKeys("value")
        assertThat(objectSchema.properties.getValue("value").identity).isSameAs(
            objectSchema.properties.getValue("value").identity,
        )
        assertThat((objectSchema.additionalProperties as GeneratorBooleanSchema).allowsAnyValue).isFalse()
    }

    @Test
    fun `structurally equal schemas retain distinct identities`() {
        val document =
            SourceOpenApiDocumentParser.parse(
                """
                openapi: 3.1.2
                info:
                  title: Test
                  version: "1.0"
                paths: {}
                components:
                  schemas:
                    First:
                      type: string
                    Second:
                      type: string
                """.trimIndent(),
            )

        assertThat(document.componentSchemas.getValue("First").identity)
            .isNotEqualTo(document.componentSchemas.getValue("Second").identity)
    }
}
