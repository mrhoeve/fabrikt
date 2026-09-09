package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

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
