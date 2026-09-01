package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

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
    }

    private fun generate(mode: SchemaGenerationMode? = null): List<String> {
        val packages = Packages("com.example")
        val sourceApi = SourceApi(openApi)
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
        """.trimIndent()
}
