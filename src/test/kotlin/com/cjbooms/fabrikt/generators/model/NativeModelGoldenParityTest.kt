package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.CodeGenerationType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import com.cjbooms.fabrikt.util.ModelNameRegistry
import com.cjbooms.fabrikt.util.ResourceHelper.readTextResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class NativeModelGoldenParityTest {
    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.HTTP_MODELS))
        ModelNameRegistry.clear()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("representativeExamples")
    fun `enforces native parity for representative existing examples`(example: String) {
        val sourceApi = SourceApi(readTextResource("/examples/$example/api.yaml"))
        val packages = Packages("parity")
        val legacy = ModelGenerator(packages, sourceApi).generate().asComparableFiles()
        ModelNameRegistry.clear()
        val native =
            NativeModelGenerator(packages.base)
                .generate(
                    GeneratorModelDescriptorBuilder.build(
                        sourceApi.parsedDocument.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                    ),
                ).asComparableFiles()

        if (example == "singleAllOf") {
            assertThat(native)
                .describedAs("Expected native output parity plus the supported inline operation response for $example")
                .containsAllEntriesOf(legacy)
            assertThat(native.keys - legacy.keys).containsExactly("Test200Response")
        } else {
            assertThat(native)
                .describedAs("Expected native output parity for $example")
                .isEqualTo(legacy)
        }
    }

    @Test
    fun `native generation preserves numeric exclusive bounds from OpenAPI 31`() {
        val sourceApi = SourceApi(openApi31ExclusiveBound)
        val packages = Packages("parity")
        val legacy = ModelGenerator(packages, sourceApi).generate().asComparableFiles().getValue("Measurement")
        val native =
            NativeModelGenerator(packages.base)
                .generate(
                    GeneratorModelDescriptorBuilder.build(
                        sourceApi.parsedDocument.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                    ),
                ).asComparableFiles()
                .getValue("Measurement")

        assertThat(native)
            .contains("@get:DecimalMin(")
            .contains("value = \"0\"")
            .contains("inclusive = false")
        assertThat(legacy).doesNotContain("inclusive = false")
    }

    private fun com.cjbooms.fabrikt.model.Models.asComparableFiles(): Map<String, String> = files.associate { it.name to it.toString() }

    companion object {
        private val openApi31ExclusiveBound =
            """
            openapi: 3.1.0
            info:
              title: Test
              version: "1.0"
            paths: {}
            components:
              schemas:
                Measurement:
                  type: object
                  required: [amount]
                  properties:
                    amount:
                      type: number
                      exclusiveMinimum: 0
            """.trimIndent()

        @JvmStatic
        fun representativeExamples(): Stream<String> =
            Stream.of(
                "leadingUnderscoreProperty",
                "mixingCamelSnakeLispCase",
                "binary",
                "optionalVsRequired",
                "validationAnnotations",
                "arrays",
                "mapExamples",
                "defaultValues",
                "enumExamples",
                "inLinedObject",
                "singleAllOf",
                "anyOfOneOfAllOf",
            )
    }
}
