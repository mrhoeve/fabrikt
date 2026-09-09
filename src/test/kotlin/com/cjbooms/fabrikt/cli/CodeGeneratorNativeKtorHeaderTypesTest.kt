package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

class CodeGeneratorNativeKtorHeaderTypesTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates typed native Ktor header parameters`(version: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.KTOR,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi.replace("OPENAPI_VERSION", version)),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("xRequestCount: Int")
            .contains("xEnabled: Boolean?")
            .contains("xLabels: List<String>")
            .contains("xMode: XMode")
            .contains("xMetadata: String?")
            .contains("getTypedHeaderOrFail<kotlin.Int>(\"X-Request-Count\",")
            .contains("getTypedHeader<kotlin.Boolean>(\"X-Enabled\",")
            .contains("getTypedHeaderOrFail<kotlin.collections.List<kotlin.String>>(\"X-Labels\",")
            .contains("getTypedHeaderOrFail<com.example.models.XMode>(\"X-Mode\",")
            .contains("call.application.conversionService, splitValues = false)")
            .contains("val xMetadata = call.request.headers[\"X-Metadata\"]")
            .contains("if (splitValues) values.flatMap { it.split(\",\") } else values")
    }

    private val openApi =
        """
        openapi: OPENAPI_VERSION
        info: { title: Typed headers, version: "1.0" }
        paths:
          /reports:
            get:
              parameters:
                - name: X-Request-Count
                  in: header
                  required: true
                  schema: { type: integer, format: int32 }
                - name: X-Enabled
                  in: header
                  schema: { type: boolean }
                - name: X-Labels
                  in: header
                  required: true
                  schema:
                    type: array
                    items: { type: string }
                - name: X-Mode
                  in: header
                  required: true
                  schema:
                    type: string
                    enum: [summary, detailed]
                - name: X-Metadata
                  in: header
                  schema:
                    type: object
                    properties:
                      source: { type: string }
              responses: { '204': { description: ok } }
        """.trimIndent()
}
