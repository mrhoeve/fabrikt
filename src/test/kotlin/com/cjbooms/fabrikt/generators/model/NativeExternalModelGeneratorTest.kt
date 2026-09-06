package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SchemaGenerationMode
import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class NativeExternalModelGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates native models from nested external schema documents`(version: String) {
        writeExternalSchemas()

        val generated = generate(openApi(version))

        assertThat(generated).containsOnlyKeys("ExternalSubject", "ExternalSubjectAddress", "Envelope")
        assertThat(generated.getValue("ExternalSubject"))
            .contains("public val id: String")
            .contains("public val address: ExternalSubjectAddress")
        assertThat(generated.getValue("ExternalSubjectAddress"))
            .contains("public val street: String")
        assertThat(generated.getValue("Envelope"))
            .contains("public val subject: ExternalSubject")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `keeps native external models portable across serializers`(version: String) {
        writeExternalSchemas()

        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generate(openApi(version))

            assertThat(generated).containsOnlyKeys("ExternalSubject", "ExternalSubjectAddress", "Envelope")
            assertThat(generated.getValue("ExternalSubject"))
                .contains("public val address: ExternalSubjectAddress")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "SerialName" else "JsonProperty")
        }
    }

    private fun writeExternalSchemas() {
        Files.writeString(
            tempDir.resolve("address.yaml"),
            """
            type: object
            required: [street]
            properties:
              street: { type: string }
            """.trimIndent(),
        )
        Files.writeString(
            tempDir.resolve("subject.yaml"),
            """
            type: object
            required: [id, address]
            properties:
              id: { type: string }
              address:
                ${'$'}ref: './address.yaml'
            """.trimIndent(),
        )
    }

    private fun generate(input: String): Map<String, String> {
        val parsed =
            OpenApiDocumentParser.parse(
                input = input,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        return NativeModelGenerator("com.example")
            .generate(GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)))
            .files
            .associate { it.name to it.toString() }
    }

    private fun openApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            ExternalSubject:
              ${'$'}ref: './subject.yaml'
            Envelope:
              type: object
              required: [subject]
              properties:
                subject:
                  ${'$'}ref: '#/components/schemas/ExternalSubject'
        """.trimIndent()
}
