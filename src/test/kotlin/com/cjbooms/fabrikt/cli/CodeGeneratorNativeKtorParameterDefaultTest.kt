package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CodeGeneratorNativeKtorParameterDefaultTest {
    @Test
    fun `applies OpenAPI defaults while decoding optional Ktor parameters`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.KTOR,
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
            .contains("page: Int")
            .contains("xMode: String")
            .contains("hardDelete: Boolean")
            .contains("getTyped<kotlin.Int>(\"page\") ?: 1")
            .contains("getTypedHeader<kotlin.String>(\"X-Mode\"")
            .contains("?: \"standard\"")
            .contains("getTypedCookie<kotlin.Boolean>(\"hard-delete\"")
            .contains("?: false")
            .contains("controller.search(xMode, hardDelete, page, call)")
    }

    private val openApi =
        """
        openapi: 3.2.0
        info: { title: Ktor parameter defaults, version: "1.0" }
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
