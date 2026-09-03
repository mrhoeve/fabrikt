package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GeneratorArrayItemsTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `distinguishes homogeneous unconstrained and tuple array items`(version: String) {
        val schemas = SourceOpenApiDocumentParser.parse(openApi.replace("VERSION", version)).componentSchemas

        assertThat(schemas.objectSchema("Homogeneous").arrayItems())
            .isEqualTo(GeneratorArrayItems.Homogeneous(schemas.objectSchema("Homogeneous").items!!))
        assertThat(schemas.objectSchema("Unconstrained").arrayItems()).isEqualTo(GeneratorArrayItems.Unconstrained)
        assertThat(schemas.objectSchema("ClosedTuple").arrayItems())
            .isEqualTo(
                GeneratorArrayItems.Tuple(
                    schemas.objectSchema("ClosedTuple").prefixItems,
                    schemas.objectSchema("ClosedTuple").items,
                ),
            )
        assertThat(schemas.objectSchema("OpenTuple").arrayItems())
            .isEqualTo(GeneratorArrayItems.Tuple(schemas.objectSchema("OpenTuple").prefixItems, null))
        assertThat(schemas.objectSchema("TypedTail").arrayItems())
            .isEqualTo(
                GeneratorArrayItems.Tuple(
                    schemas.objectSchema("TypedTail").prefixItems,
                    schemas.objectSchema("TypedTail").items,
                ),
            )
    }

    private fun Map<String, SourceSchema>.objectSchema(name: String): SourceObjectSchema = getValue(name) as SourceObjectSchema

    private val openApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Homogeneous:
              type: array
              items: { type: string }
            Unconstrained:
              type: array
            ClosedTuple:
              type: array
              prefixItems:
                - { type: string }
                - { type: integer }
              items: false
            OpenTuple:
              type: array
              prefixItems:
                - { type: string }
                - { type: integer }
            TypedTail:
              type: array
              prefixItems:
                - { type: string }
              items: { type: integer }
        """.trimIndent()
}
