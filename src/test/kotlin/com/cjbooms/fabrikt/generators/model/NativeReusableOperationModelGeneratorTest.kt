package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.ParsedOpenApiDocument
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class NativeReusableOperationModelGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetSettings() {
        MutableSettings.updateSettings()
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates models from reusable callbacks across serializers`(version: String) {
        SerializationLibrary.entries.forEach { library ->
            MutableSettings.updateSettings(serializationLibrary = library)

            val generated = generate(callbackOpenApi(version))

            assertThat(generated.keys).containsExactly("CallbackMode", "ReceiveEventRequest", "ReceiveEvent200Response")
            assertThat(generated.getValue("CallbackMode")).contains("SINGLE(\"single\")")
            assertThat(generated.getValue("ReceiveEventRequest"))
                .contains("public val eventId: String")
                .contains(if (library == SerializationLibrary.KOTLINX_SERIALIZATION) "SerialName" else "JsonProperty")
            assertThat(generated.getValue("ReceiveEvent200Response")).contains("public val accepted: Boolean")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.1.2", "3.2.0"])
    fun `generates models from reusable path items`(version: String) {
        val generated = generate(pathItemOpenApi(version))

        assertThat(generated.keys)
            .containsExactly("SharedFilter", "DetailLevel", "GetSubjectRequest", "GetSubject200Response")
        assertThat(generated.getValue("SharedFilter")).contains("public val query: String")
        assertThat(generated.getValue("DetailLevel")).contains("COMPLETE(\"complete\")")
        assertThat(generated.getValue("GetSubjectRequest")).contains("public val subjectId: String")
        assertThat(generated.getValue("GetSubject200Response")).contains("public val name: String")
    }

    @Test
    fun `generates reusable operation models from external schemas`() {
        Files.writeString(
            tempDir.resolve("event.yaml"),
            """
            type: object
            required: [eventId]
            properties:
              eventId: { type: string }
            """.trimIndent(),
        )
        val parsed =
            OpenApiDocumentParser.parse(
                input = externalCallbackOpenApi,
                baseUri = tempDir.toUri(),
                documentUri = tempDir.resolve("openapi.yaml").toUri(),
            )
        val generated = generate(parsed)

        assertThat(generated).containsOnlyKeys("ReceiveEventRequest")
        assertThat(generated.getValue("ReceiveEventRequest")).contains("public val eventId: String")
    }

    @Test
    fun `disambiguates reusable operation models from component schemas`() {
        val generated = generate(collidingCallbackOpenApi)

        assertThat(generated.keys).containsExactly("ReceiveEventRequest", "ReceiveEventRequestExtra")
        assertThat(generated.getValue("ReceiveEventRequest")).contains("public val componentValue: String")
        assertThat(generated.getValue("ReceiveEventRequestExtra")).contains("public val callbackValue: String")
    }

    @Test
    fun `uses the reusable path item name when an operation id is absent`() {
        val generated = generate(pathItemWithoutOperationIdOpenApi)

        assertThat(generated).containsOnlyKeys("GetSubjectOperations200Response")
        assertThat(generated.getValue("GetSubjectOperations200Response")).contains("public val subjectId: String")
    }

    private fun generate(input: String): Map<String, String> = generate(OpenApiDocumentParser.parse(input))

    private fun generate(parsed: ParsedOpenApiDocument): Map<String, String> =
        NativeModelGenerator("com.example")
            .generate(GeneratorModelDescriptorBuilder.build(parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE)))
            .files
            .associate { it.name to it.toString() }

    private fun callbackOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          callbacks:
            EventCallback:
              '{${'$'}request.body#/callbackUrl}':
                post:
                  operationId: receiveEvent
                  parameters:
                    - name: callback-mode
                      in: query
                      schema:
                        type: string
                        enum: [single, batch]
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [eventId]
                          properties:
                            eventId: { type: string }
                  responses:
                    '200':
                      description: Received
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [accepted]
                            properties:
                              accepted: { type: boolean }
        """.trimIndent()

    private fun pathItemOpenApi(version: String) =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          pathItems:
            SubjectOperations:
              parameters:
                - name: shared-filter
                  in: query
                  schema:
                    type: object
                    required: [query]
                    properties:
                      query: { type: string }
              get:
                operationId: getSubject
                parameters:
                  - name: detail-level
                    in: query
                    schema:
                      type: string
                      enum: [summary, complete]
                requestBody:
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [subjectId]
                        properties:
                          subjectId: { type: string }
                responses:
                  '200':
                    description: Subject
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name]
                          properties:
                            name: { type: string }
        """.trimIndent()

    private val externalCallbackOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          callbacks:
            EventCallback:
              '{${'$'}request.body#/callbackUrl}':
                post:
                  operationId: receiveEvent
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: './event.yaml'
                  responses:
                    '204': { description: Received }
        """.trimIndent()

    private val collidingCallbackOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            ReceiveEventRequest:
              type: object
              required: [componentValue]
              properties:
                componentValue: { type: string }
          callbacks:
            EventCallback:
              '{${'$'}request.body#/callbackUrl}':
                post:
                  operationId: receiveEvent
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [callbackValue]
                          properties:
                            callbackValue: { type: string }
                  responses:
                    '204': { description: Received }
        """.trimIndent()

    private val pathItemWithoutOperationIdOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          pathItems:
            SubjectOperations:
              get:
                responses:
                  '200':
                    description: Subject
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [subjectId]
                          properties:
                            subjectId: { type: string }
        """.trimIndent()
}
