package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchemaGenerationModeTest {
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
}
