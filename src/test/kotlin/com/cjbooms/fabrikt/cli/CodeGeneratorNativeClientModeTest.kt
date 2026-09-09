package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SimpleFile
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

class CodeGeneratorNativeClientModeTest {
    @Test
    fun `keeps model suffixes at the end of directional native model names`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.SPRING_HTTP_INTERFACE,
            modelSuffix = "Dto",
        )

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
            .contains("public data class SubjectRequestDto(")
            .contains("public data class SubjectResponseDto(")
            .contains("subjectRequestDto: SubjectRequestDto")
            .contains("): SubjectResponseDto")
            .doesNotContain("SubjectDtoRequest", "SubjectDtoResponse")
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates usable clients from native operations`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generatedFiles =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        val generated =
            generatedFiles.joinToString("\n") { file ->
                when (file) {
                    is KotlinSourceSet -> file.files.joinToString("\n")
                    is SimpleFile -> file.content
                    else -> ""
                }
            }

        assertThat(generated)
            .contains("public data class Subject(")
            .contains("findSubject")
            .contains("id: Int")
            .contains("includeInactive: Boolean? = null")
            .contains("sessionId: String? = null")
            .contains("subjectRequest: SubjectRequest")
        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("public class SubjectsClient")
                    .contains("ApiResponse<SubjectResponse>")
                    .contains(".pathParam(\"{id}\" to id)")
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated)
                    .contains("public interface SubjectsClient")
                    .contains("@RequestLine(\"POST /subjects/{id}?includeInactive={includeInactive}\")")
                    .contains("\"Cookie: {cookieHeader}\"")
                    .contains("): SubjectResponse")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated)
                    .contains("public interface SubjectsClient")
                    .contains("@HttpExchange(")
                    .contains("@CookieValue(\"session-id\", required = false) sessionId: String?")
                    .contains("method=\"POST\"")
                    .contains("): SubjectResponse")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("public class SubjectsClient")
                    .contains("NetworkResult<SubjectResponse>")
                    .contains("cookie(\"session-id\", it.toString())")
                    .contains("basePath: String = \"https://example.test/api\"")
        }
    }

    @Test
    fun `uses a successful response body instead of informational redirects or empty responses`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.SPRING_HTTP_INTERFACE,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(responseSelectionOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("public fun findSubject(")
            .contains("): Subject")
    }

    @Test
    fun `generates multipart Spring HTTP interface clients from native operations`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.SPRING_HTTP_INTERFACE,
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
            .contains("contentType=\"multipart/form-data\"")
            .contains("@RequestPart(\"document\") document: ByteArray")
            .contains("@RequestPart(\"description\") description: String?")
    }

    @Test
    fun `generates multipart OpenFeign clients from native operations`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OPEN_FEIGN,
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
            .contains("@Headers(\"Content-Type: multipart/form-data\")")
            .contains("@Param(\"document\") document: ByteArray")
            .contains("@Param(\"description\") description: String?")
    }

    @ParameterizedTest
    @EnumSource(SerializationLibrary::class)
    fun `generates multipart Ktor clients from native operations`(serializationLibrary: SerializationLibrary) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
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
            .contains("MultiPartFormDataContent(")
            .contains("formData {")
            .contains("\"document\",\n                document,")
            .contains("description?.let { part ->")
            .contains("documents?.forEach { part ->")
            .contains("filename=\\\"document\\\"")
        when (serializationLibrary) {
            SerializationLibrary.JACKSON ->
                assertThat(generated)
                    .contains("com.fasterxml.jackson.databind.json.JsonMapper")
                    .contains("multipartObjectMapper.writeValueAsString(part)")
            SerializationLibrary.JACKSON_3 ->
                assertThat(generated)
                    .contains("tools.jackson.databind.json.JsonMapper")
                    .contains("multipartObjectMapper.writeValueAsString(part)")
            SerializationLibrary.KOTLINX_SERIALIZATION ->
                assertThat(generated)
                    .contains("Json.encodeToString(part)")
                    .doesNotContain("multipartObjectMapper")
        }
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates form urlencoded clients from native operations`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
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
            .contains("clientSecret: String? = null")
        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("val formBuilder = FormBody.Builder()")
                    .contains("formBuilder.add(\"client_id\", clientId.toString())")
                    .contains(".post(formBody)")
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated)
                    .contains("@Body(\"client_id={client_id}&client_secret={client_secret}\")")
                    .contains("@Param(\"client_id\") clientId: String")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated)
                    .contains("contentType=\"application/x-www-form-urlencoded\"")
                    .contains("@RequestParam(\"client_id\", required = true) clientId: String")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("FormDataContent(")
                    .contains("append(\"client_id\", clientId.toString())")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `applies array encoding to native form clients`(targetName: String) {
        val target = ClientCodeGenTargetType.valueOf(targetName)
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(encodedFormOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated).contains("scopes.joinToString(\"|\")")
    }

    @Test
    fun `rejects form arrays that OpenFeign cannot serialize correctly`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OPEN_FEIGN,
        )

        assertThatThrownBy {
            CodeGenerator(
                Packages("com.example"),
                SourceApi(encodedFormOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("OpenFeign client does not support native form arrays")
            .hasMessageContaining("POST /tokens (scopes)")
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates required API key credentials from native security schemes`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(apiKeyOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("headerKey: String")
            .contains("queryKey: String")
            .contains("cookieKey: String")
        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains(".queryParam(\"api_key\", queryKey)")
                    .contains(".`header`(\"X-API-Key\", headerKey)")
                    .contains("\"session_key=\"")
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated)
                    .contains("api_key={queryKey}")
                    .contains("\"X-API-Key: {headerKey}\"")
                    .contains("\"Cookie: {cookieHeader}\"")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated)
                    .contains("@RequestHeader(\"X-API-Key\") headerKey: String")
                    .contains("@RequestParam(\"api_key\") queryKey: String")
                    .contains("@CookieValue(\"session_key\", required = true) cookieKey: String")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("`header`(\"X-API-Key\", headerKey)")
                    .contains("api_key=${'$'}{queryKey}")
                    .contains("cookie(\"session_key\", cookieKey.toString())")
        }
    }

    @Test
    fun `does not duplicate explicitly declared API key parameters`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.SPRING_HTTP_INTERFACE,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(apiKeyOpenApi.replace("parameters: []", explicitApiKeyParameter)),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated).containsOnlyOnce("@RequestHeader(\"X-API-Key\"")
    }

    private val openApi =
        """
        openapi: 3.1.1
        info:
          title: Native client
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
            post:
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

    private val responseSelectionOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native responses
          version: "1.0"
        paths:
          /subjects:
            get:
              operationId: findSubject
              responses:
                '100':
                  description: Continue
                  content:
                    application/json:
                      schema: { type: string }
                '204':
                  description: Empty success
                2XX:
                  description: Found
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Subject' }
                '300':
                  description: Redirect
                  content:
                    application/json:
                      schema: { type: integer }
        components:
          schemas:
            Subject:
              type: object
              properties:
                id: { type: string }
        """.trimIndent()

    private val multipartOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native multipart client
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
                      required: [document]
                      properties:
                        document: { type: string, format: binary }
                        description: { type: string }
                        documents:
                          type: array
                          items: { type: string, format: binary }
                        metadata:
                          type: object
                          properties:
                            title: { type: string }
              responses:
                '204':
                  description: Uploaded
        """.trimIndent()

    private val formOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native form client
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
                        client_secret: { type: string }
              responses:
                '204': { description: Created }
        """.trimIndent()

    private val encodedFormOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Encoded native form client
          version: "1.0"
        paths:
          /tokens:
            post:
              requestBody:
                content:
                  application/x-www-form-urlencoded:
                    schema:
                      type: object
                      required: [scopes]
                      properties:
                        scopes:
                          type: array
                          items: { type: string }
                    encoding:
                      scopes:
                        style: pipeDelimited
                        explode: false
              responses:
                '204': { description: Created }
        """.trimIndent()

    private val apiKeyOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native API keys
          version: "1.0"
        security:
          - HeaderKey: []
            QueryKey: []
            CookieKey: []
        paths:
          /secured:
            get:
              parameters: []
              responses:
                '204': { description: ok }
        components:
          securitySchemes:
            HeaderKey:
              type: apiKey
              name: X-API-Key
              in: header
            QueryKey:
              type: apiKey
              name: api_key
              in: query
            CookieKey:
              type: apiKey
              name: session_key
              in: cookie
        """.trimIndent()

    private val explicitApiKeyParameter =
        "parameters:\n" +
            "              - name: X-API-Key\n" +
            "                in: header\n" +
            "                required: true\n" +
            "                schema: { type: string }"
}
