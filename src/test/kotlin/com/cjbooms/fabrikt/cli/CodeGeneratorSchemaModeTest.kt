package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths
import java.util.stream.Stream

class CodeGeneratorSchemaModeTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.HTTP_MODELS))
    }

    @Test
    fun `keeps legacy model generation as the default`() {
        assertThat(generate()).isEqualTo(generate(SchemaGenerationMode.LEGACY))
    }

    @Test
    fun `routes model generation through the internal native mode`() {
        assertThat(generate(SchemaGenerationMode.NATIVE).single())
            .contains("public data class Subject(")
            .contains("public val id: String")
            .contains("public val choice: Any? = null")
            .contains("public val tuple: List<Any?>? = null")
    }

    @ParameterizedTest
    @MethodSource("nativeValueConstraintConfigurations")
    fun `routes native value constraints through supported serialization libraries`(
        version: String,
        serializationLibrary: SerializationLibrary,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.HTTP_MODELS),
            serializationLibrary = serializationLibrary,
        )

        val generated = generate(SchemaGenerationMode.NATIVE, valueConstrainedOpenApi.replace("VERSION", version)).joinToString("\n")

        assertThat(generated)
            .contains("public enum class SubjectMode(")
            .contains("public val mode: SubjectMode = SubjectMode.FIXED")
            .contains("public val count: Int = 1")
        if (serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            assertThat(generated)
                .contains("import kotlinx.serialization.Serializable")
                .contains("public val choice: JsonElement?")
        } else {
            assertThat(generated)
                .contains("import com.fasterxml.jackson.`annotation`.JsonProperty")
                .contains("public val choice: Any?")
        }
    }

    private fun generate(
        mode: SchemaGenerationMode? = null,
        input: String = openApi,
    ): List<String> {
        val packages = Packages("com.example")
        val sourceApi = SourceApi(input)
        val path = Paths.get("")
        val generator =
            if (mode == null) {
                CodeGenerator(packages, sourceApi, path, path)
            } else {
                CodeGenerator(packages, sourceApi, path, path, mode)
            }
        return generator
            .generate()
            .filterIsInstance<KotlinSourceSet>()
            .flatMap { it.files }
            .map { it.toString() }
            .sorted()
    }

    companion object {
        @JvmStatic
        fun nativeValueConstraintConfigurations(): Stream<Arguments> =
            Stream.of("3.1.2", "3.2.0").flatMap { version ->
                SerializationLibrary.entries.stream().map { serializationLibrary ->
                    Arguments.of(version, serializationLibrary)
                }
            }
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [id]
              properties:
                id: { type: string }
                choice:
                  oneOf:
                    - { type: string }
                    - { type: integer }
                    - { type: 'null' }
                tuple:
                  type: array
                  prefixItems:
                    - { type: string }
                    - { type: integer }
                    - { type: 'null' }
                  items: false
        """.trimIndent()

    private val valueConstrainedOpenApi =
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
              required: [choice]
              properties:
                mode:
                  const: fixed
                  default: fixed
                count:
                  enum: [1, 2]
                  default: 1
                choice:
                  enum: [text, 1, null]
        """.trimIndent()
}
