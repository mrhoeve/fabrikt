package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths
import java.util.stream.Stream

class CodeGeneratorSchemaModeTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.HTTP_MODELS))
    }

    @Test
    fun `keeps legacy model generation as the default`() {
        assertThat(generate()).isEqualTo(generate(SchemaGenerationMode.LEGACY))
    }

    @Test
    fun `routes model generation through the internal native mode`() {
        assertThat(generate(SchemaGenerationMode.NATIVE).single())
            .contains("public data class Subject(")
            .contains("public val id: String")
            .contains("public val choice: Any? = null")
            .contains("public val tuple: List<Any?>? = null")
    }

    @Test
    fun `routes named component container models through native mode`() {
        val generated = generate(SchemaGenerationMode.NATIVE, componentContainerOpenApi).joinToString("\n")

        assertThat(generated)
            .contains("public data class CreateSubject(")
            .contains("public val name: String")
            .contains("public data class SubjectResponse(")
            .contains("public val id: String")
    }

    @Test
    fun `routes inline operation body models through native mode`() {
        val generated = generate(SchemaGenerationMode.NATIVE, operationBodyOpenApi).joinToString("\n")

        assertThat(generated)
            .contains("public data class CreateSubjectRequest(")
            .contains("public val name: String")
            .contains("public data class CreateSubject201Response(")
            .contains("public val id: String")
    }

    @ParameterizedTest
    @MethodSource("nativeValueConstraintConfigurations")
    fun `routes native value constraints through supported serialization libraries`(
        version: String,
        serializationLibrary: SerializationLibrary,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.HTTP_MODELS),
            serializationLibrary = serializationLibrary,
        )

        val generated = generate(SchemaGenerationMode.NATIVE, valueConstrainedOpenApi.replace("VERSION", version)).joinToString("\n")

        assertThat(generated)
            .contains("public enum class SubjectMode(")
            .contains("public val mode: SubjectMode = SubjectMode.FIXED")
            .contains("public val count: Int = 1")
        if (serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            assertThat(generated)
                .contains("import kotlinx.serialization.Serializable")
                .contains("public val choice: JsonElement?")
        } else {
            assertThat(generated)
                .contains("import com.fasterxml.jackson.`annotation`.JsonProperty")
                .contains("public val choice: Any?")
        }
    }

    @ParameterizedTest
    @MethodSource("nativeValueConstraintConfigurations")
    fun `routes native reference siblings through supported serialization libraries`(
        version: String,
        serializationLibrary: SerializationLibrary,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.HTTP_MODELS),
            serializationLibrary = serializationLibrary,
        )

        val generated = generate(SchemaGenerationMode.NATIVE, referenceSiblingOpenApi.replace("VERSION", version)).joinToString("\n")

        assertThat(generated)
            .contains("public data class ExtendedSubject(")
            .contains("public val id: String")
            .contains("public val label: String")
            .contains("public enum class ContainerCode(")
            .contains("READY(\"READY\")")
            .doesNotContain("RETRY(\"RETRY\")")
            .contains("public val code: ContainerCode")
            .contains("public val identifier: UUID")
            .contains("public val tags: List<String>")
        if (serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            assertThat(generated).contains("import kotlinx.serialization.Serializable")
        } else {
            assertThat(generated).contains("JsonProperty")
        }
    }

    private fun generate(
        mode: SchemaGenerationMode? = null,
        input: String = openApi,
    ): List<String> {
        val packages = Packages("com.example")
        val sourceApi = SourceApi(input)
        val path = Paths.get("")
        val generator =
            if (mode == null) {
                CodeGenerator(packages, sourceApi, path, path)
            } else {
                CodeGenerator(packages, sourceApi, path, path, mode)
            }
        return generator
            .generate()
            .filterIsInstance<KotlinSourceSet>()
            .flatMap { it.files }
            .map { it.toString() }
            .sorted()
    }

    companion object {
        @JvmStatic
        fun nativeValueConstraintConfigurations(): Stream<Arguments> =
            Stream.of("3.1.2", "3.2.0").flatMap { version ->
                SerializationLibrary.entries.stream().map { serializationLibrary ->
                    Arguments.of(version, serializationLibrary)
                }
            }
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [id]
              properties:
                id: { type: string }
                choice:
                  oneOf:
                    - { type: string }
                    - { type: integer }
                    - { type: 'null' }
                tuple:
                  type: array
                  prefixItems:
                    - { type: string }
                    - { type: integer }
                    - { type: 'null' }
                  items: false
        """.trimIndent()

    private val valueConstrainedOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [choice]
              properties:
                mode:
                  const: fixed
                  default: fixed
                count:
                  enum: [1, 2]
                  default: 1
                choice:
                  enum: [text, 1, null]
        """.trimIndent()

    private val componentContainerOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          requestBodies:
            CreateSubject:
              content:
                application/json:
                  schema:
                    type: object
                    required: [name]
                    properties:
                      name: { type: string }
          responses:
            SubjectResponse:
              description: Subject
              content:
                application/json:
                  schema:
                    type: object
                    required: [id]
                    properties:
                      id: { type: string }
        """.trimIndent()

    private val operationBodyOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      required: [name]
                      properties:
                        name: { type: string }
              responses:
                '201':
                  description: Created
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [id]
                        properties:
                          id: { type: string }
        """.trimIndent()

    private val referenceSiblingOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            BaseSubject:
              type: object
              required: [id]
              properties:
                id: { type: string }
            ExtendedSubject:
              ${'$'}ref: '#/components/schemas/BaseSubject'
              required: [label]
              properties:
                label: { type: string }
            Code:
              type: string
              enum: [READY, DONE]
            Identifier:
              type: string
            Tags:
              type: array
              items: { type: string }
            Container:
              type: object
              required: [code, identifier, tags]
              properties:
                code:
                  ${'$'}ref: '#/components/schemas/Code'
                  type: string
                  enum: [READY, RETRY]
                identifier:
                  ${'$'}ref: '#/components/schemas/Identifier'
                  format: uuid
                tags:
                  ${'$'}ref: '#/components/schemas/Tags'
                  maxItems: 4
        """.trimIndent()
}
