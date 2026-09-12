package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
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

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates models referenced through arbitrary external schema pointers`(version: String) {
        writeArbitraryExternalSchemas()

        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated =
                generate(
                    """
                    openapi: $version
                    info:
                      title: Test
                      version: "1.0"
                    paths: {}
                    components:
                      schemas:
                        ExternalSubject:
                          ${'$'}ref: './container.yaml#/schemas/ExternalSubject'
                        Envelope:
                          type: object
                          required: [subject]
                          properties:
                            subject:
                              ${'$'}ref: '#/components/schemas/ExternalSubject'
                    """.trimIndent(),
                )

            assertThat(generated).containsOnlyKeys("ExternalSubject", "ExternalSubjectAddress", "Envelope")
            assertThat(generated.getValue("ExternalSubject"))
                .contains("public val id: String")
                .contains("public val address: ExternalSubjectAddress")
            assertThat(generated.getValue("ExternalSubjectAddress")).contains("public val street: String")
            assertThat(generated.getValue("Envelope")).contains("public val subject: ExternalSubject")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates recursive models from external dynamic references`(version: String) {
        Files.writeString(
            tempDir.resolve("trees.yaml"),
            """
            schemas:
              Tree:
                ${'$'}dynamicAnchor: node
                type: object
                required: [id]
                properties:
                  id: { type: string }
                  children:
                    type: array
                    items:
                      ${'$'}dynamicRef: '#node'
            """.trimIndent(),
        )

        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated =
                generate(
                    """
                    openapi: $version
                    info:
                      title: Test
                      version: "1.0"
                    paths: {}
                    components:
                      schemas:
                        Tree:
                          ${'$'}ref: './trees.yaml#/schemas/Tree'
                    """.trimIndent(),
                )

            assertThat(generated).containsOnlyKeys("Tree")
            assertThat(generated.getValue("Tree"))
                .contains("public val id: String")
                .contains("public val children: List<Tree>? = null")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "Serializable" else "JsonProperty")
        }
    }

    @Test
    fun `keeps component names for identical pointers in different external documents`() {
        writeExternalDefinition("first.yaml", "left")
        writeExternalDefinition("second.yaml", "right")

        val generated =
            generate(
                """
                openapi: 3.2.0
                info:
                  title: Test
                  version: "1.0"
                paths: {}
                components:
                  schemas:
                    First:
                      ${'$'}ref: './first.yaml#/${'$'}defs/Detail'
                    Second:
                      ${'$'}ref: './second.yaml#/${'$'}defs/Detail'
                    Holder:
                      type: object
                      required: [first, second]
                      properties:
                        first:
                          ${'$'}ref: '#/components/schemas/First'
                        second:
                          ${'$'}ref: '#/components/schemas/Second'
                """.trimIndent(),
            )

        assertThat(generated).containsOnlyKeys("First", "Second", "Holder")
        assertThat(generated.getValue("First")).contains("public val left: String")
        assertThat(generated.getValue("Second")).contains("public val right: String")
        assertThat(generated.getValue("Holder"))
            .contains("public val first: First")
            .contains("public val second: Second")
    }

    @Test
    fun `discovers external models nested below composition members`() {
        Files.writeString(
            tempDir.resolve("entry.yaml"),
            """
            type: object
            required: [id]
            properties:
              id: { type: string }
            """.trimIndent(),
        )

        val generated =
            generate(
                """
                openapi: 3.1.2
                info:
                  title: Test
                  version: "1.0"
                paths: {}
                components:
                  schemas:
                    Response:
                      allOf:
                        - type: object
                          required: [entries]
                          properties:
                            entries:
                              type: array
                              items:
                                ${'$'}ref: './entry.yaml'
                """.trimIndent(),
            )

        assertThat(generated).containsOnlyKeys("Response", "ResponseEntries")
        assertThat(generated.getValue("Response")).contains("public val entries: List<ResponseEntries>")
        assertThat(generated.getValue("ResponseEntries")).contains("public val id: String")
    }

    private fun writeExternalDefinition(
        fileName: String,
        propertyName: String,
    ) {
        Files.writeString(
            tempDir.resolve(fileName),
            """
            ${'$'}defs:
              Detail:
                type: object
                required: [$propertyName]
                properties:
                  $propertyName: { type: string }
            """.trimIndent(),
        )
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

    private fun writeArbitraryExternalSchemas() {
        Files.writeString(
            tempDir.resolve("container.yaml"),
            """
            schemas:
              ExternalSubject:
                type: object
                required: [id, address]
                properties:
                  id: { type: string }
                  address:
                    ${'$'}ref: './nested.yaml#/types/Address'
            """.trimIndent(),
        )
        Files.writeString(
            tempDir.resolve("nested.yaml"),
            """
            types:
              Address:
                type: object
                required: [street]
                properties:
                  street: { type: string }
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
