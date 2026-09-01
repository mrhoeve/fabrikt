package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NativeModelGeneratorTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates data classes with collection properties from native schemas`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Subject").toString())
            .contains("public val id: String")
            .contains("public val count: Int = 5")
            .contains("public val aliases: List<String>? = null")
            .contains("public val labels: LinkedHashSet<String>? = null")
            .contains("public val attributes: Map<String, Int?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates enums from native schemas`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Status").toString())
            .contains("@JsonValue")
            .contains("IN_PROGRESS(\"in-progress\")")
            .contains("DONE(\"done\")")
            .contains("public fun fromValue(`value`: String): Status? = mapping[value]")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates defaults documentation serialization and validation metadata`(version: String) {
        val subject = generate(version).getValue("Subject").toString()

        assertThat(subject)
            .contains("A generated subject.")
            .contains("@param:JsonProperty(\"id\")")
            .contains("@get:JsonProperty(\"id\")")
            .contains("@get:NotNull")
            .contains("@get:Pattern(regexp = \"[a-z]+\")")
            .contains("@get:Size(", "min = 2", "max = 20")
            .contains("@get:DecimalMin(", "value = \"1\"", "inclusive = true")
            .contains("@get:DecimalMax(", "value = \"10\"")
    }

    private fun generate(version: String) =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(openApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associateBy { it.name }

    private val openApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Status:
              type: string
              enum: [in-progress, done]
            Subject:
              type: object
              description: A generated subject.
              required: [id]
              properties:
                id:
                  type: string
                  pattern: '[a-z]+'
                  minLength: 2
                  maxLength: 20
                count:
                  type: integer
                  default: 5
                  minimum: 1
                  maximum: 10
                aliases:
                  type: array
                  items: { type: string }
                labels:
                  type: array
                  uniqueItems: true
                  items: { type: string }
                attributes:
                  type: object
                  additionalProperties: { type: integer }
        """.trimIndent()
}
