package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SimpleFile
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Paths

class CodeGeneratorNativeClientModeTest {
    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates usable clients from native operations`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generatedFiles =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        val generated =
            generatedFiles.joinToString("\n") { file ->
                when (file) {
                    is KotlinSourceSet -> file.files.joinToString("\n")
                    is SimpleFile -> file.content
                    else -> ""
                }
            }

        assertThat(generated)
            .contains("public data class Subject(")
            .contains("findSubject")
            .contains("id: Int")
            .contains("includeInactive: Boolean? = null")
            .contains("sessionId: String? = null")
            .contains("subject: Subject")
        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("public class SubjectsClient")
                    .contains("ApiResponse<Subject>")
                    .contains(".pathParam(\"{id}\" to id)")
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated)
                    .contains("public interface SubjectsClient")
                    .contains("@RequestLine(\"POST /subjects/{id}?includeInactive={includeInactive}\")")
                    .contains("\"Cookie: {cookieHeader}\"")
                    .contains("): Subject")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated)
                    .contains("public interface SubjectsClient")
                    .contains("@HttpExchange(")
                    .contains("@CookieValue(\"session-id\", required = false) sessionId: String?")
                    .contains("method=\"POST\"")
                    .contains("): Subject")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("public class SubjectsClient")
                    .contains("NetworkResult<Subject>")
                    .contains("cookie(\"session-id\", it.toString())")
                    .contains("basePath: String = \"https://example.test/api\"")
        }
    }

    private val openApi =
        """
        openapi: 3.1.1
        info:
          title: Native client
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
            post:
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
