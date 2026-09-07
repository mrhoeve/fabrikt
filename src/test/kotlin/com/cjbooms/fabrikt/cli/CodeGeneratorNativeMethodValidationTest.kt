package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
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
}
