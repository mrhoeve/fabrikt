package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SchemaGenerationMode
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class NativeOperationBodyModelGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates native models for inline operation bodies`(version: String) {
        val generated = generate(operationOpenApi(version))

        assertThat(generated)
            .containsOnlyKeys(
                "CreateSubjectRequest",
                "CreateSubjectRequestAddress",
                "CreateSubject201Response",
                "GetSubjectsId200Response",
            )
        assertThat(generated.getValue("CreateSubjectRequest"))
            .contains("public val name: String")
            .contains("public val address: CreateSubjectRequestAddress")
        assertThat(generated.getValue("CreateSubjectRequestAddress")).contains("public val street: String")
        assertThat(generated.getValue("CreateSubject201Response")).contains("public val id: String")
        assertThat(generated.getValue("GetSubjectsId200Response")).contains("public val id: String")
    }

    @Test
    fun `generates distinct native models for operation media types`() {
        val generated = generate(multipleMediaOpenApi)

        assertThat(generated)
            .containsOnlyKeys(
                "SearchSubjectsApplicationJsonRequest",
                "SearchSubjectsApplicationXmlRequest",
                "SearchSubjects200ApplicationJsonResponse",
                "SearchSubjects200ApplicationXmlResponse",
            )
        assertThat(generated.getValue("SearchSubjectsApplicationJsonRequest")).contains("public val jsonQuery: String")
        assertThat(generated.getValue("SearchSubjectsApplicationXmlRequest")).contains("public val xmlQuery: String")
        assertThat(generated.getValue("SearchSubjects200ApplicationJsonResponse")).contains("public val jsonResult: String")
        assertThat(generated.getValue("SearchSubjects200ApplicationXmlResponse")).contains("public val xmlResult: String")
    }

    @Test
    fun `generates operation body models from external schemas`() {
        Files.writeString(
            tempDir.resolve("request.yaml"),
            """
            type: object
            required: [name]
            properties:
              name: { type: string }
            """.trimIndent(),
        )
        Files.writeString(
            tempDir.resolve("response.yaml"),
            """
            type: object
            required: [id]
            properties:
              id: { type: string }
            """.trimIndent(),
        )
        val parsed =
            OpenApiDocumentParser.parse(
                input = externalOperationOpenApi,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        val generated =
            NativeModelGenerator("com.example")
                .generate(GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)))
                .files
                .associate { it.name to it.toString() }

        assertThat(generated).containsOnlyKeys("CreateSubjectRequest", "CreateSubject201Response")
        assertThat(generated.getValue("CreateSubjectRequest")).contains("public val name: String")
        assertThat(generated.getValue("CreateSubject201Response")).contains("public val id: String")
    }

    private fun generate(input: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser.parse(input).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun operationOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      required: [name, address]
                      properties:
                        name: { type: string }
                        address:
                          type: object
                          required: [street]
                          properties:
                            street: { type: string }
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [id]
                        properties:
                          id: { type: string }
          /subjects/{id}:
            get:
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [id]
                        properties:
                          id: { type: string }
        """.trimIndent()

    private val multipleMediaOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            query:
              operationId: searchSubjects
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        jsonQuery: { type: string }
                  application/xml:
                    schema:
                      type: object
                      properties:
                        xmlQuery: { type: string }
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          jsonResult: { type: string }
                    application/xml:
                      schema:
                        type: object
                        properties:
                          xmlResult: { type: string }
        """.trimIndent()

    private val externalOperationOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              requestBody:
                content:
                  application/json:
                    schema:
                      ${'$'}ref: './request.yaml'
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: './response.yaml'
        """.trimIndent()
}
