package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CodeGeneratorNativeKtorClientParameterDefaultTest {
    @Test
    fun `uses OpenAPI defaults for optional Ktor client parameters`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("page: Int = 1")
            .contains("xMode: String = \"standard\"")
            .contains("hardDelete: Boolean = false")
            .doesNotContain("page: Int = null")
            .doesNotContain("hardDelete: Boolean = null")
    }

    private val openApi =
        """
        openapi: 3.2.0
        info: { title: Ktor client parameter defaults, version: "1.0" }
        paths:
          /search:
            get:
              operationId: search
              parameters:
                - name: page
                  in: query
                  schema: { type: integer, format: int32, default: 1 }
                - name: X-Mode
                  in: header
                  schema: { type: string, default: standard }
                - name: hard-delete
                  in: cookie
                  schema: { type: boolean, default: false }
              responses:
                '204': { description: searched }
        """.trimIndent()
}
