package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GeneratorModelDescriptorTest {
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
}
