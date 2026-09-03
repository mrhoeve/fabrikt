package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.cli.SchemaGenerationMode
import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
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

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `resolves multi-type schemas to an explicit safe fallback`(version: String) {
        val parsed = OpenApiDocumentParser.parse(multiTypeOpenApi.replace("VERSION", version))
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val resolver = GeneratorKotlinTypeResolver(document)

        assertThat(resolver.resolve(document.componentSchemas.getValue("Value")))
            .isEqualTo(
                GeneratorKotlinTypeResolution.Fallback(
                    typeInfo = KotlinTypeInfo.AnyType,
                    nullable = true,
                    classification =
                        GeneratorSchemaTypeClassification.MultiType(
                            linkedSetOf(OasType.Text, OasType.Integer),
                            nullable = true,
                        ),
                ),
            )
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `uses serializable JSON fallbacks for nested Kotlinx multi-types`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)
        val parsed = OpenApiDocumentParser.parse(multiTypeOpenApi.replace("VERSION", version))
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val resolver = GeneratorKotlinTypeResolver(document)

        assertThat(resolver.resolve(document.componentSchemas.getValue("Value")))
            .isEqualTo(
                GeneratorKotlinTypeResolution.Fallback(
                    typeInfo = KotlinTypeInfo.JsonElement,
                    nullable = true,
                    classification =
                        GeneratorSchemaTypeClassification.MultiType(
                            linkedSetOf(OasType.Text, OasType.Integer),
                            nullable = true,
                        ),
                ),
            )
        assertResolved(
            resolver,
            document,
            "Values",
            KotlinTypeInfo.Array(KotlinTypeInfo.JsonElement, isParameterizedTypeNullable = true),
        )
        assertResolved(
            resolver,
            document,
            "ValuesByKey",
            KotlinTypeInfo.Map(KotlinTypeInfo.JsonElement),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `resolves composition unions to explicit safe fallbacks`(version: String) {
        val parsed = OpenApiDocumentParser.parse(compositionUnionOpenApi.replace("VERSION", version))
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val resolver = GeneratorKotlinTypeResolver(document)

        assertThat(resolver.resolve(document.componentSchemas.getValue("Choice")))
            .isEqualTo(
                GeneratorKotlinTypeResolution.Fallback(
                    typeInfo = KotlinTypeInfo.AnyType,
                    nullable = true,
                    classification =
                        GeneratorSchemaTypeClassification.CompositionUnion(
                            linkedSetOf(OasType.Text, OasType.Integer),
                            nullable = true,
                        ),
                ),
            )
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `resolves closed open and typed-tail tuples`(version: String) {
        val parsed = OpenApiDocumentParser.parse(tupleOpenApi.replace("VERSION", version))
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val resolver = GeneratorKotlinTypeResolver(document)

        assertResolved(
            resolver,
            document,
            "ClosedTuple",
            KotlinTypeInfo.Array(KotlinTypeInfo.AnyType, isParameterizedTypeNullable = true),
        )
        assertResolved(resolver, document, "HomogeneousTuple", KotlinTypeInfo.Array(KotlinTypeInfo.Text))
        assertResolved(
            resolver,
            document,
            "OpenTuple",
            KotlinTypeInfo.Array(KotlinTypeInfo.AnyType, isParameterizedTypeNullable = true),
        )
        assertResolved(resolver, document, "TypedTail", KotlinTypeInfo.Array(KotlinTypeInfo.AnyType))
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `uses serializable JSON elements for Kotlinx tuple fallbacks`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)
        val parsed = OpenApiDocumentParser.parse(tupleOpenApi.replace("VERSION", version))
        val document = parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)
        val resolver = GeneratorKotlinTypeResolver(document)

        assertResolved(
            resolver,
            document,
            "ClosedTuple",
            KotlinTypeInfo.Array(KotlinTypeInfo.JsonElement, isParameterizedTypeNullable = true),
        )
        assertResolved(
            resolver,
            document,
            "OpenTuple",
            KotlinTypeInfo.Array(KotlinTypeInfo.JsonElement, isParameterizedTypeNullable = true),
        )
        assertResolved(resolver, document, "TypedTail", KotlinTypeInfo.Array(KotlinTypeInfo.JsonElement))
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

    private val multiTypeOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Value:
              type: [string, integer, 'null']
            Values:
              type: array
              items:
                type: [string, integer, 'null']
            ValuesByKey:
              type: object
              additionalProperties:
                type: [string, integer, 'null']
        """.trimIndent()

    private val compositionUnionOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Choice:
              oneOf:
                - { type: string }
                - { type: integer }
                - { type: 'null' }
        """.trimIndent()

    private val tupleOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            ClosedTuple:
              type: array
              prefixItems:
                - { type: string }
                - { type: integer }
                - { type: 'null' }
              items: false
            HomogeneousTuple:
              type: array
              prefixItems:
                - { type: string }
                - { type: string }
              items: false
            OpenTuple:
              type: array
              prefixItems:
                - { type: string }
            TypedTail:
              type: array
              prefixItems:
                - { type: string }
              items: { type: integer }
        """.trimIndent()
}
