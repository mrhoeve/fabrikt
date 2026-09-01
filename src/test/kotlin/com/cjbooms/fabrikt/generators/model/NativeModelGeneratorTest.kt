package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NativeModelGeneratorTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates data classes with collection properties from native schemas`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Subject").toString()).contains(
            """
            public data class Subject(
              public val id: String,
              public val count: Int? = null,
              public val aliases: List<String>? = null,
              public val labels: LinkedHashSet<String>? = null,
              public val attributes: Map<String, Int?>? = null,
            )
            """.trimIndent(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates enums from native schemas`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Status").toString()).contains(
            """
            public enum class Status(
              public val `value`: String,
            ) {
              IN_PROGRESS("in-progress"),
              DONE("done"),
              ;

              override fun toString(): String = value

              public companion object {
                private val mapping: Map<String, Status> = entries.associateBy(Status::value)

                public fun fromValue(`value`: String): Status? = mapping[value]
              }
            }
            """.trimIndent(),
        )
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
              required: [id]
              properties:
                id: { type: string }
                count: { type: integer }
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
