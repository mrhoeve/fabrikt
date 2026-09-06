package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SchemaGenerationMode
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NativeOperationParameterModelGeneratorTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates native models for path and operation parameters`(version: String) {
        val generated = generate(openApi(version))

        assertThat(generated).containsOnlyKeys("SharedState", "Filter", "Entries", "Payload")
        assertThat(generated.getValue("SharedState"))
            .contains("public enum class SharedState(")
            .contains("ACTIVE(\"active\")")
            .contains("INACTIVE(\"inactive\")")
        assertThat(generated.getValue("Filter")).contains("public val `value`: String? = null")
        assertThat(generated.getValue("Entries")).contains("public val id: String")
        assertThat(generated.getValue("Payload")).contains("public val query: String")
    }

    private fun generate(input: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser.parse(input).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun openApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            parameters:
              - name: shared-state
                in: query
                schema:
                  type: string
                  enum: [active, inactive]
            get:
              parameters:
                - name: filter
                  in: query
                  schema:
                    type: object
                    properties:
                      value: { type: string }
                - name: entries
                  in: query
                  schema:
                    type: array
                    items:
                      type: object
                      properties:
                        id: { type: string }
                - name: payload
                  in: query
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          query: { type: string }
              responses:
                '204':
                  description: Success
        """.trimIndent()
}
