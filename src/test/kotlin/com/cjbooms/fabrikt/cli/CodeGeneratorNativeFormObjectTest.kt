package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

class CodeGeneratorNativeFormObjectTest {
    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `serializes native object valued form fields`(targetName: String) {
        val generated = generate(ClientCodeGenTargetType.valueOf(targetName))

        assertThat(generated)
            .contains("filter: Filter")
            .contains("filter.role")
            .contains("filter.active?.let")
            .contains("compactFilter.role")
            .contains("joinToString(\",\")")
            .contains("deepFilter.role")
        if (targetName == "OK_HTTP") {
            assertThat(generated)
                .contains("formBuilder.add(\"role\"")
                .contains("formBuilder.add(\"deepFilter[role]\"")
        } else {
            assertThat(generated)
                .contains("append(\"role\"")
                .contains("append(\"deepFilter[role]\"")
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClientCodeGenTargetType::class, names = ["OPEN_FEIGN", "SPRING_HTTP_INTERFACE"])
    fun `rejects object valued forms for clients without deterministic encoding`(target: ClientCodeGenTargetType) {
        assertThatThrownBy { generate(target) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not support native object-valued form fields")
    }

    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class)
    fun `rejects object valued forms for server frameworks without deterministic binding`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CONTROLLERS), controllerTarget = target)

        assertThatThrownBy {
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not support native object-valued form fields")
    }

    private fun generate(target: ClientCodeGenTargetType): String {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CLIENT), clientTarget = target)
        return CodeGenerator(
            Packages("com.example"),
            SourceApi(openApi),
            Paths.get(""),
            Paths.get(""),
            SchemaGenerationMode.NATIVE,
        ).generate()
            .filterIsInstance<KotlinSourceSet>()
            .flatMap { it.files }
            .joinToString("\n")
    }

    private val openApi =
        """
        openapi: 3.1.1
        info: { title: Form objects, version: "1.0" }
        paths:
          /search:
            post:
              requestBody:
                required: true
                content:
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
                      required: [filter]
                      properties:
                        filter: { ${'$'}ref: '#/components/schemas/Filter' }
                    encoding:
                      filter:
                        style: form
                        explode: true
              responses: { '204': { description: ok } }
          /compact:
            post:
              requestBody:
                required: true
                content:
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
                      required: [compactFilter]
                      properties:
                        compactFilter: { ${'$'}ref: '#/components/schemas/Filter' }
                    encoding:
                      compactFilter:
                        style: form
                        explode: false
              responses: { '204': { description: ok } }
          /deep:
            post:
              requestBody:
                required: true
                content:
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
                      required: [deepFilter]
                      properties:
                        deepFilter: { ${'$'}ref: '#/components/schemas/Filter' }
                    encoding:
                      deepFilter:
                        style: deepObject
                        explode: true
              responses: { '204': { description: ok } }
        components:
          schemas:
            Filter:
              type: object
              required: [role]
              properties:
                role: { type: string }
                active: { type: boolean }
        """.trimIndent()
}
