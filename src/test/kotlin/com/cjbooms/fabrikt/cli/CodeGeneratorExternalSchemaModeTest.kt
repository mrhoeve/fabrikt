package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import com.cjbooms.fabrikt.parser.SchemaGenerationMode as InternalSchemaGenerationMode

class CodeGeneratorExternalSchemaModeTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.HTTP_MODELS))
    }

    @Test
    fun `routes external schemas through native model generation`() {
        val externalFile = tempDir.resolve("subject.yaml")
        Files.writeString(
            externalFile,
            """
            type: object
            required: [id]
            properties:
              id: { type: string }
            """.trimIndent(),
        )
        val documentFile = tempDir.resolve("openapi.yaml")
        val sourceApi =
            SourceApi.create(
                baseApi = openApi,
                apiFragments = emptyList(),
                baseUri = tempDir.toUri(),
                documentUri = documentFile.toUri(),
            )

        val generated =
            CodeGenerator(Packages("com.example"), sourceApi, tempDir, tempDir, InternalSchemaGenerationMode.NATIVE)
                .generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .associate { it.name to it.toString() }

        assertThat(generated).containsOnlyKeys("ExternalSubject", "Envelope")
        assertThat(generated.getValue("ExternalSubject")).contains("public val id: String")
        assertThat(generated.getValue("Envelope")).contains("public val subject: ExternalSubject")
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
