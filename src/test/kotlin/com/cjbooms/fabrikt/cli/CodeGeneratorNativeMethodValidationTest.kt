package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Paths

class CodeGeneratorNativeMethodValidationTest {
    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class)
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
    @EnumSource(ClientCodeGenTargetType::class, names = ["OK_HTTP", "KTOR"])
    fun `rejects OpenAPI 3_2 query operations unsupported by client targets`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        assertThatIllegalArgumentException()
            .isThrownBy { generator(queryOpenApi).generate() }
            .withMessageContaining("client")
            .withMessageContaining("QUERY")
    }

    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class, names = ["MICRONAUT", "KTOR"])
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

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class, names = ["OPEN_FEIGN", "SPRING_HTTP_INTERFACE", "KTOR"])
    fun `rejects multipart operations unsupported by client targets`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        assertThatIllegalArgumentException()
            .isThrownBy { generator(multipartOpenApi).generate() }
            .withMessageContaining("multipart")
            .withMessageContaining("POST /subjects")
    }

    @Test
    fun `allows multipart generation for Spring controllers and OkHttp clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.SPRING,
        )
        assertThatNoException().isThrownBy { generator(multipartOpenApi).generate() }

        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
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
              responses:
                '200':
                  description: Found
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
