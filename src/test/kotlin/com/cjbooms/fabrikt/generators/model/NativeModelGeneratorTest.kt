package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SchemaGenerationMode
import com.cjbooms.fabrikt.cli.SerializationLibrary
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

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `flattens referenced and inline allOf properties`(version: String) {
        val child = generate(version).getValue("Child").toString()

        assertThat(child)
            .contains("public val baseId: String")
            .contains("public val childName: String")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates oneOf sealed unions and flattens anyOf models`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Pet").toString())
            .contains("public sealed interface Pet")
            .contains("@JsonTypeInfo(")
            .contains("property = \"kind\"")
            .contains("name = \"cat\"")
            .contains("name = \"dog\"")
        assertThat(generated.getValue("PossiblePet").toString())
            .contains("public data class PossiblePet(")
            .contains("public val kind: String")
        assertThat(generated.getValue("Cat").toString()).contains(") : Pet").doesNotContain("PossiblePet")
        assertThat(generated.getValue("Dog").toString()).contains(") : Pet").doesNotContain("PossiblePet")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates deterministically named inline models`(version: String) {
        val generated = generate(version)

        assertThat(generated).containsKeys("SubjectDetail", "SubjectState", "SubjectPets")
        assertThat(generated.getValue("Subject").toString())
            .contains("public val detail: SubjectDetail? = null")
            .contains("public val state: SubjectState? = null")
            .contains("public val pets: List<SubjectPets>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `native source and legacy adapters produce identical shared model output`(version: String) {
        assertThat(generate(version, SchemaGenerationMode.NATIVE))
            .isEqualTo(generate(version, SchemaGenerationMode.LEGACY))
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates safe fallback properties for native multi-type schemas`(version: String) {
        val subject = generateMultiTypes(version)

        assertThat(subject)
            .contains("public val `value`: Any?")
            .contains("public val values: List<Any?>? = null")
            .contains("public val valuesByKey: Map<String, Any?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates serializable Kotlinx fallbacks for native multi-type schemas`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateMultiTypes(version)

        assertThat(subject)
            .contains("public val `value`: JsonElement?")
            .contains("public val values: List<JsonElement?>? = null")
            .contains("public val valuesByKey: Map<String, JsonElement?>? = null")
    }

    private fun generate(
        version: String,
        mode: SchemaGenerationMode = SchemaGenerationMode.NATIVE,
    ) = NativeModelGenerator("com.example")
        .generate(
            GeneratorModelDescriptorBuilder.build(
                OpenApiDocumentParser
                    .parse(openApi.replace("VERSION", version))
                    .toGeneratorSchemaDocument(mode),
            ),
        ).files
        .associateBy { it.name }

    private fun generateMultiTypes(version: String): String =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(multiTypeOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .single { it.name == "Subject" }
            .toString()

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
                detail:
                  type: object
                  properties:
                    note: { type: string }
                state:
                  type: string
                  enum: [new, old]
                pets:
                  type: array
                  items:
                    type: object
                    properties:
                      name: { type: string }
            Base:
              type: object
              required: [baseId]
              properties:
                baseId: { type: string }
            Child:
              allOf:
                - ${'$'}ref: '#/components/schemas/Base'
                - type: object
                  required: [childName]
                  properties:
                    childName: { type: string }
            Cat:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
            Dog:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
            Pet:
              oneOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
              discriminator:
                propertyName: kind
                mapping:
                  cat: '#/components/schemas/Cat'
                  dog: '#/components/schemas/Dog'
            PossiblePet:
              anyOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
        """.trimIndent()

    private val multiTypeOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [value]
              properties:
                value:
                  type: [string, integer, 'null']
                values:
                  type: array
                  items:
                    type: [string, integer, 'null']
                valuesByKey:
                  type: object
                  additionalProperties:
                    type: [string, integer, 'null']
        """.trimIndent()
}
