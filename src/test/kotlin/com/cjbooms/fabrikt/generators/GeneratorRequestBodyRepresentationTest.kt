package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.model.BodyParameter
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorOperationDocument
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorRequestBodyRepresentationTest {
    @Test
    fun `retains a typed body shared by multiple media types`() {
        val body = bodyParameter(sharedSchemaOpenApi)

        assertThat(body.name).isEqualTo("payload")
        assertThat(body.type.toString()).isEqualTo("com.example.models.Payload")
        assertThat(body.contentTypes).containsExactly("application/json", "application/vnd.example+json")
    }

    @Test
    fun `uses the serializer JSON tree for heterogeneous JSON bodies`() {
        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.JACKSON_3)
        assertThat(bodyParameter(heterogeneousJsonOpenApi).type.toString()).isEqualTo("tools.jackson.databind.JsonNode")

        MutableSettings.updateSettings(serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION)
        assertThat(bodyParameter(heterogeneousJsonOpenApi).type.toString()).isEqualTo("kotlinx.serialization.json.JsonElement")
    }

    @Test
    fun `uses raw bytes for heterogeneous bodies that are not all JSON`() {
        MutableSettings.updateSettings()

        val body = bodyParameter(heterogeneousMediaOpenApi)

        assertThat(body.name).isEqualTo("body")
        assertThat(body.type.toString()).isEqualTo("kotlin.ByteArray")
        assertThat(body.contentTypes).containsExactly("application/json", "text/plain")
    }

    private fun bodyParameter(openApi: String): BodyParameter {
        val document = OpenApiDocumentParser.parse(openApi)
        val context =
            GeneratorEndpointContext(
                document.toGeneratorOperationDocument(SchemaGenerationMode.NATIVE),
                document.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
                "com.example",
            )
        val operation =
            context.operations.paths
                .single()
                .operations
                .single()
        return context.incomingParameters(operation, emptyList()).single() as BodyParameter
    }

    private val sharedSchemaOpenApi =
        openApi(
            """
            application/json:
              schema: { ${'$'}ref: '#/components/schemas/Payload' }
            application/vnd.example+json:
              schema: { ${'$'}ref: '#/components/schemas/Payload' }
            """.trimIndent(),
        )

    private val heterogeneousJsonOpenApi =
        openApi(
            """
            application/json:
              schema: { ${'$'}ref: '#/components/schemas/Payload' }
            application/vnd.example+json:
              schema: { type: string }
            """.trimIndent(),
        )

    private val heterogeneousMediaOpenApi =
        openApi(
            """
            application/json:
              schema: { ${'$'}ref: '#/components/schemas/Payload' }
            text/plain:
              schema: { type: string }
            """.trimIndent(),
        )

    private fun openApi(content: String): String =
        listOf(
            "openapi: 3.1.1",
            "info: { title: Request content, version: \"1.0\" }",
            "paths:",
            "  /payloads:",
            "    post:",
            "      requestBody:",
            "        required: true",
            "        content:",
            content.prependIndent("          "),
            "      responses:",
            "        '204': { description: Accepted }",
            "components:",
            "  schemas:",
            "    Payload:",
            "      type: object",
            "      required: [value]",
            "      properties:",
            "        value: { type: string }",
        ).joinToString("\n")
}
