package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Paths

class CodeGeneratorNativeMethodValidationTest {
    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class, names = ["SPRING"])
    fun `rejects OpenAPI 3_2 query operations unsupported by controller targets`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        assertThatIllegalArgumentException()
            .isThrownBy { generator(queryOpenApi).generate() }
            .withMessageContaining("${target.name.lowercase().replaceFirstChar(Char::uppercase)} controller")
            .withMessageContaining("QUERY")
    }

    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class, names = ["MICRONAUT", "KTOR"])
    fun `generates OpenAPI 3_2 query operations for supported controller targets`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        val generated =
            generator(queryOpenApi)
                .generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        when (target) {
            ControllerCodeGenTargetType.MICRONAUT ->
                assertThat(generated)
                    .contains("@CustomHttpMethod")
                    .contains("method = \"QUERY\"")
            ControllerCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("route(\"/subjects\", HttpMethod(\"QUERY\"))")
                    .contains("handle {")
            ControllerCodeGenTargetType.SPRING -> error("Spring custom methods are rejected before generation")
        }
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates OpenAPI 3_2 query operations for client targets`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            generator(queryOpenApi)
                .generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("\"QUERY\",")
                    .contains("objectMapper.writeValueAsString(querySubjectsRequest)")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("httpClient.request(url)")
                    .contains("method = HttpMethod(\"QUERY\")")
                    .contains("setBody(querySubjectsRequest)")
            ClientCodeGenTargetType.OPEN_FEIGN -> assertThat(generated).contains("@RequestLine(\"QUERY /subjects\")")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE -> assertThat(generated).contains("method=\"QUERY\"")
        }
    }

    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class, names = ["KTOR"])
    fun `rejects multipart operations unsupported by controller targets`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        assertThatIllegalArgumentException()
            .isThrownBy { generator(multipartOpenApi).generate() }
            .withMessageContaining("multipart")
            .withMessageContaining("POST /subjects")
    }

    @Test
    fun `allows multipart generation for supported controllers and clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.SPRING,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }

        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.MICRONAUT,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }

        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }

        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.SPRING_HTTP_INTERFACE,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }

        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OPEN_FEIGN,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }

        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }
    }

    @Test
    fun `rejects multipart methods unsupported by OkHttp clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        assertThatIllegalArgumentException()
            .isThrownBy { generator(multipartOpenApi.replace("post:", "delete:")).generate() }
            .withMessageContaining("OkHttp client")
            .withMessageContaining("DELETE /subjects")
    }

    private fun generator(openApi: String) =
        CodeGenerator(
            Packages("com.example"),
            SourceApi(openApi),
            Paths.get(""),
            Paths.get(""),
            SchemaGenerationMode.NATIVE,
        )

    private val queryOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Native methods
          version: "1.0"
        paths:
          /subjects:
            query:
              operationId: querySubjects
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [filter]
                      properties:
                        filter: { type: string }
              responses:
                '200':
                  description: Found
                  content:
                    application/json:
                      schema:
                        type: array
                        items: { type: string }
        """.trimIndent()

    private val multipartOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native multipart
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              requestBody:
                required: true
                content:
                  multipart/form-data:
                    schema:
                      type: object
                      required: [document]
                      properties:
                        document: { type: string, format: binary }
                        description: { type: string }
              responses:
                '204':
                  description: Created
        """.trimIndent()
}
