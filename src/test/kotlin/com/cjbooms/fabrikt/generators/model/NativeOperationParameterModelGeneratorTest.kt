package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SchemaGenerationMode
import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import com.cjbooms.fabrikt.util.ModelNameRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class NativeOperationParameterModelGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
        ModelNameRegistry.clear()
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

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `preserves legacy output for inline enum parameters`(version: String) {
        val input = enumParameterOpenApi(version)
        val sourceApi = SourceApi(input)
        val legacy = ModelGenerator(Packages("com.example"), sourceApi).generate().files.associate { it.name to it.toString() }
        ModelNameRegistry.clear()

        assertThat(generate(input)).isEqualTo(legacy)
    }

    @Test
    fun `generates operation parameter models from external schemas`() {
        Files.writeString(
            tempDir.resolve("filter.yaml"),
            """
            type: object
            required: [query]
            properties:
              query: { type: string }
            """.trimIndent(),
        )
        val parsed =
            OpenApiDocumentParser.parse(
                input = externalParameterOpenApi,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        val generated =
            NativeModelGenerator("com.example")
                .generate(GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)))
                .files
                .associate { it.name to it.toString() }

        assertThat(generated).containsOnlyKeys("ExternalFilter")
        assertThat(generated.getValue("ExternalFilter")).contains("public val query: String")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `keeps operation parameter models portable across serializers`(version: String) {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generate(openApi(version))

            assertThat(generated.getValue("Filter"))
                .contains("public val `value`: String? = null")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "SerialName" else "JsonProperty")
            assertThat(generated.getValue("SharedState")).contains("ACTIVE(\"active\")")
        }
    }

    @Test
    fun `allocates distinct models for duplicate inline parameter names`() {
        val generated = generate(duplicateParameterOpenApi)

        assertThat(generated).containsOnlyKeys("State", "StateExtra")
        assertThat(generated.getValue("State")).contains("ACTIVE(\"active\")")
        assertThat(generated.getValue("StateExtra")).contains("PENDING(\"pending\")")
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

    private fun enumParameterOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              parameters:
                - name: subject-state
                  in: query
                  schema:
                    type: string
                    enum: [active, inactive]
              responses:
                '204':
                  description: Success
        """.trimIndent()

    private val externalParameterOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              parameters:
                - name: external-filter
                  in: query
                  schema:
                    ${'$'}ref: './filter.yaml'
              responses:
                '204':
                  description: Success
        """.trimIndent()

    private val duplicateParameterOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              parameters:
                - name: state
                  in: query
                  schema:
                    type: string
                    enum: [active]
              responses:
                '204':
                  description: Success
            post:
              parameters:
                - name: state
                  in: query
                  schema:
                    type: string
                    enum: [pending]
              responses:
                '204':
                  description: Success
        """.trimIndent()
}
