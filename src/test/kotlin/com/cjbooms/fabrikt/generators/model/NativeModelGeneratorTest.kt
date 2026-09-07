package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NativeModelGeneratorTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates data classes with collection properties from native schemas`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Subject").toString())
            .contains("public val id: String")
            .contains("public val count: Int = 5")
            .contains("public val aliases: List<String>? = null")
            .contains("public val labels: LinkedHashSet<String>? = null")
            .contains("public val attributes: Map<String, Int?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates enums from native schemas`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Status").toString())
            .contains("@JsonValue")
            .contains("IN_PROGRESS(\"in-progress\")")
            .contains("DONE(\"done\")")
            .contains("public fun fromValue(`value`: String): Status? = mapping[value]")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates defaults documentation serialization and validation metadata`(version: String) {
        val subject = generate(version).getValue("Subject").toString()

        assertThat(subject)
            .contains("A generated subject.")
            .contains("@param:JsonProperty(\"id\")")
            .contains("@get:JsonProperty(\"id\")")
            .contains("@get:NotNull")
            .contains("@get:Pattern(regexp = \"[a-z]+\")")
            .contains("@get:Size(", "min = 2", "max = 20")
            .contains("@get:DecimalMin(", "value = \"1\"", "inclusive = true")
            .contains("@get:DecimalMax(", "value = \"10\"")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `flattens referenced and inline allOf properties`(version: String) {
        val child = generate(version).getValue("Child").toString()

        assertThat(child)
            .contains("public val baseId: String")
            .contains("public val childName: String")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates oneOf sealed unions and flattens anyOf models`(version: String) {
        val generated = generate(version)

        assertThat(generated.getValue("Pet").toString())
            .contains("public sealed interface Pet")
            .contains("@JsonTypeInfo(")
            .contains("property = \"kind\"")
            .contains("name = \"cat\"")
            .contains("name = \"dog\"")
        assertThat(generated.getValue("PossiblePet").toString())
            .contains("public data class PossiblePet(")
            .contains("public val kind: String")
        assertThat(generated.getValue("Cat").toString()).contains(") : Pet").doesNotContain("PossiblePet")
        assertThat(generated.getValue("Dog").toString()).contains(") : Pet").doesNotContain("PossiblePet")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates deterministically named inline models`(version: String) {
        val generated = generate(version)

        assertThat(generated).containsKeys("SubjectDetail", "SubjectState", "SubjectPets")
        assertThat(generated.getValue("Subject").toString())
            .contains("public val detail: SubjectDetail? = null")
            .contains("public val state: SubjectState? = null")
            .contains("public val pets: List<SubjectPets>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `native source and legacy adapters produce identical shared model output`(version: String) {
        assertThat(generate(version, SchemaGenerationMode.NATIVE))
            .isEqualTo(generate(version, SchemaGenerationMode.LEGACY))
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates safe fallback properties for native multi-type schemas`(version: String) {
        val subject = generateMultiTypes(version)

        assertThat(subject)
            .contains("public val `value`: Any?")
            .contains("public val values: List<Any?>? = null")
            .contains("public val valuesByKey: Map<String, Any?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates serializable Kotlinx fallbacks for native multi-type schemas`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateMultiTypes(version)

        assertThat(subject)
            .contains("public val `value`: JsonElement?")
            .contains("public val values: List<JsonElement?>? = null")
            .contains("public val valuesByKey: Map<String, JsonElement?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates safe fallback properties for native composition unions`(version: String) {
        val subject = generateCompositionUnions(version)

        assertThat(subject)
            .contains("public val choice: Any?")
            .contains("public val alternative: Any? = null")
            .contains("public val choices: List<Any?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates serializable Kotlinx fallbacks for native composition unions`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateCompositionUnions(version)

        assertThat(subject)
            .contains("public val choice: JsonElement?")
            .contains("public val alternative: JsonElement? = null")
            .contains("public val choices: List<JsonElement?>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates native closed open and typed-tail tuple properties`(version: String) {
        val subject = generateTuples(version)

        assertThat(subject)
            .contains("public val closedTuple: List<Any?>? = null")
            .contains("public val homogeneousTuple: List<String>? = null")
            .contains("public val openTuple: List<Any?>? = null")
            .contains("public val typedTail: List<Any>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates serializable Kotlinx tuple properties`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateTuples(version)

        assertThat(subject)
            .contains("public val closedTuple: List<JsonElement?>? = null")
            .contains("public val homogeneousTuple: List<String>? = null")
            .contains("public val openTuple: List<JsonElement?>? = null")
            .contains("public val typedTail: List<JsonElement>? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates native models from enum values without declared types`(version: String) {
        val generated = generateValueConstraints(enumValueOpenApi.replace("VERSION", version))

        assertThat(generated.getValue("Status"))
            .contains("public enum class Status(")
            .contains("READY(\"ready\")")
            .contains("DONE(\"done\")")
        assertThat(generated.getValue("Subject"))
            .contains("public val status: Status")
            .contains("public val attempts: Int = 1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates native models from const and heterogeneous value constraints`(version: String) {
        val generated = generateValueConstraints(constValueOpenApi.replace("VERSION", version))

        assertThat(generated.getValue("Fixed"))
            .contains("public enum class Fixed(")
            .contains("FIXED_VALUE(\"fixed-value\")")
        assertThat(generated.getValue("SubjectMode"))
            .contains("public enum class SubjectMode(")
            .contains("MANUAL(\"manual\")")
        assertThat(generated.getValue("Subject"))
            .contains("public val fixed: Fixed")
            .contains("public val mode: SubjectMode = SubjectMode.MANUAL")
            .contains("public val enabled: Boolean = true")
            .contains("public val ratio: BigDecimal")
            .contains("public val choice: Any?")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates serializable Kotlinx value constraint fallbacks`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateValueConstraints(constValueOpenApi.replace("VERSION", version)).getValue("Subject")

        assertThat(subject)
            .contains("public val fixed: Fixed")
            .contains("public val mode: SubjectMode = SubjectMode.MANUAL")
            .contains("public val choice: JsonElement?")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `applies native reference siblings to generated models`(version: String) {
        val generated = generateReferenceSiblings(version)

        assertThat(generated.getValue("ExtendedSubject"))
            .contains("public val id: String")
            .contains("public val label: String")
        assertThat(generated.getValue("ContainerCode"))
            .contains("public enum class ContainerCode(")
            .contains("READY(\"READY\")")
            .doesNotContain("DONE(\"DONE\")")
        assertThat(generated.getValue("Container"))
            .contains("public val subject: ExtendedSubject")
            .contains("public val code: ContainerCode")
            .contains("public val identifier: UUID")
            .contains("public val tags: List<String>")
            .contains("max = 4")
            .contains("A narrowed code")
            .contains("@get:Pattern(regexp = \"(?=(?:[A-Z]+)\\\\z)(?:R.*)\")")
            .contains("@get:Size(", "min = 3", "max = 8")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `keeps generated reference sibling models portable across serializers`(version: String) {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generateReferenceSiblings(version)

            assertThat(generated).containsKeys("ExtendedSubject", "ContainerCode", "Container")
            assertThat(generated.getValue("Container"))
                .contains("public val subject: ExtendedSubject")
                .contains("public val code: ContainerCode")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4"])
    fun `keeps OpenAPI 3_0 reference sibling generation compatible`(version: String) {
        val generated = generateReferenceSiblings(version)

        assertThat(generated).containsKey("ExtendedSubject").doesNotContainKey("ContainerCode")
        assertThat(generated.getValue("ExtendedSubject"))
            .contains("public val id: String")
            .doesNotContain("public val label: String")
        assertThat(generated.getValue("Container"))
            .contains("public val subject: BaseSubject")
            .contains("public val code: Code")
            .contains("public val identifier: String")
            .contains("public val tags: List<String>")
            .doesNotContain("A narrowed code")
            .doesNotContain("max = 4")
    }

    private fun generate(
        version: String,
        mode: SchemaGenerationMode = SchemaGenerationMode.NATIVE,
    ) = NativeModelGenerator("com.example")
        .generate(
            GeneratorModelDescriptorBuilder.build(
                OpenApiDocumentParser
                    .parse(openApi.replace("VERSION", version))
                    .toGeneratorSchemaDocument(mode),
            ),
        ).files
        .associateBy { it.name }

    private fun generateMultiTypes(version: String): String =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(multiTypeOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .single { it.name == "Subject" }
            .toString()

    private fun generateCompositionUnions(version: String): String =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(compositionUnionOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .single { it.name == "Subject" }
            .toString()

    private fun generateTuples(version: String): String =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(tupleOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .single { it.name == "Subject" }
            .toString()

    private fun generateValueConstraints(openApi: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser.parse(openApi).toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun generateReferenceSiblings(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(referenceSiblingOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private val openApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Status:
              type: string
              enum: [in-progress, done]
            Subject:
              type: object
              description: A generated subject.
              required: [id]
              properties:
                id:
                  type: string
                  pattern: '[a-z]+'
                  minLength: 2
                  maxLength: 20
                count:
                  type: integer
                  default: 5
                  minimum: 1
                  maximum: 10
                aliases:
                  type: array
                  items: { type: string }
                labels:
                  type: array
                  uniqueItems: true
                  items: { type: string }
                attributes:
                  type: object
                  additionalProperties: { type: integer }
                detail:
                  type: object
                  properties:
                    note: { type: string }
                state:
                  type: string
                  enum: [new, old]
                pets:
                  type: array
                  items:
                    type: object
                    properties:
                      name: { type: string }
            Base:
              type: object
              required: [baseId]
              properties:
                baseId: { type: string }
            Child:
              allOf:
                - ${'$'}ref: '#/components/schemas/Base'
                - type: object
                  required: [childName]
                  properties:
                    childName: { type: string }
            Cat:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
            Dog:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
            Pet:
              oneOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
              discriminator:
                propertyName: kind
                mapping:
                  cat: '#/components/schemas/Cat'
                  dog: '#/components/schemas/Dog'
            PossiblePet:
              anyOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
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
            Subject:
              type: object
              required: [value]
              properties:
                value:
                  type: [string, integer, 'null']
                values:
                  type: array
                  items:
                    type: [string, integer, 'null']
                valuesByKey:
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
            Subject:
              type: object
              required: [choice]
              properties:
                choice:
                  oneOf:
                    - { type: string }
                    - { type: integer }
                    - { type: 'null' }
                alternative:
                  anyOf:
                    - { type: boolean }
                    - { type: number }
                choices:
                  type: array
                  items:
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
            Subject:
              type: object
              properties:
                closedTuple:
                  type: array
                  prefixItems:
                    - { type: string }
                    - { type: integer }
                    - { type: 'null' }
                  items: false
                homogeneousTuple:
                  type: array
                  prefixItems:
                    - { type: string }
                    - { type: string }
                  items: false
                openTuple:
                  type: array
                  prefixItems:
                    - { type: string }
                typedTail:
                  type: array
                  prefixItems:
                    - { type: string }
                  items: { type: integer }
        """.trimIndent()

    private val enumValueOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Status:
              enum: [ready, done]
            Subject:
              type: object
              required: [status]
              properties:
                status:
                  ${'$'}ref: '#/components/schemas/Status'
                attempts:
                  enum: [1, 2]
                  default: 1
        """.trimIndent()

    private val constValueOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Fixed:
              const: fixed-value
            Subject:
              type: object
              required: [fixed, ratio, choice]
              properties:
                fixed:
                  ${'$'}ref: '#/components/schemas/Fixed'
                mode:
                  const: manual
                  default: manual
                enabled:
                  const: true
                  default: true
                ratio:
                  const: 1.5
                choice:
                  enum: [text, 1, null]
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
              pattern: '[A-Z]+'
              minLength: 2
              maxLength: 8
            Identifier:
              type: string
            Tags:
              type: array
              items: { type: string }
            Container:
              type: object
              required: [subject, code, identifier, tags]
              properties:
                subject:
                  ${'$'}ref: '#/components/schemas/ExtendedSubject'
                code:
                  ${'$'}ref: '#/components/schemas/Code'
                  description: A narrowed code
                  type: string
                  enum: [READY, RETRY]
                  pattern: 'R.*'
                  minLength: 3
                identifier:
                  ${'$'}ref: '#/components/schemas/Identifier'
                  format: uuid
                tags:
                  ${'$'}ref: '#/components/schemas/Tags'
                  maxItems: 4
        """.trimIndent()
}
