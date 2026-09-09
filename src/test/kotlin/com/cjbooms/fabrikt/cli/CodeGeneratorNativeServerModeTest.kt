package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
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
            .contains("@RequestParam(value = \"client_id\", required = true) clientId: String")
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
            .contains("subjectRequest: SubjectRequest")
        when (target) {
            ControllerCodeGenTargetType.SPRING ->
                assertThat(generated)
                    .contains("@RequestMapping(")
                    .contains("ResponseEntity<SubjectResponse>")
            ControllerCodeGenTargetType.MICRONAUT ->
                assertThat(generated)
                    .contains("@Get(uri = \"/subjects/{id}\")")
                    .contains("HttpResponse<SubjectResponse>")
            ControllerCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("public fun Route.subjectsRoutes(")
                    .contains("call.request.headers.getTypedCookie<kotlin.String>(\"session-id\"")
                    .contains("call: TypedApplicationCall<SubjectResponse>")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `parses typed native Ktor cookie parameters from their raw wire values`(version: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.KTOR,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(
                    cookieOpenApi.replace("VERSION", version).replace(
                        "STYLE",
                        if (version ==
                            "3.2.0"
                        ) {
                            "style: cookie"
                        } else {
                            "style: form"
                        },
                    ),
                ),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("tenantId: Int")
            .contains("mode: Mode?")
            .contains("scopes: List<String>")
            .contains("labels: List<Labels>?")
            .contains("getTypedCookieOrFail<kotlin.Int>(\"tenant-id\"")
            .contains("getTypedCookie<com.example.models.Mode>(\"mode\"")
            .contains("getTypedCookieOrFail<kotlin.collections.List<kotlin.String>>(\"scopes\"")
            .contains("getTypedCookie<kotlin.collections.List<com.example.models.Labels>>(\"labels\"")
            .contains("getAll(HttpHeaders.Cookie).orEmpty()")
            .contains("header.split(\";\")")
            .contains("cookie.substring(separator + 1).trim()")
            .doesNotContain("call.request.cookies[\"tenant-id\"]")
    }

    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class)
    fun `preserves percent signs in native operation documentation`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(percentEncodedDocumentationOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated).contains("Returns names with spaces encoded as %20.")
    }

    @Test
    fun `generates multipart Micronaut controllers from native operations`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.MICRONAUT,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("@Consumes(value = [\"multipart/form-data\"])")
            .contains("@Part(value = \"document\") @Valid document: ByteArray")
            .contains("@Part(value = \"attachments\") @Valid attachments: List<ByteArray>?")
            .contains("@Part(value = \"description\") @Valid description: String?")
            .contains("@Part(value = \"subject-metadata\") @Valid subjectMetadata: SubjectMetadata")
            .doesNotContain("@Body")
    }

    @ParameterizedTest
    @EnumSource(SerializationLibrary::class)
    fun `generates multipart Ktor controllers from native operations`(serializationLibrary: SerializationLibrary) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.KTOR,
            serializationLibrary = serializationLibrary,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("document: ByteArray")
            .contains("attachments: List<")
            .contains("priority: Int")
            .contains("labels: List<String>?")
            .contains("history: List<SubjectMetadata>?")
            .contains("val multipartData = call.receiveMultipart()")
            .contains("multipartData.forEachPart { part ->")
            .contains("documentPart = part.provider().toByteArray()")
            .contains("attachmentsParts += part.provider().toByteArray()")
            .contains("parametersOf(\"priority\", priorityParts).getTypedOrFail<Int>(\"priority\")")
            .contains("val attachments = attachmentsParts.takeIf { it.isNotEmpty() }")
            .contains("MissingRequestParameterException(\"subject-metadata\")")
            .contains("part.dispose()")

        when (serializationLibrary) {
            SerializationLibrary.JACKSON ->
                assertThat(generated)
                    .contains("com.fasterxml.jackson.databind.json.JsonMapper")
                    .contains("attachments: List<ByteArray>?")
                    .contains("multipartObjectMapper.readValue(part.value,")
                    .contains("SubjectMetadata::class.java")
            SerializationLibrary.JACKSON_3 ->
                assertThat(generated)
                    .contains("tools.jackson.databind.json.JsonMapper")
                    .contains("attachments: List<ByteArray>?")
                    .contains("multipartObjectMapper.readValue(part.value,")
                    .contains("SubjectMetadata::class.java")
            SerializationLibrary.KOTLINX_SERIALIZATION ->
                assertThat(generated)
                    .contains("attachments: List<@Contextual ByteArray>?")
                    .contains("Json.decodeFromString<SubjectMetadata>(part.value)")
                    .doesNotContain("multipartObjectMapper")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `reads typed multipart part headers in native Ktor controllers`(version: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.KTOR,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipartHeaderOpenApi.replace("VERSION", version)),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("documentXChecksum: String")
            .contains("attachmentsXSequence: List<Int>? = null")
            .contains("attachmentsXNote: List<String?>? = null")
            .contains("var documentXChecksumRawPart: String? = null")
            .contains("val attachmentsXSequenceRawParts = mutableListOf<String?>()")
            .contains("documentXChecksumRawPart = part.headers[\"X-Checksum\"]")
            .contains("attachmentsXSequenceRawParts += part.headers[\"X-Sequence\"]")
            .contains("Multipart part header X-Sequence is required")
            .contains("metadataXAttributes: XAttributes")
            .contains("val encodedFields = (metadataXAttributesRawPart")
            .contains("headerFields[\"code\"]")
            .contains("metadataXTags: Map<String, String?>?")
            .doesNotContain("documentContentType")
    }

    @ParameterizedTest
    @ValueSource(strings = ["SPRING", "MICRONAUT"])
    fun `rejects multipart part headers for native annotation based controllers`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        assertThatThrownBy {
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipartHeaderOpenApi.replace("VERSION", "3.2.0")),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot represent native multipart part headers")
            .hasMessageContaining("POST /documents")
    }

    @ParameterizedTest
    @EnumSource(ControllerCodeGenTargetType::class)
    fun `generates form urlencoded controllers from native operations`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(formOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("clientId: String")
            .contains("scopes: List<String>?")
        when (target) {
            ControllerCodeGenTargetType.SPRING ->
                assertThat(generated).contains("@RequestParam(value = \"client_id\", required = true) clientId: String")
            ControllerCodeGenTargetType.MICRONAUT ->
                assertThat(generated).contains("@Body(\"client_id\") clientId: String")
            ControllerCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("val formFields = call.receiveParameters()")
                    .contains("formFields.getTypedOrFail<String>(\"client_id\"")
                    .contains("formFields.getTyped<List<String>>(\"scopes\"")
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
              required: [id, secret]
              properties:
                id:
                  type: string
                  readOnly: true
                secret:
                  type: string
                  writeOnly: true
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

    private val multipartOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native multipart server
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
                      required: [document, subject-metadata, priority]
                      properties:
                        document: { type: string, format: binary }
                        attachments:
                          type: array
                          items: { type: string, format: binary }
                        description: { type: string }
                        priority: { type: integer, format: int32 }
                        labels:
                          type: array
                          items: { type: string }
                        subject-metadata:
                          ${'$'}ref: '#/components/schemas/SubjectMetadata'
                        history:
                          type: array
                          items:
                            ${'$'}ref: '#/components/schemas/SubjectMetadata'
              responses:
                '204': { description: Created }
        components:
          schemas:
            SubjectMetadata:
              type: object
              required: [name]
              properties:
                name: { type: string }
        """.trimIndent()

    private val formOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native form server
          version: "1.0"
        paths:
          /tokens:
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
                        scopes:
                          type: array
                          items: { type: string }
              responses:
                '204': { description: Created }
        """.trimIndent()

    private val multipartHeaderOpenApi =
        """
        openapi: VERSION
        info:
          title: Native multipart part headers
          version: "1.0"
        paths:
          /documents:
            post:
              operationId: uploadDocument
              requestBody:
                required: true
                content:
                  multipart/form-data:
                    schema:
                      type: object
                      required: [document, metadata]
                      properties:
                        document: { type: string, format: binary }
                        attachments:
                          type: array
                          items: { type: string, format: binary }
                        metadata:
                          type: object
                          required: [title]
                          properties:
                            title: { type: string }
                    encoding:
                      document:
                        headers:
                          X-Checksum:
                            required: true
                            schema: { type: string }
                          Content-Type:
                            schema: { type: string }
                      attachments:
                        headers:
                          X-Sequence:
                            required: true
                            schema: { type: integer }
                          X-Note:
                            schema: { type: string }
                      metadata:
                        contentType: application/json
                        headers:
                          X-Attributes:
                            required: true
                            explode: true
                            schema:
                              type: object
                              required: [code]
                              properties:
                                code: { type: integer }
                                note: { type: string }
                          X-Tags:
                            schema:
                              type: object
                              additionalProperties: { type: string }
              responses:
                '204': { description: Uploaded }
        """.trimIndent()

    private val percentEncodedDocumentationOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native percent encoded documentation
          version: "1.0"
        paths:
          /subjects:
            get:
              operationId: listSubjects
              summary: Returns names with spaces encoded as %20.
              responses:
                '204': { description: No content }
        """.trimIndent()

    private val cookieOpenApi =
        """
        openapi: VERSION
        info:
          title: Native cookie serialization
          version: "1.0"
        paths:
          /preferences:
            get:
              parameters:
                - name: tenant-id
                  in: cookie
                  required: true
                  STYLE
                  schema: { type: integer }
                - name: mode
                  in: cookie
                  STYLE
                  schema:
                    type: string
                    enum: [fast-mode, safe]
                - name: scopes
                  in: cookie
                  required: true
                  STYLE
                  explode: false
                  schema:
                    type: array
                    items: { type: string }
                - name: labels
                  in: cookie
                  STYLE
                  explode: true
                  schema:
                    type: array
                    items:
                      type: string
                      enum: [primary-label, secondary]
              responses:
                '204': { description: Accepted }
        """.trimIndent()

    companion object {
        @JvmStatic
        fun nativeServerConfigurations(): Stream<Arguments> =
            Stream.of("3.0.4", "3.1.2", "3.2.0").flatMap { version ->
                ControllerCodeGenTargetType.entries.stream().map { target -> Arguments.of(version, target) }
            }
    }
}
