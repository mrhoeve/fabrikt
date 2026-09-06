package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SchemaGenerationModeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `selects legacy schemas without reparsing the document`() {
        val parsed = OpenApiDocumentParser.parse(openApi30)
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.LEGACY)

        assertThat(document.version?.value).isEqualTo("3.0.4")
        assertThat(document.componentSchemas).containsOnlyKeys("Subject", "Value")
        assertThat(document.componentSchemas.values).allMatch { it is GeneratorObjectSchema && it !is SourceSchema }
    }

    @Test
    fun `selects native schemas without passing through Kaizen`() {
        val parsed = OpenApiDocumentParser.parse(openApi31)
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val value = document.componentSchemas.getValue("Value") as GeneratorObjectSchema

        assertThat(document.version?.value).isEqualTo("3.1.2")
        assertThat(document.componentSchemas.values).allMatch { it is SourceSchema }
        assertThat(value.types).containsExactly(SourceSchemaType.STRING, SourceSchemaType.INTEGER, SourceSchemaType.NULL)
        assertThat(GeneratorSchemaTypeClassifier.classify(value))
            .isEqualTo(
                GeneratorSchemaTypeClassification.MultiType(
                    linkedSetOf(OasType.Text, OasType.Integer),
                    nullable = true,
                ),
            )

        val subject = document.componentSchemas.getValue("Subject") as GeneratorObjectSchema
        assertThat(document.resolve(subject.properties.getValue("value"))).isSameAs(value)
    }

    @Test
    fun `legacy and native modes classify basic OpenAPI 3_0 schemas equally`() {
        val parsed = OpenApiDocumentParser.parse(openApi30)
        val legacy = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.LEGACY)
        val native = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)

        assertThat(legacy.componentSchemas.keys).containsExactlyInAnyOrderElementsOf(native.componentSchemas.keys)
        legacy.componentSchemas.keys.forEach { name ->
            assertThat(GeneratorSchemaTypeClassifier.classify(legacy.componentSchemas.getValue(name)))
                .isEqualTo(GeneratorSchemaTypeClassifier.classify(native.componentSchemas.getValue(name)))
        }

        val legacySubject = legacy.componentSchemas.getValue("Subject") as GeneratorObjectSchema
        assertThat(legacy.resolve(legacySubject.properties.getValue("value")))
            .isSameAs(legacySubject.properties.getValue("value"))
    }

    @Test
    fun `native OpenAPI 3_0 resolution ignores reference siblings`() {
        val document = OpenApiDocumentParser.parse(referenceSiblingOpenApi("3.0.4")).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val subject = document.componentSchemas.getValue("Subject") as GeneratorObjectSchema
        val value = document.componentSchemas.getValue("Value")

        assertThat(document.resolve(subject.properties.getValue("value"))).isSameAs(value)
    }

    @Test
    fun `native OpenAPI 3_1 resolution composes reference siblings with their target`() {
        val document = OpenApiDocumentParser.parse(referenceSiblingOpenApi("3.1.2")).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val subject = document.componentSchemas.getValue("Subject") as GeneratorObjectSchema
        val value = document.componentSchemas.getValue("Value")
        val resolved = document.resolve(subject.properties.getValue("value")) as GeneratorReferenceSiblingSchema

        assertThat(resolved.referencedSchema).isSameAs(value)
        assertThat(resolved.allOf).startsWith(value)
        assertThat(resolved.metadata.description).isEqualTo("A narrowed value")
        assertThat(resolved.constraints.minLength).isEqualTo(3)
        assertThat(resolved.constraints.maxLength).isEqualTo(8)
        assertThat(resolved.changesGeneratedShape).isFalse()
    }

    @Test
    fun `native reference sibling classification intersects rather than overrides target types`() {
        val document =
            OpenApiDocumentParser
                .parse(
                    """
                    openapi: 3.2.0
                    info:
                      title: Test
                      version: "1.0"
                    paths: {}
                    components:
                      schemas:
                        Text:
                          type: string
                        NullableText:
                          ${'$'}ref: '#/components/schemas/Text'
                          type: [string, 'null']
                        ImpossibleText:
                          ${'$'}ref: '#/components/schemas/Text'
                          type: integer
                    """.trimIndent(),
                ).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)

        assertThat(GeneratorSchemaTypeClassifier.classify(document.resolve(document.componentSchemas.getValue("NullableText"))))
            .isEqualTo(GeneratorSchemaTypeClassification.Resolved(OasType.Text, nullable = false))
        assertThat(GeneratorSchemaTypeClassifier.classify(document.resolve(document.componentSchemas.getValue("ImpossibleText"))))
            .isEqualTo(
                GeneratorSchemaTypeClassification.Unsupported(
                    GeneratorSchemaTypeClassification.Reason.INCONSISTENT_COMPOSITION_TYPES,
                ),
            )
    }

    @Test
    fun `native mode resolves schemas across the complete external document graph`() {
        val nestedFile = tempDir.resolve("nested.yaml")
        Files.writeString(
            nestedFile,
            """
            type: object
            required: [street]
            properties:
              street: { type: string }
            """.trimIndent(),
        )
        val externalFile = tempDir.resolve("external.yaml")
        Files.writeString(
            externalFile,
            """
            type: object
            required: [id, address]
            properties:
              id: { type: string }
              address:
                ${'$'}ref: './nested.yaml'
            """.trimIndent(),
        )
        val parsed =
            OpenApiDocumentParser.parse(
                input = externalReferenceOpenApi,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val external = document.resolve(document.componentSchemas.getValue("External")) as GeneratorObjectSchema
        val address = document.resolve(external.properties.getValue("address")) as GeneratorObjectSchema

        assertThat(external.properties).containsKeys("id", "address")
        assertThat(address.properties).containsKey("street")
    }

    private fun referenceSiblingOpenApi(version: String) =
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
                value:
                  ${'$'}ref: '#/components/schemas/ValueAlias'
                  description: A narrowed value
                  minLength: 3
            ValueAlias:
              ${'$'}ref: '#/components/schemas/Value'
            Value:
              type: string
              minLength: 2
              maxLength: 8
        """.trimIndent()

    private val openApi30 =
        """
        openapi: 3.0.4
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              properties:
                value:
                  ${'$'}ref: '#/components/schemas/Value'
            Value:
              type: string
              nullable: true
        """.trimIndent()

    private val openApi31 =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              properties:
                value:
                  ${'$'}ref: '#/components/schemas/Value'
            Value:
              type: [string, integer, 'null']
        """.trimIndent()

    private val externalReferenceOpenApi =
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
}
