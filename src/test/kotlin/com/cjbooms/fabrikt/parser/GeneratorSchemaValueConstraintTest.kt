package com.cjbooms.fabrikt.parser

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorSchemaValueConstraintTest {
    @Test
    fun `uses const as the intersection of const and enum`() {
        val schema = parseSchema("enum: [1, 2]\nconst: 1.0")

        assertThat(schema.types).isEmpty()
        assertThat(schema.metadata.enumValues).containsExactly(
            JsonNodeFactory.instance.numberNode(1),
            JsonNodeFactory.instance.numberNode(2),
        )
        assertThat(schema.metadata.constValue).isEqualTo(JsonNodeFactory.instance.numberNode(1.0))
        assertThat(schema.valueConstraint())
            .isEqualTo(
                GeneratorSchemaValueConstraint.Allowed(
                    listOf(JsonNodeFactory.instance.numberNode(1.0)),
                ),
            )
    }

    @Test
    fun `identifies disjoint const and enum constraints as impossible`() {
        val schema = parseSchema("enum: [one, two]\nconst: three")

        assertThat(schema.valueConstraint()).isEqualTo(GeneratorSchemaValueConstraint.Impossible)
    }

    @Test
    fun `filters values that do not match declared types`() {
        val schema = parseSchema("type: [string, 'null']\nenum: [one, 2, null]")

        assertThat(schema.types).containsExactly(SourceSchemaType.STRING, SourceSchemaType.NULL)
        assertThat(schema.metadata.enumValues).hasSize(3)
        assertThat(schema.valueConstraint())
            .isEqualTo(
                GeneratorSchemaValueConstraint.Allowed(
                    listOf(
                        JsonNodeFactory.instance.textNode("one"),
                        JsonNodeFactory.instance.nullNode(),
                    ),
                ),
            )
    }

    @Test
    fun `maps JSON values to compatible schema types`() {
        val nodes = JsonNodeFactory.instance

        assertThat(nodes.textNode("value").matchesSourceSchemaType(SourceSchemaType.STRING)).isTrue()
        assertThat(nodes.numberNode(1).matchesSourceSchemaType(SourceSchemaType.INTEGER)).isTrue()
        assertThat(nodes.numberNode(1.0).matchesSourceSchemaType(SourceSchemaType.INTEGER)).isTrue()
        assertThat(nodes.numberNode(1).matchesSourceSchemaType(SourceSchemaType.NUMBER)).isTrue()
        assertThat(nodes.nullNode().matchesSourceSchemaType(SourceSchemaType.NULL)).isTrue()
        assertThat(nodes.numberNode(1).isJsonValueEqualTo(nodes.numberNode(1.0))).isTrue()
    }

    private fun parseSchema(schema: String): GeneratorObjectSchema {
        val document =
            """
            openapi: 3.2.0
            info:
              title: Test
              version: "1.0"
            paths: {}
            components:
              schemas:
                Subject:
            """.trimIndent() + "\n" + schema.prependIndent("      ")
        return SourceOpenApiDocumentParser.parse(document).componentSchemas.getValue("Subject") as GeneratorObjectSchema
    }
}
