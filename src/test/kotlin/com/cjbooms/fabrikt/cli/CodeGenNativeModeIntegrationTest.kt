package com.cjbooms.fabrikt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class CodeGenNativeModeIntegrationTest {
    @Test
    fun `generates native models server contracts and clients through the CLI`(
        @TempDir directory: Path,
    ) {
        val api = directory.resolve("api.yaml")
        val output = directory.resolve("generated")
        Files.writeString(api, openApi)

        CodeGen.main(
            arrayOf(
                "--api-file",
                api.toString(),
                "--output-directory",
                output.toString(),
                "--base-package",
                "com.example",
                "--targets",
                "http_models",
                "--targets",
                "controllers",
                "--targets",
                "client",
                "--http-controller-target",
                "spring",
                "--http-client-target",
                "spring_http_interface",
                "--schema-generation-mode",
                "native",
            ),
        )

        val sources =
            Files
                .walk(output)
                .use { paths -> paths.filter { it.toString().endsWith(".kt") }.map(Path::readText).toList() }
                .joinToString("\n")
        assertThat(sources)
            .contains("public data class Subject(")
            .contains("public val id: String? = null")
            .contains("public val label: String?")
            .contains("public val secret: String? = null")
            .contains("public data class SubjectRequest(")
            .contains("public val secret: String")
            .contains("public data class SubjectResponse(")
            .contains("public val id: String")
            .contains("public interface SubjectsController")
            .contains("public interface SubjectsClient")
            .contains("public fun createSubject(")
            .contains("subject: Subject")
            .contains("): Subject")
    }

    private val openApi =
        """
        openapi: 3.1.1
        info:
          title: Native CLI
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Subject' }
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Subject' }
        components:
          schemas:
            Subject:
              type: object
              required: [id, secret]
              properties:
                id:
                  type: string
                  readOnly: true
                label:
                  type: [string, 'null']
                secret:
                  type: string
                  writeOnly: true
        """.trimIndent()
}
