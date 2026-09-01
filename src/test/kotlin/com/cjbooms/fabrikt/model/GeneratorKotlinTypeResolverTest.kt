package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GeneratorKotlinTypeResolverTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `resolves native schemas to Kotlin types`(version: String) {
        val parsed = OpenApiDocumentParser.parse(openApi.replace("VERSION", version))
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val resolver = GeneratorKotlinTypeResolver(document)

        assertResolved(resolver, document, "Text", KotlinTypeInfo.Text)
        assertResolved(resolver, document, "Uuid", KotlinTypeInfo.Uuid)
        assertResolved(resolver, document, "Number", KotlinTypeInfo.Numeric)
        assertResolved(resolver, document, "Integer", KotlinTypeInfo.Integer)
        assertResolved(resolver, document, "Object", KotlinTypeInfo.Object("Object"))
        assertResolved(resolver, document, "Reference", KotlinTypeInfo.Object("Object"))
        assertResolved(resolver, document, "Enum", KotlinTypeInfo.Enum(listOf("one", "two"), "Enum"))
        assertResolved(resolver, document, "Array", KotlinTypeInfo.Array(KotlinTypeInfo.Uuid))
        assertResolved(resolver, document, "Set", KotlinTypeInfo.Array(KotlinTypeInfo.Text, hasUniqueItems = true))
        assertResolved(resolver, document, "Map", KotlinTypeInfo.Map(KotlinTypeInfo.Integer))
    }

    private fun assertResolved(
        resolver: GeneratorKotlinTypeResolver,
        document: com.cjbooms.fabrikt.parser.GeneratorSchemaDocument,
        name: String,
        expected: KotlinTypeInfo,
    ) {
        assertThat(resolver.resolve(document.componentSchemas.getValue(name)))
            .isEqualTo(GeneratorKotlinTypeResolution.Resolved(expected, false))
    }

    private val openApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Text: { type: string }
            Uuid: { type: string, format: uuid }
            Number: { type: number }
            Integer: { type: integer }
            Object:
              type: object
              properties:
                value: { type: string }
            Reference:
              ${'$'}ref: '#/components/schemas/Object'
            Enum: { type: string, enum: [one, two] }
            Array:
              type: array
              items:
                ${'$'}ref: '#/components/schemas/Uuid'
            Set: { type: array, uniqueItems: true, items: { type: string } }
            Map: { type: object, additionalProperties: { type: integer } }
        """.trimIndent()
}
