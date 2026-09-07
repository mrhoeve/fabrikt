package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths
import java.util.stream.Stream

class CodeGeneratorNativeServerModeTest {
    @Test
    fun `uses generated inline model names in native endpoint contracts`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.HTTP_MODELS, CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.SPRING,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(inlineEndpointModelsOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("public enum class Status(")
            .contains("status: List<Status>?")
            .contains("public data class CreateTokenRequest(")
            .contains("createTokenRequest: CreateTokenRequest")
            .contains("public data class CreateToken200Response(")
            .contains("ResponseEntity<CreateToken200Response>")
            .doesNotContain("models.Items")
            .doesNotContain("models.Schema")
    }

    @ParameterizedTest
    @MethodSource("nativeServerConfigurations")
    fun `generates usable server contracts from native operations`(
        version: String,
        target: ControllerCodeGenTargetType,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi.replace("VERSION", version)),
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
        openapi: VERSION
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

    private val inlineEndpointModelsOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native inline endpoint models
          version: "1.0"
        paths:
          /subjects:
            get:
              operationId: listSubjects
              parameters:
                - name: status
                  in: query
                  schema:
                    type: array
                    items:
                      type: string
                      enum: [active, inactive]
              responses:
                '204': { description: No content }
          /auth/token:
            post:
              operationId: createToken
              requestBody:
                required: true
                content:
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
                      required: [client_id]
                      properties:
                        client_id: { type: string }
              responses:
                '200':
                  description: Created
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [access_token]
                        properties:
                          access_token: { type: string }
        """.trimIndent()

    companion object {
        @JvmStatic
        fun nativeServerConfigurations(): Stream<Arguments> =
            Stream.of("3.0.4", "3.1.2", "3.2.0").flatMap { version ->
                ControllerCodeGenTargetType.entries.stream().map { target -> Arguments.of(version, target) }
            }
    }
}
