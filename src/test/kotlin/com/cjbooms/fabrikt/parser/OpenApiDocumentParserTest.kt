package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class OpenApiDocumentParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `preserves the source document before applying Kaizen compatibility transformations`() {
        val input =
            """
            openapi: 3.1.0
            info:
              title: Test
              version: "1.0"
            paths: {}
            components:
              schemas:
                Name:
                  type:
                    - string
                    - "null"
            """.trimIndent()

        val parsedDocument = OpenApiDocumentParser.parse(input)
        val source = parsedDocument.source.root

        assertThat(source.at("/components/schemas/Name/type").map { it.textValue() })
            .containsExactly("string", "null")
        assertThat(parsedDocument.kaizenModel.schemas["Name"]!!.type).isEqualTo("string")
        assertThat(parsedDocument.kaizenModel.schemas["Name"]!!.nullable).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "3.0.4, 3, 0, 4",
        "3.1.2, 3, 1, 2",
        "3.2.0, 3, 2, 0",
    )
    fun `exposes the source OpenAPI version before compatibility transformations`(
        value: String,
        major: Int,
        minor: Int,
        patch: Int,
    ) {
        val input =
            """
            openapi: $value
            info:
              title: Test
              version: "1.0"
            paths: {}
            """.trimIndent()

        val version = OpenApiDocumentParser.parse(input).version

        assertThat(version)
            .isEqualTo(OpenApiVersion(value, major, minor, patch))
    }

    @Test
    fun `uses the supplied base URI for source reference resolution`() {
        val input =
            """
            openapi: 3.1.0
            info:
              title: Test
              version: "1.0"
            paths: {}
            components:
              schemas:
                Name:
                  type: string
                Alias:
                  ${'$'}ref: '#/components/schemas/Name'
            """.trimIndent()
        val baseUri = URI("https://example.test/specs%20with%20spaces/openapi.yaml")

        val source = OpenApiDocumentParser.parse(input, baseUri).source
        val resolution =
            source.schemaReferenceResolutions.getValue("#/components/schemas/Alias") as
                SourceSchemaReferenceResolution.Resolved

        assertThat(source.baseUri).isEqualTo(baseUri)
        assertThat(resolution.uri)
            .isEqualTo(URI("https://example.test/specs%20with%20spaces/openapi.yaml#/components/schemas/Name"))
        assertThat(resolution.target).isSameAs(source.componentSchemas.getValue("Name"))
    }

    @Test
    fun `preserves the complete external source document graph alongside the kaizen model`() {
        val externalFile = tempDir.resolve("external.yaml")
        Files.writeString(
            externalFile,
            """
            type: object
            properties:
              id: { type: string }
            """.trimIndent(),
        )
        val documentUri = tempDir.resolve("openapi.yaml").toUri()
        val parsed =
            OpenApiDocumentParser.parse(
                input =
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
                    """.trimIndent(),
                baseUri = tempDir.toUri(),
                documentUri = documentUri,
            )

        assertThat(parsed.sourceGraph.documentsByUri).containsKeys(documentUri, externalFile.toUri())
        assertThat(parsed.sourceGraph.loadFailures).isEmpty()
        assertThat(parsed.kaizenModel.schemas).containsKey("External")
    }

    @Test
    fun `inlines external operation references before collecting native schemas`() {
        val pathsDirectory = Files.createDirectories(tempDir.resolve("paths"))
        val componentsDirectory = Files.createDirectories(tempDir.resolve("components"))
        Files.writeString(
            pathsDirectory.resolve("subjects.yaml"),
            """
            description: External path item
            get:
              operationId: listSubjects
              parameters:
                - ${'$'}ref: ../components/filter.yaml
              responses:
                '200':
                  ${'$'}ref: ../components/subjects-response.yaml
            """.trimIndent(),
        )
        Files.writeString(
            componentsDirectory.resolve("filter.yaml"),
            """
            name: filter
            in: query
            schema: { type: string }
            """.trimIndent(),
        )
        Files.writeString(
            componentsDirectory.resolve("subjects-response.yaml"),
            """
            description: Subjects
            content:
              application/json:
                schema:
                  ${'$'}ref: ./subject.yaml
            """.trimIndent(),
        )
        val subjectFile = componentsDirectory.resolve("subject.yaml")
        Files.writeString(
            subjectFile,
            """
            type: object
            required: [id]
            properties:
              id: { type: string }
            """.trimIndent(),
        )
        val documentUri = tempDir.resolve("openapi.yaml").toUri()

        val parsed =
            OpenApiDocumentParser.parseSource(
                input =
                    """
                    openapi: 3.1.2
                    info:
                      title: Test
                      version: "1.0"
                    paths:
                      /subjects:
                        ${'$'}ref: paths/subjects.yaml
                        summary: Local summary
                    """.trimIndent(),
                baseUri = tempDir.toUri(),
                documentUri = documentUri,
            )

        val pathItem = parsed.operations.paths.single()
        assertThat(pathItem.summary).isEqualTo("Local summary")
        assertThat(pathItem.description).isEqualTo("External path item")
        assertThat(
            pathItem.operations
                .single()
                .parameters
                .single()
                .name,
        ).isEqualTo("filter")
        assertThat(parsed.source.modelSchemas).isNotEmpty
        val loadedDocumentPaths =
            parsed.sourceGraph.documentsByUri.keys
                .map(URI::getPath)
        assertThat(loadedDocumentPaths).contains(subjectFile.toUri().path)
    }

    @Test
    fun `exposes native operations before parsing the Kaizen compatibility model`() {
        val parsed =
            OpenApiDocumentParser.parse(
                """
                openapi: 3.2.0
                info:
                  title: Test
                  version: "1.0"
                paths:
                  /jobs:
                    query:
                      operationId: queryJobs
                      responses: {}
                    additionalOperations:
                      PURGE:
                        operationId: purgeJobs
                        responses: {}
                """.trimIndent(),
            )

        assertThat(parsed.operations).isSameAs(parsed.source.operations)
        assertThat(
            parsed.operations.paths
                .single()
                .operations
                .map { it.method.wireName },
        ).containsExactly("QUERY", "PURGE")
        assertThat(parsed.kaizenModel.paths).isNotNull()
    }
}
