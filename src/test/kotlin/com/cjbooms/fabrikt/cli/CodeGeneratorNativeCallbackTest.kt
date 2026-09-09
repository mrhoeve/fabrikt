package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Paths

class CodeGeneratorNativeCallbackTest {
    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates native callback sender contracts with runtime destinations`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CLIENT), clientTarget = target)

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
            .contains("public interface StatusCallbackSender")
            .contains("sendStatus(")
            .contains("callbackUrl2: String")
            .contains("statusUpdate: StatusUpdate")
            .contains("callbackUrl: String? = null")
            .contains("StatusAcknowledgement")
            .contains("evaluating `{${'$'}request.body#/callbackUrl}`")
        if (target == ClientCodeGenTargetType.KTOR) {
            assertThat(generated).contains("public suspend fun sendStatus(")
        }
    }

    @Test
    fun `keeps operation scoped callbacks with the same name distinct`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.SPRING_HTTP_INTERFACE,
        )

        val generated = generate(duplicateCallbackOpenApi)

        assertThat(generated)
            .contains("public interface CreateFirstResultCallbackSender")
            .contains("public interface CreateSecondResultCallbackSender")
    }

    private fun generate(specification: String): String =
        CodeGenerator(
            Packages("com.example"),
            SourceApi(specification),
            Paths.get(""),
            Paths.get(""),
            SchemaGenerationMode.NATIVE,
        ).generate()
            .filterIsInstance<KotlinSourceSet>()
            .flatMap { it.files }
            .joinToString("\n")

    private val openApi =
        """
        openapi: 3.1.1
        info: { title: Callbacks, version: "1.0" }
        paths:
          /subscriptions:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        callbackUrl: { type: string, format: uri }
              callbacks:
                statusCallback:
                  '{${'$'}request.body#/callbackUrl}':
                    post:
                      operationId: sendStatus
                      parameters:
                        - name: callback-url
                          in: header
                          schema: { type: string }
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: { ${'$'}ref: '#/components/schemas/StatusUpdate' }
                      responses:
                        '200':
                          description: Accepted
                          content:
                            application/json:
                              schema: { ${'$'}ref: '#/components/schemas/StatusAcknowledgement' }
              responses:
                '202': { description: Accepted }
        components:
          schemas:
            StatusUpdate:
              type: object
              properties:
                state: { type: string }
            StatusAcknowledgement:
              type: object
              properties:
                accepted: { type: boolean }
        """.trimIndent()

    private val duplicateCallbackOpenApi =
        """
        openapi: 3.1.1
        info: { title: Scoped callbacks, version: "1.0" }
        paths:
          /first:
            post:
              operationId: createFirst
              callbacks:
                resultCallback:
                  '{${'$'}request.body#/url}':
                    post:
                      responses: { '204': { description: ok } }
              responses: { '202': { description: ok } }
          /second:
            post:
              operationId: createSecond
              callbacks:
                resultCallback:
                  '{${'$'}request.body#/url}':
                    delete:
                      responses: { '204': { description: ok } }
              responses: { '202': { description: ok } }
        """.trimIndent()
}
