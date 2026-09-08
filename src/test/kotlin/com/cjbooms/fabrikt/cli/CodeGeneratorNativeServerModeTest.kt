package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Paths

class CodeGeneratorNativeServerModeTest {
    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class)
    fun `generates usable server contracts from native operations`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
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
            .contains("public data class Subject(")
            .contains("public val id: String")
            .contains("public interface SubjectsController")
            .contains("id: Int")
            .contains("includeInactive: Boolean?")
            .contains("sessionId: String?")
            .contains("subject: Subject")
        when (target) {
            ControllerCodeGenTargetType.SPRING ->
                assertThat(generated)
                    .contains("@RequestMapping(")
                    .contains("ResponseEntity<Subject>")
            ControllerCodeGenTargetType.MICRONAUT ->
                assertThat(generated)
                    .contains("@Get(uri = \"/subjects/{id}\")")
                    .contains("HttpResponse<Subject>")
            ControllerCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("public fun Route.subjectsRoutes(")
                    .contains("call.request.cookies[\"session-id\"]")
                    .contains("call: TypedApplicationCall<Subject>")
        }
    }

    private val openApi =
        """
        openapi: 3.1.1
        info:
          title: Native server
          version: "1.0"
        servers:
          - url: https://example.test/api
        paths:
          /subjects/{id}:
            parameters:
              - name: id
                in: path
                required: true
                schema: { type: integer }
            get:
              operationId: findSubject
              summary: Find a subject
              parameters:
                - name: includeInactive
                  in: query
                  schema: { type: boolean }
                - name: session-id
                  in: cookie
                  schema: { type: string }
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Subject' }
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Subject' }
        components:
          schemas:
            Subject:
              type: object
              required: [id]
              properties:
                id: { type: string }
        """.trimIndent()
}
