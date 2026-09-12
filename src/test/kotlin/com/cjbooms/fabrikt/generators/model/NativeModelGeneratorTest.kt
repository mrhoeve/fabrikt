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
            .contains("EMPTY(\"\")")
            .contains("IN_PROGRESS(\"in-progress\")")
            .contains("DONE(\"done\")")
            .contains("public fun fromValue(`value`: String): Status? = mapping[value]")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `normalises and disambiguates component model names`(version: String) {
        val generated = generateComponentNames(version)

        assertThat(generated).containsOnlyKeys("AppsSecret", "AppsSecretExtra", "Envelope")
        assertThat(generated.getValue("Envelope"))
            .contains("public val dotted: AppsSecret")
            .contains("public val hyphenated: AppsSecretExtra")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `ignores non-model members while matching discriminator mappings`(version: String) {
        val generated = generateDiscriminatorMappings(version)

        assertThat(generated.getValue("Pet"))
            .contains("public sealed interface Pet")
            .contains("value = Cat::class", "name = \"cat\"")
        assertThat(generated.getValue("Cat")).contains(") : Pet")
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
    fun `generates Kotlinx discriminator mappings without duplicate wire properties`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val generated = generate(version)

        assertThat(generated.getValue("Pet").toString())
            .contains("@Serializable", "@JsonClassDiscriminator(\"kind\")", "public sealed interface Pet")
        assertThat(generated.getValue("Cat").toString())
            .contains("@Serializable", "@SerialName(\"cat\")", ") : Pet")
            .doesNotContain("public val kind:")
        assertThat(generated.getValue("Dog").toString())
            .contains("@Serializable", "@SerialName(\"dog\")", ") : Pet")
            .doesNotContain("public val kind:")
        assertThat(generated.getValue("Bird").toString())
            .contains("@Serializable", "@SerialName(\"Bird\")", ") : Pet")
            .doesNotContain("public val kind:")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates Kotlinx serializers for discriminatorless oneOf unions`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val generated = generateDiscriminatorlessUnion(version)

        assertThat(generated.getValue("Pet"))
            .contains("@Serializable(with = Pet.Serializer::class)")
            .contains("public object Serializer : KSerializer<Pet>")
            .contains("is Cat -> jsonEncoder.json.encodeToJsonElement(Cat.serializer(), value)")
            .contains("is Dog -> jsonEncoder.json.encodeToJsonElement(Dog.serializer(), value)")
            .contains("jsonDecoder.json.decodeFromJsonElement(Cat.serializer(), element)")
            .contains("jsonDecoder.json.decodeFromJsonElement(Dog.serializer(), element)")
            .contains("Expected exactly one Pet variant but matched ")
        assertThat(generated.getValue("Cat")).contains("@Serializable", ") : Pet")
        assertThat(generated.getValue("Dog")).contains("@Serializable", ") : Pet")
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
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `makes required read-only and write-only properties optional in combined native models`(version: String) {
        val generated = generateDirectionalProperties(version)

        assertThat(generated.getValue("Credentials"))
            .contains("public val username: String")
            .contains("public val password: String? = null")
            .contains("public val identifier: String? = null")
        assertThat(generated.getValue("Account"))
            .contains("public val credentials: Credentials")
            .contains("public val audit: AccountAudit? = null")
        assertThat(generated.getValue("AccountAudit"))
            .contains("public val createdBy: String? = null")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates closed scalar unions for native multi-type schemas`(version: String) {
        val generated = generateMultiTypes(version)
        val subject = generated.getValue("Subject")

        assertThat(subject)
            .contains("public val `value`: SubjectValue?")
            .contains("public val values: List<SubjectValues?>? = null")
            .contains("public val valuesByKey: Map<String, ValuesByKeyValue?>? = null")
        assertThat(generated.getValue("SubjectValue"))
            .contains("public sealed interface SubjectValue")
            .contains("public data class StringValue(", "public val `value`: String", ") : SubjectValue")
            .contains("public data class IntegerValue(", "public val `value`: Int", ") : SubjectValue")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates serializable closed scalar unions for every serialization library`(version: String) {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generateMultiTypes(version)
            val subject = generated.getValue("Subject")
            val union = generated.getValue("SubjectValue")

            assertThat(generated).containsKeys("SubjectValue", "SubjectValues", "ValuesByKeyValue")
            assertThat(subject)
                .contains("public val `value`: SubjectValue?")
                .contains("public val values: List<SubjectValues?>? = null")
                .contains("public val valuesByKey: Map<String, ValuesByKeyValue?>? = null")
            assertThat(union)
                .contains("public sealed interface SubjectValue")
                .contains("is StringValue ->")
                .contains("is IntegerValue ->")
            when (library) {
                SerializationLibrary.JACKSON ->
                    assertThat(union)
                        .contains("import com.fasterxml.jackson.databind.JsonSerializer")
                        .contains("@JsonSerialize(using = SubjectValue.Serializer::class)")
                        .contains("generator.writeString(value.value)")
                        .contains("when (parser.currentToken())")
                SerializationLibrary.JACKSON_3 ->
                    assertThat(union)
                        .contains("import tools.jackson.databind.ValueSerializer")
                        .contains("@JsonSerialize(using = SubjectValue.Serializer::class)")
                        .contains("generator.writeNumber(value.value)")
                        .contains("when (parser.currentToken())")
                SerializationLibrary.KOTLINX_SERIALIZATION ->
                    assertThat(union)
                        .contains("@Serializable(with = SubjectValue.Serializer::class)")
                        .contains("public object Serializer : KSerializer<SubjectValue>")
                        .contains("jsonEncoder.encodeJsonElement(primitive)")
                        .contains("jsonDecoder.decodeJsonElement()")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates closed mixed oneOf unions for every serialization library`(version: String) {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generateMixedUnion(version)
            val union = generated.getValue("MixedValue")
            val cat = generated.getValue("Cat")

            assertThat(generated.getValue("Subject"))
                .contains(if (version == "3.0.4") "public val `value`: MixedValue" else "public val `value`: MixedValue?")
            assertThat(union)
                .contains("public sealed interface MixedValue")
                .contains("public data class StringValue(", "public val `value`: String", ") : MixedValue")
                .contains("public data class IntegerValue(", "public val `value`: Int", ") : MixedValue")
            assertThat(cat).contains(") : MixedValue")
            assertThat(generated.getValue("Dog")).contains(") : MixedValue")

            when (library) {
                SerializationLibrary.JACKSON -> {
                    assertThat(union)
                        .contains("@JsonSerialize(using = MixedValue.Serializer::class)")
                        .contains("serializers.defaultSerializeValue(value, generator)")
                        .contains(
                            "context.readTreeAsValue(node, Cat::class.java)",
                            "context.readTreeAsValue(node, Dog::class.java)",
                        )
                    assertThat(cat)
                        .contains("@JsonSerialize(using = JsonSerializer.None::class)")
                        .contains("@JsonDeserialize(using = JsonDeserializer.None::class)")
                }
                SerializationLibrary.JACKSON_3 -> {
                    assertThat(union)
                        .contains("@JsonSerialize(using = MixedValue.Serializer::class)")
                        .contains("serializers.writeValue(generator, value)")
                        .contains(
                            "context.readTreeAsValue(node, Cat::class.java)",
                            "context.readTreeAsValue(node, Dog::class.java)",
                        )
                    assertThat(cat)
                        .contains("@JsonSerialize(using = ValueSerializer.None::class)")
                        .contains("@JsonDeserialize(using = ValueDeserializer.None::class)")
                }
                SerializationLibrary.KOTLINX_SERIALIZATION ->
                    assertThat(union)
                        .contains("@Serializable(with = MixedValue.Serializer::class)")
                        .contains(
                            "jsonEncoder.json.encodeToJsonElement(Cat.serializer(), value)",
                            "jsonEncoder.json.encodeToJsonElement(Dog.serializer(), value)",
                            "jsonDecoder.json.decodeFromJsonElement(Cat.serializer(), element)",
                            "jsonDecoder.json.decodeFromJsonElement(Dog.serializer(), element)",
                        )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates closed scalar unions for native composition unions`(version: String) {
        val generated = generateCompositionUnions(version)
        val subject = generated.getValue("Subject")

        assertThat(subject)
            .contains("public val choice: SubjectChoice?")
            .contains("public val alternative: SubjectAlternative? = null")
            .contains("public val choices: List<SubjectChoices?>? = null")
        assertThat(generated.getValue("SubjectChoice"))
            .contains("public sealed interface SubjectChoice")
            .contains("public data class StringValue(")
            .contains("public data class IntegerValue(")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates closed Kotlinx scalar composition unions`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateCompositionUnions(version).getValue("Subject")

        assertThat(subject)
            .contains("public val choice: SubjectChoice?")
            .contains("public val alternative: SubjectAlternative? = null")
            .contains("public val choices: List<SubjectChoices?>? = null")
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
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `uses JsonElement for native untyped Kotlinx values`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val subject = generateUntypedValues(version)

        assertThat(subject)
            .contains("public val `value`: JsonElement")
            .contains("public val valuesByKey: Map<String, JsonElement?>")
            .doesNotContain("kotlin.Any")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates Kotlinx serializers for native additional properties`(version: String) {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)

        val generated = generateAdditionalProperties(version)

        assertThat(generated.getValue("Subject"))
            .contains("@Serializable(with = Subject.Serializer::class)")
            .contains("public val additionalProperties: MutableMap<String, JsonElement?> = mutableMapOf()")
            .contains("values[\"id\"] = json.encodeToJsonElement<String>(value.id)")
            .contains("if (json.configuration.encodeDefaults || value.count != 5)")
            .contains("additionalProperties = values.mapValues")
            .contains("json.decodeFromJsonElement<JsonElement?>(item)")
        assertThat(generated.getValue("TypedSubject"))
            .contains("public val additionalProperties: MutableMap<String, Int?> = mutableMapOf()")
            .contains("json.decodeFromJsonElement<Int?>(item)")
        assertThat(generated.getValue("NamedSubject"))
            .contains("public val additionalProperties: String?")
            .contains("public val additionalPropertiesExtra: MutableMap<String, Boolean?> = mutableMapOf()")
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

    private fun generateMultiTypes(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(multiTypeOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun generateMixedUnion(version: String): Map<String, String> {
        val nullMember = if (version == "3.0.4") "" else "        - type: 'null'"
        return NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(mixedUnionOpenApi.replace("VERSION", version).replace("NULL_MEMBER", nullMember))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }
    }

    private fun generateComponentNames(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(componentNamesOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun generateDiscriminatorMappings(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(discriminatorMappingsOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun generateDiscriminatorlessUnion(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(discriminatorlessUnionOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun generateCompositionUnions(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(compositionUnionOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

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

    private fun generateUntypedValues(version: String): String =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(untypedValuesOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .single { it.name == "Subject" }
            .toString()

    private fun generateAdditionalProperties(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(additionalPropertiesOpenApi.replace("VERSION", version))
                        .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                ),
            ).files
            .associate { it.name to it.toString() }

    private fun generateDirectionalProperties(version: String): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(
                GeneratorModelDescriptorBuilder.build(
                    OpenApiDocumentParser
                        .parse(directionalPropertiesOpenApi.replace("VERSION", version))
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
              enum: ['', in-progress, done]
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
            Bird:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
            Pet:
              oneOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
                - ${'$'}ref: '#/components/schemas/Bird'
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

    private val mixedUnionOpenApi =
        """
        openapi: VERSION
        info:
          title: Mixed union
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [value]
              properties:
                value:
                  ${'$'}ref: '#/components/schemas/MixedValue'
            MixedValue:
              oneOf:
                - type: string
                - type: integer
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
        NULL_MEMBER
            Cat:
              type: object
              required: [name]
              properties:
                name: { type: string }
            Dog:
              type: object
              required: [barks]
              properties:
                barks: { type: boolean }
        """.trimIndent()

    private val additionalPropertiesOpenApi =
        """
        openapi: VERSION
        info:
          title: Additional properties
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [id]
              properties:
                id: { type: string }
                count: { type: integer, default: 5 }
              additionalProperties: true
            TypedSubject:
              type: object
              properties:
                label: { type: string }
              additionalProperties: { type: integer }
            NamedSubject:
              type: object
              properties:
                additionalProperties: { type: string }
              additionalProperties: { type: boolean }
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

    private val untypedValuesOpenApi =
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
              required: [value, valuesByKey]
              properties:
                value: {}
                valuesByKey:
                  type: object
                  additionalProperties: true
        """.trimIndent()

    private val componentNamesOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            apps.secret:
              type: object
              properties:
                id: { type: string }
            apps-secret:
              type: object
              properties:
                value: { type: string }
            Envelope:
              type: object
              required: [dotted, hyphenated]
              properties:
                dotted:
                  ${'$'}ref: '#/components/schemas/apps.secret'
                hyphenated:
                  ${'$'}ref: '#/components/schemas/apps-secret'
        """.trimIndent()

    private val discriminatorMappingsOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Pet:
              type: object
              discriminator:
                propertyName: kind
                mapping:
                  cat: '#/components/schemas/Cat'
              oneOf:
                - type: string
                - ${'$'}ref: '#/components/schemas/Cat'
            Cat:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
        """.trimIndent()

    private val discriminatorlessUnionOpenApi =
        """
        openapi: VERSION
        info:
          title: Discriminatorless union
          version: "1.0"
        paths: {}
        components:
          schemas:
            Pet:
              oneOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
            Cat:
              type: object
              required: [meows]
              properties:
                meows: { type: boolean }
            Dog:
              type: object
              required: [barks]
              properties:
                barks: { type: boolean }
        """.trimIndent()

    private val directionalPropertiesOpenApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Credentials:
              type: object
              required: [username, password, identifier]
              properties:
                username: { type: string }
                password:
                  type: string
                  writeOnly: true
                identifier:
                  type: string
                  readOnly: true
            Account:
              type: object
              required: [credentials, audit]
              properties:
                credentials:
                  ${'$'}ref: '#/components/schemas/Credentials'
                audit:
                  type: object
                  readOnly: true
                  required: [createdBy]
                  properties:
                    createdBy:
                      type: string
                      readOnly: true
        """.trimIndent()
}
