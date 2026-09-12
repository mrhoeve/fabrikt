package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.YamlUtils
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LegacyGeneratorSchemaAdapterTest {
    @Test
    fun `adapts legacy schemas to the generator contract`() {
        val legacySchema = YamlUtils.parseOpenApi(openApi).schemas.getValue("Subject")
        val schema = LegacyGeneratorSchemaAdapter().adapt(legacySchema)

        assertThat(schema.types).containsExactly(SourceSchemaType.OBJECT)
        assertThat(schema.canonicalReference).endsWith("/components/schemas/Subject")
        assertThat(schema.metadata.title).isEqualTo("Subject title")
        assertThat(schema.metadata.description).isEqualTo("Subject description")
        assertThat(schema.metadata.extensions).containsEntry(
            "x-generator-hint",
            JsonNodeFactory.instance.textNode("preserved"),
        )
        assertThat(schema.requiredProperties).containsExactly("value")
        assertThat(schema.discriminator).isEqualTo(
            SourceSchemaDiscriminator("kind", mapOf("subject" to "#/components/schemas/Subject")),
        )
        assertThat(schema.constraints.minimum).isEqualTo(SourceSchemaBound(BigDecimal("1.5"), true))
        assertThat(schema.constraints.maximum).isEqualTo(SourceSchemaBound(BigDecimal("9.5"), false))
        assertThat(schema.properties).containsOnlyKeys("kind", "value")
        assertThat((schema.additionalProperties as GeneratorBooleanSchema).allowsAnyValue).isFalse()
    }

    @Test
    fun `reuses adapters and identities throughout the legacy graph`() {
        val legacySchema = YamlUtils.parseOpenApi(openApi).schemas.getValue("Subject")
        val adapter = LegacyGeneratorSchemaAdapter()

        val first = adapter.adapt(legacySchema)
        val second = adapter.adapt(legacySchema)

        assertThat(first).isSameAs(second)
        assertThat(first.identity).isSameAs(second.identity)
        assertThat(first.properties.getValue("value")).isSameAs(second.properties.getValue("value"))
    }

    private val openApi =
        """
        openapi: 3.0.4
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              title: Subject title
              description: Subject description
              type: object
              required: [value]
              minimum: 1.5
              exclusiveMinimum: true
              maximum: 9.5
              exclusiveMaximum: false
              x-generator-hint: preserved
              discriminator:
                propertyName: kind
                mapping:
                  subject: '#/components/schemas/Subject'
              properties:
                kind:
                  type: string
                value:
                  type: string
                  nullable: true
                  default: fallback
                  enum: [first, second]
              additionalProperties: false
        """.trimIndent()
}
