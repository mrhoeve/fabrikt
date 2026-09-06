package com.cjbooms.fabrikt.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourceApiDocumentLocationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `preserves the entry document location for external source references`() {
        val externalFile = tempDir.resolve("external.yaml")
        Files.writeString(externalFile, "type: string")
        val documentFile = tempDir.resolve("openapi.yaml")
        val input =
            """
            openapi: 3.1.2
            info:
              title: Test
              version: "1.0"
            paths: {}
            components:
              schemas:
                External:
                  ${'$'}ref: './external.yaml'
            """.trimIndent()

        val sourceApi =
            SourceApi.create(
                baseApi = input,
                apiFragments = emptyList(),
                baseUri = tempDir.toUri(),
                documentUri = documentFile.toUri(),
            )

        assertThat(sourceApi.parsedDocument.sourceGraph.rootDocument.baseUri).isEqualTo(documentFile.toUri())
        assertThat(sourceApi.parsedDocument.sourceGraph.documentsByUri).containsKey(externalFile.toUri())
    }
}
