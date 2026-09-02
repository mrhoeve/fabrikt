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
    fun `tracks native parity for representative existing examples`(example: RepresentativeExample) {
        val sourceApi = SourceApi(readTextResource("/examples/${example.name}/api.yaml"))
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

        if (example.hasParity) {
            assertThat(native)
                .describedAs("Expected native output parity for ${example.name}")
                .isEqualTo(legacy)
        } else {
            assertThat(native)
                .describedAs("Expected the documented native parity gap for ${example.name}: ${example.gap}")
                .isNotEqualTo(legacy)
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
        fun representativeExamples(): Stream<RepresentativeExample> =
            Stream.of(
                RepresentativeExample.parity("leadingUnderscoreProperty"),
                RepresentativeExample.parity("mixingCamelSnakeLispCase"),
                RepresentativeExample.parity("binary"),
                RepresentativeExample.parity("optionalVsRequired"),
                RepresentativeExample.parity("validationAnnotations"),
                RepresentativeExample.parity("arrays"),
                RepresentativeExample.parity("mapExamples"),
                RepresentativeExample.gap("defaultValues", "complex and inline enum defaults"),
                RepresentativeExample.gap("enumExamples", "extensible and collection enum handling"),
                RepresentativeExample.gap("inLinedObject", "legacy-compatible nested model naming"),
                RepresentativeExample.gap("singleAllOf", "single-reference allOf aliases"),
                RepresentativeExample.gap("anyOfOneOfAllOf", "advanced composition model shapes"),
            )
    }

    data class RepresentativeExample(
        val name: String,
        val hasParity: Boolean,
        val gap: String? = null,
    ) {
        override fun toString(): String = name

        companion object {
            fun parity(name: String) = RepresentativeExample(name, true)

            fun gap(
                name: String,
                reason: String,
            ) = RepresentativeExample(name, false, reason)
        }
    }
}
