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

class CodeGeneratorNativeWebhookTest {
    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class)
    fun `generates unbound native webhook handler contracts`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CONTROLLERS), controllerTarget = target)

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
            .contains("public interface OrderChangedWebhookHandler")
            .contains("receiveOrderChange(")
            .contains("orderChange: OrderChange")
            .contains("traceId: String? = null")
            .contains("Acknowledgement")
        if (target == ControllerCodeGenTargetType.KTOR) {
            assertThat(generated).contains("public suspend fun receiveOrderChange(")
        } else {
            assertThat(generated).contains("public fun receiveOrderChange(")
        }
    }

    private val openApi =
        """
        openapi: 3.1.1
        info: { title: Webhooks, version: "1.0" }
        webhooks:
          orderChanged:
            post:
              operationId: receiveOrderChange
              parameters:
                - name: trace-id
                  in: header
                  schema: { type: string }
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/OrderChange' }
              responses:
                '200':
                  description: Accepted
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Acknowledgement' }
        components:
          schemas:
            OrderChange:
              type: object
              required: [id]
              properties:
                id: { type: string }
            Acknowledgement:
              type: object
              required: [accepted]
              properties:
                accepted: { type: boolean }
        """.trimIndent()
}
