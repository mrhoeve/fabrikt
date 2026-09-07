package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class NativeComponentSchemaModelGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates native models from named component containers`(version: String) {
        val generated = generate(openApi(version))

        assertThat(generated).containsOnlyKeys("Subject", "Filter", "CreateSubject", "SubjectResponse")
        assertThat(generated.getValue("Filter")).contains("public val query: String")
        assertThat(generated.getValue("CreateSubject")).contains("public val name: String")
        assertThat(generated.getValue("SubjectResponse")).contains("public val id: String")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `preserves legacy output for named component container models`(version: String) {
        val input = openApi(version)
        val sourceApi = SourceApi(input)
        val legacy = ModelGenerator(Packages("com.example"), sourceApi).generate().files.associate { it.name to it.toString() }

        assertThat(generate(input)).isEqualTo(legacy)
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `keeps component container models portable across serializers`(version: String) {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generate(openApi(version))

            assertThat(generated).containsOnlyKeys("Subject", "Filter", "CreateSubject", "SubjectResponse")
            assertThat(generated.getValue("CreateSubject"))
                .contains("public val name: String")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "SerialName" else "JsonProperty")
        }
    }

    @Test
    fun `generates named component container models from external schemas`() {
        Files.writeString(
            tempDir.resolve("payload.yaml"),
            """
            type: object
            required: [id]
            properties:
              id: { type: string }
            """.trimIndent(),
        )
        val parsed =
            OpenApiDocumentParser.parse(
                input = externalRequestBodyOpenApi,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        val generated =
            NativeModelGenerator("com.example")
                .generate(GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)))
                .files
                .associate { it.name to it.toString() }

        assertThat(generated).containsOnlyKeys("CreateSubject")
        assertThat(generated.getValue("CreateSubject")).contains("public val id: String")
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
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              properties:
                value: { type: string }
          parameters:
            Filter:
              name: filter
              in: query
              required: true
              schema:
                type: object
                required: [query]
                properties:
                  query: { type: string }
          requestBodies:
            CreateSubject:
              content:
                application/json:
                  schema:
                    type: object
                    required: [name]
                    properties:
                      name: { type: string }
          responses:
            SubjectResponse:
              description: Subject
              content:
                application/json:
                  schema:
                    type: object
                    required: [id]
                    properties:
                      id: { type: string }
        """.trimIndent()

    private val externalRequestBodyOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          requestBodies:
            CreateSubject:
              content:
                application/json:
                  schema:
                    ${'$'}ref: './payload.yaml'
        """.trimIndent()
}
