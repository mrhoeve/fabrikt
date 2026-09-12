package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class GeneratorModelDescriptorTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @Test
    fun `builds equal basic model descriptors from legacy and native schemas`() {
        val parsed = OpenApiDocumentParser.parse(openApi)
        val legacy = GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.LEGACY))
        val native = GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE))

        assertThat(native.withoutIdentities()).isEqualTo(legacy.withoutIdentities())
    }

    @Test
    fun `describes resolved properties and their generation semantics`() {
        val parsed = OpenApiDocumentParser.parse(openApi)
        val models = GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE))
        val subject = models.single { it.name == "Subject" }

        assertThat(subject.classification).isEqualTo(GeneratorSchemaTypeClassification.Resolved(OasType.Object, false))
        assertThat(subject.description).isEqualTo("A subject")
        assertThat(subject.properties).hasSize(2)
        val id = subject.properties.single { it.name == "id" }
        assertThat(id.classification).isEqualTo(GeneratorSchemaTypeClassification.Resolved(OasType.Uuid, false))
        assertThat(id.required).isTrue()
        assertThat(id.readOnly).isTrue()
        assertThat(id.description).isEqualTo("Stable identifier")

        val label = subject.properties.single { it.name == "label" }
        assertThat(label.classification).isEqualTo(GeneratorSchemaTypeClassification.Resolved(OasType.Text, true))
        assertThat(label.required).isFalse()
        assertThat(label.defaultValue).isEqualTo(JsonNodeFactory.instance.textNode("unknown"))
        assertThat(label.constraints?.minLength).isEqualTo(2)
        assertThat(label.constraints?.maxLength).isEqualTo(40)
    }

    @Test
    fun `omits optional properties that cannot accept a value`() {
        val models = buildNativeModels(neverSchemaOpenApi)

        assertThat(models.single { it.name == "Permitted" }.properties.map(GeneratorPropertyDescriptor::name))
            .containsExactly("value")
    }

    @Test
    fun `rejects required properties that cannot accept a value`() {
        assertThatThrownBy { buildNativeModels(neverSchemaOpenApi.replace("required: []", "required: [forbiddenByReference]")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cannot generate model 'Permitted' because required property 'forbiddenByReference' cannot accept any value")
    }

    @Test
    fun `rejects composed models that cannot accept a value`() {
        assertThatThrownBy { buildNativeModels(impossibleCompositionOpenApi) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cannot generate model 'Impossible' because its schema cannot accept any value")
    }

    private fun buildNativeModels(input: String): List<GeneratorModelDescriptor> =
        GeneratorModelDescriptorBuilder.build(
            OpenApiDocumentParser.parse(input).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
        )

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `reuses component models referenced by operation inputs and outputs`(version: String) {
        val parsed = OpenApiDocumentParser.parse(operationReferencesOpenApi(version))

        val models = GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE))

        assertThat(models.map(GeneratorModelDescriptor::name)).containsExactly("Subject")
    }

    @Test
    fun `visits shared composition branches once per model root`() {
        val depth = 24
        val parsed = OpenApiDocumentParser.parse(compositionGraphOpenApi(depth))

        val models =
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                ThrowingSupplier {
                    GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE))
                },
            )

        assertThat(models.map(GeneratorModelDescriptor::name)).contains("Composition$depth", "Leaf")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `describes patterned object values without losing nested models`(version: String) {
        val parsed = OpenApiDocumentParser.parse(patternPropertiesOpenApi.replace("VERSION", version))

        val models = GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE))

        assertThat(models.map(GeneratorModelDescriptor::name))
            .containsExactly(
                "TypedPatterns",
                "OpenPatterns",
                "MixedPatterns",
                "ConstrainedPatterns",
                "NestedPatterns",
                "NestedPatternsPattern1Value",
            )
        assertThat(models.single { it.name == "TypedPatterns" }.additionalPropertiesType?.typeInfo)
            .isEqualTo(KotlinTypeInfo.Text)
        assertThat(models.single { it.name == "OpenPatterns" }.additionalPropertiesType?.typeInfo)
            .isEqualTo(KotlinTypeInfo.AnyType)
        assertThat(models.single { it.name == "MixedPatterns" }.additionalPropertiesType?.typeInfo)
            .isEqualTo(KotlinTypeInfo.AnyType)
        assertThat(models.single { it.name == "ConstrainedPatterns" }.additionalPropertiesType?.typeInfo)
            .isEqualTo(KotlinTypeInfo.Text)
        assertThat(models.single { it.name == "NestedPatterns" }.additionalPropertiesType?.typeInfo)
            .isEqualTo(KotlinTypeInfo.Object("NestedPatternsPattern1Value"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `projects conditional object properties as optional model fields`(version: String) {
        val parsed = OpenApiDocumentParser.parse(conditionalPropertiesOpenApi.replace("VERSION", version))

        val models = GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE))
        val subject = models.single { it.name == "Subject" }

        assertThat(models.map(GeneratorModelDescriptor::name)).containsExactly("Subject", "SubjectCredentials", "SubjectConfig")
        assertThat(subject.properties.map(GeneratorPropertyDescriptor::name))
            .containsExactly("email", "phone", "clientId", "credentials", "tenant", "config", "region", "kind")
        assertThat(subject.properties.single { it.name == "kind" }.required).isTrue()
        assertThat(subject.properties.filterNot { it.name == "kind" }).allMatch { !it.required }
    }

    private fun List<GeneratorModelDescriptor>.withoutIdentities() =
        map { model ->
            model.copy(
                schemaIdentity = nativeIdentity,
                properties = model.properties.map { it.copy(schemaIdentity = nativeIdentity) },
            )
        }

    private val nativeIdentity =
        com.cjbooms.fabrikt.parser
            .GeneratorSchemaIdentity()

    private val openApi =
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
              description: A subject
              required: [id]
              properties:
                id:
                  ${'$'}ref: '#/components/schemas/Identifier'
                label:
                  type: string
                  nullable: true
                  default: unknown
                  minLength: 2
                  maxLength: 40
            Identifier:
              type: string
              format: uuid
              description: Stable identifier
              readOnly: true
        """.trimIndent()

    private val neverSchemaOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Never: false
            Permitted:
              type: object
              required: []
              properties:
                value: { type: string }
                forbiddenDirectly: false
                forbiddenByReference:
                  ${'$'}ref: '#/components/schemas/Never'
        """.trimIndent()

    private val patternPropertiesOpenApi =
        """
        openapi: VERSION
        info:
          title: Pattern properties
          version: "1.0"
        paths: {}
        components:
          schemas:
            TypedPatterns:
              type: object
              patternProperties:
                '^S_': { type: string }
              additionalProperties: false
            OpenPatterns:
              type: object
              patternProperties:
                '^S_': { type: string }
            MixedPatterns:
              type: object
              patternProperties:
                '^S_': { type: string }
                '^I_': { type: integer }
              additionalProperties: false
            ConstrainedPatterns:
              type: object
              patternProperties:
                '^S_': { type: string }
              additionalProperties: { type: string }
            NestedPatterns:
              type: object
              patternProperties:
                '^entry-':
                  type: object
                  required: [value]
                  properties:
                    value: { type: integer }
              additionalProperties: false
        """.trimIndent()

    private val impossibleCompositionOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Impossible:
              allOf:
                - { type: object }
                - false
        """.trimIndent()

    private val conditionalPropertiesOpenApi =
        """
        openapi: VERSION
        info:
          title: Conditional properties
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
              anyOf:
                - required: [email]
                  properties:
                    email: { type: string }
                - required: [phone]
                  properties:
                    phone: { type: string }
              dependentSchemas:
                oauth:
                  required: [clientId, credentials]
                  properties:
                    clientId: { type: string }
                    credentials:
                      type: object
                      required: [secret]
                      properties:
                        secret: { type: string }
              if:
                properties:
                  kind: { const: enterprise }
              then:
                required: [tenant, config]
                properties:
                  tenant: { type: string }
                  config:
                    type: object
                    required: [enabled]
                    properties:
                      enabled: { type: boolean }
              else:
                required: [region]
                properties:
                  region: { type: string }
        """.trimIndent()

    private fun operationReferencesOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              parameters:
                - name: filter
                  in: query
                  schema:
                    ${'$'}ref: '#/components/schemas/Subject'
              requestBody:
                content:
                  application/json:
                    schema:
                      ${'$'}ref: '#/components/schemas/Subject'
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Subject'
        components:
          schemas:
            Subject:
              type: object
              properties:
                id: { type: string }
        """.trimIndent()

    private fun compositionGraphOpenApi(depth: Int): String {
        val schemas =
            buildString {
                appendLine("    Leaf:")
                appendLine("      type: object")
                appendLine("      properties:")
                appendLine("        value: { type: string }")
                (1..depth).forEach { index ->
                    val parent = if (index == 1) "Leaf" else "Composition${index - 1}"
                    appendLine("    Composition$index:")
                    appendLine("      allOf:")
                    appendLine("        - ${'$'}ref: '#/components/schemas/$parent'")
                    appendLine("        - ${'$'}ref: '#/components/schemas/$parent'")
                }
            }
        return buildString {
            appendLine("openapi: 3.1.1")
            appendLine("info:")
            appendLine("  title: Composition graph")
            appendLine("  version: \"1.0\"")
            appendLine("paths: {}")
            appendLine("components:")
            appendLine("  schemas:")
            append(schemas)
        }
    }
}
