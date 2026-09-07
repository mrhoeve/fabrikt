package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class NativeHeaderMediaModelGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @Test
    fun `generates native header and sequential media models`() {
        val generated = generate(headerMediaOpenApi)

        assertThat(generated.keys)
            .containsExactly(
                "TraceContext",
                "ComponentStreamItem",
                "SequenceMetadata",
                "StreamEventsRequestItem",
                "RateLimit",
                "StreamEvents200Response",
                "StreamEvents200ResponseItem",
            )
        assertThat(generated.getValue("TraceContext")).contains("public val traceId: String")
        assertThat(generated.getValue("ComponentStreamItem")).contains("public val sequence: Long")
        assertThat(generated.getValue("SequenceMetadata")).contains("public val source: String")
        assertThat(generated.getValue("StreamEventsRequestItem")).contains("public val requestId: String")
        assertThat(generated.getValue("RateLimit")).contains("public val limit: Long")
        assertThat(generated.getValue("StreamEvents200ResponseItem")).contains("public val eventId: String")
    }

    @Test
    fun `keeps header and media models portable across serializers`() {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generate(headerMediaOpenApi)

            assertThat(generated.getValue("RateLimit"))
                .contains("public val limit: Long")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "SerialName" else "JsonProperty")
            assertThat(generated.getValue("StreamEvents200ResponseItem"))
                .contains("public val eventId: String")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "SerialName" else "JsonProperty")
        }
    }

    @Test
    fun `generates component header models from external schemas`() {
        Files.writeString(
            tempDir.resolve("trace-context.yaml"),
            """
            type: object
            required: [traceId]
            properties:
              traceId: { type: string }
            """.trimIndent(),
        )
        val parsed =
            OpenApiDocumentParser.parse(
                input = externalHeaderOpenApi,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        val generated = generate(parsed)

        assertThat(generated).containsOnlyKeys("TraceContext")
        assertThat(generated.getValue("TraceContext")).contains("public val traceId: String")
    }

    @Test
    fun `disambiguates header models from existing schema names`() {
        val generated = generate(collidingHeaderOpenApi)

        assertThat(generated.keys).containsExactly("RateLimit", "RateLimitExtra")
        assertThat(generated.getValue("RateLimit")).contains("public val componentValue: String")
        assertThat(generated.getValue("RateLimitExtra")).contains("public val headerValue: String")
    }

    private fun generate(input: String): Map<String, String> = generate(OpenApiDocumentParser.parse(input))

    private fun generate(parsed: com.cjbooms.fabrikt.parser.ParsedOpenApiDocument): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)))
            .files
            .associate { it.name to it.toString() }

    private val headerMediaOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /events:
            query:
              operationId: streamEvents
              requestBody:
                content:
                  application/json-seq:
                    itemSchema:
                      type: object
                      required: [requestId]
                      properties:
                        requestId: { type: string }
              responses:
                '200':
                  description: Stream
                  headers:
                    Rate-Limit:
                      schema:
                        type: object
                        required: [limit]
                        properties:
                          limit: { type: integer, format: int64 }
                  content:
                    application/json-seq:
                      schema:
                        type: object
                        required: [page]
                        properties:
                          page: { type: integer }
                      itemSchema:
                        type: object
                        required: [eventId]
                        properties:
                          eventId: { type: string }
        components:
          headers:
            TraceContext:
              schema:
                type: object
                required: [traceId]
                properties:
                  traceId: { type: string }
          mediaTypes:
            ComponentStream:
              itemSchema:
                type: object
                required: [sequence]
                properties:
                  sequence: { type: integer, format: int64 }
              itemEncoding:
                headers:
                  Sequence-Metadata:
                    schema:
                      type: object
                      required: [source]
                      properties:
                        source: { type: string }
        """.trimIndent()

    private val externalHeaderOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          headers:
            TraceContext:
              schema:
                ${'$'}ref: './trace-context.yaml'
        """.trimIndent()

    private val collidingHeaderOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            get:
              responses:
                '200':
                  description: Success
                  headers:
                    Rate-Limit:
                      schema:
                        type: object
                        required: [headerValue]
                        properties:
                          headerValue: { type: string }
        components:
          schemas:
            RateLimit:
              type: object
              required: [componentValue]
              properties:
                componentValue: { type: string }
        """.trimIndent()
}
