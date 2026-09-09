package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.generators.model.NativeModelGenerator
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GeneratorDirectionalModelPlanTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `creates filtered request and response variants for directional models`(version: String) {
        val models = plan(version).descriptors.associateBy(GeneratorModelDescriptor::name)

        assertThat(models.keys)
            .contains(
                "Credentials",
                "CredentialsRequest",
                "CredentialsResponse",
                "Envelope",
                "EnvelopeRequest",
                "EnvelopeResponse",
            )
        assertThat(models.getValue("CredentialsRequest").properties.map(GeneratorPropertyDescriptor::name))
            .containsExactly("username", "password")
        assertThat(models.getValue("CredentialsResponse").properties.map(GeneratorPropertyDescriptor::name))
            .containsExactly("username", "identifier")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `propagates direction through nested collections`(version: String) {
        val models = plan(version).descriptors.associateBy(GeneratorModelDescriptor::name)

        assertThat(
            models
                .getValue("EnvelopeRequest")
                .properties
                .single { it.name == "credentials" }
                .resolvedTypeName(),
        ).isEqualTo("CredentialsRequest")
        assertThat(
            models
                .getValue("EnvelopeResponse")
                .properties
                .single { it.name == "credentials" }
                .resolvedTypeName(),
        ).isEqualTo("CredentialsResponse")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `propagates direction through nested maps`(version: String) {
        val models = plan(version).descriptors.associateBy(GeneratorModelDescriptor::name)

        assertThat(
            models
                .getValue("EnvelopeRequest")
                .properties
                .single { it.name == "credentialsByName" }
                .resolvedMapTypeName(),
        ).isEqualTo("CredentialsRequest")
        assertThat(
            models
                .getValue("EnvelopeResponse")
                .properties
                .single { it.name == "credentialsByName" }
                .resolvedMapTypeName(),
        ).isEqualTo("CredentialsResponse")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `places direction before the configured model suffix`(version: String) {
        MutableSettings.updateSettings(modelSuffix = "Dto")

        val models = plan(version).descriptors.associateBy(GeneratorModelDescriptor::name)

        assertThat(models).containsKeys("CredentialsDto", "CredentialsRequestDto", "CredentialsResponseDto")
        assertThat(
            models
                .getValue("EnvelopeRequestDto")
                .properties
                .single { it.name == "credentials" }
                .resolvedTypeName(),
        ).isEqualTo("CredentialsRequestDto")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `projects complete oneOf hierarchies by direction`(version: String) {
        val generated =
            NativeModelGenerator("com.example")
                .generate(plan(version).descriptors)
                .files
                .associate { it.name to it.toString() }

        assertThat(generated.getValue("PetRequest"))
            .contains("value = CatRequest::class", "DogRequest::class")
        assertThat(generated.getValue("CatRequest")).contains(") : PetRequest")
        assertThat(generated.getValue("DogRequest")).contains(") : PetRequest")
        assertThat(generated.getValue("PetResponse"))
            .contains("value = CatResponse::class", "DogResponse::class")
        assertThat(generated.getValue("CatResponse")).contains(") : PetResponse")
        assertThat(generated.getValue("DogResponse")).contains(") : PetResponse")
    }

    private fun plan(version: String): GeneratorDirectionalModelPlan =
        GeneratorDirectionalModelPlan.create(
            GeneratorModelDescriptorBuilder.build(
                OpenApiDocumentParser
                    .parse(openApi.replace("VERSION", version))
                    .toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
            ),
        )

    private fun GeneratorPropertyDescriptor.resolvedTypeName(): String? {
        val resolution = kotlinType as GeneratorKotlinTypeResolution.Resolved
        val list = resolution.typeInfo as KotlinTypeInfo.Array
        return list.parameterizedType.generatedModelClassName
    }

    private fun GeneratorPropertyDescriptor.resolvedMapTypeName(): String? {
        val resolution = kotlinType as GeneratorKotlinTypeResolution.Resolved
        val map = resolution.typeInfo as KotlinTypeInfo.Map
        return map.parameterizedType.generatedModelClassName
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
            Envelope:
              type: object
              required: [credentials]
              properties:
                credentials:
                  type: array
                  items:
                    ${'$'}ref: '#/components/schemas/Credentials'
                credentialsByName:
                  type: object
                  additionalProperties:
                    ${'$'}ref: '#/components/schemas/Credentials'
            Cat:
              type: object
              required: [kind, identifier]
              properties:
                kind: { type: string }
                identifier:
                  type: string
                  readOnly: true
            Dog:
              type: object
              required: [kind]
              properties:
                kind: { type: string }
            Pet:
              oneOf:
                - ${'$'}ref: '#/components/schemas/Cat'
                - ${'$'}ref: '#/components/schemas/Dog'
        """.trimIndent()
}
