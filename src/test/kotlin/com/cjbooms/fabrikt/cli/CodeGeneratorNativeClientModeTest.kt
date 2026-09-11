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
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths
import java.util.stream.Stream

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
                    .contains("add(\"session-id=\" + it)")
                    .contains("`header`(\"Cookie\", cookieValues.joinToString(\"; \"))")
                    .contains("basePath: String = \"https://example.test/api\"")
        }
    }

    @ParameterizedTest
    @MethodSource("nativeCookieClientConfigurations")
    fun `serializes native cookie parameters without changing their wire values`(
        version: String,
        target: ClientCodeGenTargetType,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
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
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("tenantId: Int")
            .contains("mode: Mode? = null")
            .contains("scopes: List<String>")
            .contains("labels: List<Labels>? = null")
            .contains("mode?.let { add(\"mode=\" + it.value) }")
            .contains("labels?.forEach { add(\"labels=\" + it.value) }")

        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("tenantId.let { add(\"tenant-id=\" + it) }")
                    .contains("scopes.let { add(\"scopes=\" + it.joinToString(\",\")) }")
                    .contains("headerBuilder.add(\"Cookie\", cookieValues.joinToString(\"; \"))")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("add(\"tenant-id=\" + tenantId)")
                    .contains("add(\"scopes=\" + scopes.joinToString(\",\"))")
                    .contains("`header`(\"Cookie\", cookieValues.joinToString(\"; \"))")
                    .doesNotContain("cookie(\"tenant-id\"")
            else -> error("Unsupported test target $target")
        }
    }

    @ParameterizedTest
    @MethodSource("nativeMultipartHeaderClientConfigurations")
    fun `writes typed native multipart part headers`(
        version: String,
        target: ClientCodeGenTargetType,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipartHeaderOpenApi.replace("VERSION", version)),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("documentXChecksum: String")
            .contains("metadataXTraceIds: List<String>? = null")
            .contains("attachmentsXSequence: List<Int>? = null")
            .contains("attachmentsXNote: List<String?>? = null")
            .contains("thumbnailXChecksum: String? = null")
            .doesNotContain("documentContentType")
            .contains("\"X-Checksum\"")
            .contains("documentXChecksum.toString()")
            .contains("\"X-Trace-Ids\"")
            .contains("it.joinToString(\",\")")
            .contains("documentXMode.value")
            .contains("\"code=\"")
            .contains("it.entries.flatMap")

        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("documentPartHeadersBuilder.add")
                    .contains("multipartBuilder.addPart(documentPartHeaders, document.requestBody)")
                    .contains("requireNotNull(attachmentsXSequence?.getOrNull(index))")
                    .contains("attachmentsXNote?.getOrNull(index)?.let")
                    .contains("requireNotNull(thumbnailXChecksum)")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("Headers.build {")
                    .contains("append(\"X-Checksum\", documentXChecksum.toString())")
                    .contains("attachments?.forEachIndexed { index, part ->")
                    .contains("requireNotNull(attachmentsXSequence?.getOrNull(index))")
                    .contains("attachmentsXNote?.getOrNull(index)?.let")
                    .contains("requireNotNull(thumbnailXChecksum)")
            else -> error("Unsupported test target $target")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPEN_FEIGN", "SPRING_HTTP_INTERFACE"])
    fun `rejects native multipart part headers for declarative clients`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
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
    fun `uses JsonElement for heterogeneous JSON responses in native Ktor clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
            serializationLibrary = SerializationLibrary.KOTLINX_SERIALIZATION,
        )

        val generated = generateClient(heterogeneousJsonResponsesOpenApi)

        assertThat(generated)
            .contains("import kotlinx.serialization.json.JsonElement")
            .contains("NetworkResult<JsonElement>")
            .doesNotContain("jackson.databind.JsonNode")
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

    @Test
    fun `generates multipart delete requests for native OkHttp clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipartOpenApi.replace("    post:", "    delete:")),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated).contains(".method(\"DELETE\", multipartBody)")
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
            .contains("application/merge-patch+json")
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

    @Test
    fun `applies multipart encoding content types to native OkHttp clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
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
            .contains("import okhttp3.MultipartBody")
            .contains("val multipartBuilder = MultipartBody.Builder()")
            .doesNotContain("import okhttp3.MultipartBody.Builder")
            .contains("objectMapper.writeValueAsString(metadata)")
            .contains("\"application/merge-patch+json\".toMediaType()")
    }

    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `writes multipart content transfer encodings in native clients`(target: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(target),
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(contentEncodedMultipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("Content-Transfer-Encoding")
            .contains("base64")
            .doesNotContain("documentContentTransferEncoding:")
    }

    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `writes fixed multipart transfer header schemas in native clients`(target: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(target),
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(fixedTransferHeaderMultipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("Content-Transfer-Encoding")
            .contains("base64")
            .doesNotContain("documentContentTransferEncoding:")
    }

    @Test
    fun `rejects conflicting multipart transfer encodings`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
        )

        assertThatThrownBy {
            CodeGenerator(
                Packages("com.example"),
                SourceApi(conflictingTransferEncodingMultipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Content-Transfer-Encoding header does not allow schema contentEncoding 'base64'")
    }

    @Test
    fun `keeps variable multipart transfer headers configurable`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(variableTransferHeaderMultipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("documentContentTransferEncoding:")
            .contains("append(\"Content-Transfer-Encoding\"")
    }

    @Test
    fun `serializes JSON parameter content in native OkHttp clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val generated = generateClient(parameterContentOpenApi)

        assertThat(generated)
            .contains("objectMapper.writeValueAsString(selector)")
            .contains("objectMapper.writeValueAsString(filter)")
            .contains("objectMapper.writeValueAsString(it)")
            .contains("\"X-Filter\"")
            .contains("\"filterState=\"")
    }

    @ParameterizedTest
    @EnumSource(SerializationLibrary::class)
    fun `serializes JSON parameter content in native Ktor clients`(serializationLibrary: SerializationLibrary) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
            serializationLibrary = serializationLibrary,
        )

        val generated = generateClient(parameterContentOpenApi)

        assertThat(generated)
            .contains("fabriktSelectorContentValue")
            .contains("fabriktFilterContentValue")
            .contains("fabriktXFilterContentValue")
            .contains("fabriktFilterStateContentValue")
        if (serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            assertThat(generated).contains("Json.encodeToString").doesNotContain("parameterContentObjectMapper")
        } else {
            assertThat(generated).contains("parameterContentObjectMapper.writeValueAsString")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPEN_FEIGN", "SPRING_HTTP_INTERFACE"])
    fun `rejects content-based parameters for annotation clients`(target: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(target),
        )

        assertThatThrownBy { generateClient(parameterContentOpenApi) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("supports native content-based parameters only for text/plain")
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPEN_FEIGN", "SPRING_HTTP_INTERFACE"])
    fun `binds text parameter content in annotation clients`(target: String) {
        val clientTarget = ClientCodeGenTargetType.valueOf(target)
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = clientTarget,
        )

        val generated = generateClient(textParameterContentOpenApi)

        assertThat(generated)
            .contains("selector: Int")
            .contains("active: Boolean = false")
        when (clientTarget) {
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated).contains("@Param(\"selector\")", "@Param(\"active\")")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated).contains("@PathVariable(\"selector\")", "@RequestParam(\"active\")")
            else -> error("Unsupported annotation client target $clientTarget")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `rejects unsupported parameter content media types`(target: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(target),
        )

        assertThatThrownBy { generateClient(parameterContentOpenApi.replace("application/json", "application/xml")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(
                "supports native content-based parameters only for text/plain, JSON, and form-encoded OpenAPI 3.2 querystring parameters",
            )
    }

    private fun generateClient(openApi: String): String =
        CodeGenerator(
            Packages("com.example"),
            SourceApi(openApi),
            Paths.get(""),
            Paths.get(""),
            SchemaGenerationMode.NATIVE,
        ).generate()
            .joinToString("\n") { file ->
                when (file) {
                    is KotlinSourceSet -> file.files.joinToString("\n")
                    is SimpleFile -> file.content
                    else -> ""
                }
            }

    @Test
    fun `generates ordered and nested multipart OkHttp clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(sequentialMultipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("parts: Iterable<MultipartPart>")
            .contains("val multipartBody = buildSequentialMultipartBody(parts, \"multipart/mixed\"")
            .contains("listOf(\"application/json\")")
            .contains("listOf(\"image/png\", \"image/jpeg\")")
            .contains("requiredHeaders = setOf(\"X-Part-Id\")")
            .contains("Content-Transfer-Encoding")
            .contains("base64")
            .contains("minimumPartCount = 1")
            .contains("maximumPartCount = 4")
            .contains("public data class MultipartPart(")
            .contains("public val parts: Iterable<MultipartPart>? = null")
            .contains("public val requestBody: RequestBody? = null")
            .contains("Exactly one of body, parts, or requestBody must be supplied")
            .contains("private class SequentialMultipartBody(")
            .contains("override fun writeTo(sink: BufferedSink)")
            .contains("var partCount = 0")
            .contains("require(partCount >= encoding.minimumPartCount)")
            .contains("part.requestBody?.writeTo(sink) ?: sink.write(requireNotNull(part.body))")
            .contains("Multipart part header '\$name' must have value '\$expectedValue'")
            .contains("writeSequentialMultipart(sink, nestedParts, requireNotNull(partEncoding)")
    }

    @Test
    fun `generates ordered and nested multipart Ktor clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.KTOR,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(sequentialMultipartOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("parts: Iterable<MultipartPart>")
            .contains("setBody(buildSequentialMultipartContent(parts, \"multipart/mixed\"")
            .contains("public data class MultipartPart(")
            .contains("private val boundary: String = \"fabrikt-\" + UUID.randomUUID()")
            .contains("require(partCount >= encoding.minimumPartCount)")
            .contains("OutgoingContent.WriteChannelContent()")
            .contains("override suspend fun writeTo(channel: ByteWriteChannel)")
            .contains("channel.writeFully(byteArrayOf(13, 10, 13, 10))")
            .contains("Content-Transfer-Encoding")
            .contains("base64")
            .contains("channel.writeStringUtf8(expectedValue)")
            .contains("writeSequentialMultipart(channel, nestedParts, requireNotNull(partEncoding)")
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
                    .contains("\"api_key\".encodeURLParameter()")
                    .contains("add(\"session_key=\" + cookieKey)")
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

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates HTTP and token credentials from native security schemes`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(authenticationOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("basicAuth: BasicCredentials")
            .contains("bearerAuth: BearerToken")
            .contains("oAuth: BearerToken")
            .contains("public data class BasicCredentials(")
            .contains("public data class BearerToken(")
            .contains("Base64.getEncoder().encodeToString")
            .contains("\"\"\"${'$'}username:${'$'}password\"\"\".toByteArray(Charsets.UTF_8)")
            .contains("\"Bearer \" + value")
            .contains("Authorization")
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates optional credentials for native security alternatives`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(authenticationAlternativesOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("basicAuth: BasicCredentials? = null")
            .contains("bearerAuth: BearerToken? = null")
            .contains("apiKey: String? = null")
            .contains("Authorization")
            .contains("X-API-Key")
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `allows clients to select a request content representation`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipleRequestContentOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("body: JsonNode")
            .contains("contentType: String = \"application/json\"")
        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated)
                    .contains("contentType.toMediaType()")
                    .contains(".`header`(\"Content-Type\", contentType)")
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated).contains("\"Content-Type: {contentType}\"")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated).contains("@RequestHeader(\"Content-Type\") contentType: String")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("`header`(\"Content-Type\", contentType)")
                    .doesNotContain("`header`(\"Content-Type\", \"application/json\")")
        }
    }

    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `negotiates response content across successful statuses`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(multipleResponseContentOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated).contains("acceptHeader: String = \"application/json\"")
        when (target) {
            ClientCodeGenTargetType.OK_HTTP ->
                assertThat(generated).contains(".`header`(\"Accept\", acceptHeader)")
            ClientCodeGenTargetType.OPEN_FEIGN ->
                assertThat(generated).contains("\"Accept: {acceptHeader}\"")
            ClientCodeGenTargetType.SPRING_HTTP_INTERFACE ->
                assertThat(generated).contains("@RequestHeader(\"Accept\") acceptHeader: String")
            ClientCodeGenTargetType.KTOR ->
                assertThat(generated)
                    .contains("`header`(\"Accept\", acceptHeader)")
                    .doesNotContain("`header`(\"Accept\", \"application/json\")")
        }
    }

    @Test
    fun `serializes OkHttp request bodies according to their selected media type`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(nonJsonRequestContentOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .filterIsInstance<KotlinSourceSet>()
                .flatMap { it.files }
                .joinToString("\n")

        assertThat(generated)
            .contains("val fabriktContentType = \"text/plain\"")
            .contains("contentType: String = \"application/json\"")
            .contains("objectMapper.writeValueAsBytes(body)")
            .contains("body is ByteArray -> body")
            .contains("else -> body.toString().toByteArray()")
            .contains("fabriktRequestBodyBytes.toRequestBody(fabriktContentType.toMediaType())")
            .doesNotContain("objectMapper.writeValueAsString(body).toRequestBody(\"text/plain\"")
    }

    @Test
    fun `reads plain text OkHttp responses without Jackson`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val generated =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(textResponseOpenApi),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
                .joinToString("\n") { file ->
                    when (file) {
                        is KotlinSourceSet -> file.files.joinToString("\n")
                        is SimpleFile -> file.content
                        else -> ""
                    }
                }

        assertThat(generated)
            .contains("public fun readText(")
            .contains("return request.executeText(okHttpClient)")
            .contains("responseBody?.string()")
            .contains("public fun readJsonString(")
            .contains("return request.execute(okHttpClient, objectMapper, jacksonTypeRef())")
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

    private val multipleRequestContentOpenApi =
        """
        openapi: 3.1.1
        info: { title: Request representations, version: "1.0" }
        paths:
          /payloads:
            post:
              operationId: createPayload
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [value]
                      properties:
                        value: { type: string }
                  application/vnd.example+json:
                    schema: { type: string }
              responses:
                '204': { description: Accepted }
        """.trimIndent()

    private val multipleResponseContentOpenApi =
        """
        openapi: 3.1.1
        info: { title: Response representations, version: "1.0" }
        paths:
          /payloads:
            get:
              operationId: findPayload
              responses:
                '200':
                  description: Structured payload
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          value: { type: string }
                '202':
                  description: Deferred payload
                  content:
                    text/plain:
                      schema: { type: string }
        """.trimIndent()

    private val nonJsonRequestContentOpenApi =
        """
        openapi: 3.1.1
        info: { title: Non-JSON requests, version: "1.0" }
        paths:
          /text:
            post:
              operationId: sendText
              requestBody:
                required: true
                content:
                  text/plain:
                    schema: { type: string }
              responses:
                '204': { description: Accepted }
          /dynamic:
            post:
              operationId: sendDynamic
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        value: { type: string }
                  application/octet-stream:
                    schema: { type: string, format: binary }
              responses:
                '204': { description: Accepted }
        """.trimIndent()

    private val textResponseOpenApi =
        """
        openapi: 3.1.1
        info: { title: Text responses, version: "1.0" }
        paths:
          /text:
            get:
              operationId: readText
              responses:
                '200':
                  description: Plain text
                  content:
                    text/plain:
                      schema: { type: string }
          /json-string:
            get:
              operationId: readJsonString
              responses:
                '200':
                  description: JSON string
                  content:
                    application/json:
                      schema: { type: string }
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
                    encoding:
                      metadata:
                        contentType: application/merge-patch+json
              responses:
                '204':
                  description: Uploaded
        """.trimIndent()

    private val heterogeneousJsonResponsesOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Heterogeneous JSON responses
          version: "1.0"
        paths:
          /subject:
            get:
              operationId: findSubject
              responses:
                '200':
                  description: Text result
                  content:
                    application/json:
                      schema: { type: string }
                '202':
                  description: Numeric result
                  content:
                    application/json:
                      schema: { type: integer }
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
                        thumbnail: { type: string, format: binary }
                        metadata:
                          type: object
                          properties:
                            title: { type: string }
                    encoding:
                      document:
                        headers:
                          X-Checksum:
                            required: true
                            schema: { type: string }
                          X-Mode:
                            required: true
                            schema:
                              type: string
                              enum: [fast-mode, safe]
                          Content-Type:
                            schema: { type: string }
                      metadata:
                        contentType: application/json
                        headers:
                          X-Trace-Ids:
                            schema:
                              type: array
                              items: { type: string }
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
                      attachments:
                        headers:
                          X-Sequence:
                            required: true
                            schema: { type: integer }
                          X-Note:
                            schema: { type: string }
                      thumbnail:
                        headers:
                          X-Checksum:
                            required: true
                            schema: { type: string }
              responses:
                '204': { description: Uploaded }
        """.trimIndent()

    private val contentEncodedMultipartOpenApi =
        multipartOpenApi.replace(
            "document: { type: string, format: binary }",
            "document: { type: string, format: binary, contentEncoding: base64 }",
        )

    private val fixedTransferHeaderMultipartOpenApi =
        multipartOpenApi.replace(
            "            encoding:\n              metadata:",
            listOf(
                "            encoding:",
                "              document:",
                "                headers:",
                "                  Content-Transfer-Encoding:",
                "                    required: true",
                "                    schema: { type: string, const: base64 }",
                "              metadata:",
            ).joinToString("\n"),
        )

    private val conflictingTransferEncodingMultipartOpenApi =
        contentEncodedMultipartOpenApi.replace(
            "            encoding:\n              metadata:",
            listOf(
                "            encoding:",
                "              document:",
                "                headers:",
                "                  Content-Transfer-Encoding:",
                "                    required: true",
                "                    schema: { type: string, const: quoted-printable }",
                "              metadata:",
            ).joinToString("\n"),
        )

    private val variableTransferHeaderMultipartOpenApi =
        fixedTransferHeaderMultipartOpenApi
            .replace("required: true", "required: false")
            .replace("const: base64", "enum: [base64, binary]")

    private val sequentialMultipartOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Native sequential multipart client
          version: "1.0"
        paths:
          /documents:
            post:
              operationId: uploadDocumentBundle
              requestBody:
                required: true
                content:
                  multipart/mixed:
                    schema:
                      type: array
                      minItems: 1
                      maxItems: 4
                      prefixItems:
                        - type: object
                          properties:
                            title: { type: string }
                        - type: array
                          prefixItems:
                            - { type: string }
                          items: {}
                      items:
                        type: string
                        contentEncoding: base64
                    prefixEncoding:
                      - contentType: application/json
                      - contentType: multipart/mixed
                        prefixEncoding:
                          - contentType: text/plain
                        itemEncoding:
                          contentType: image/png, image/jpeg
                    itemEncoding:
                      contentType: text/plain
                      headers:
                        Content-Transfer-Encoding:
                          required: true
                          schema: { type: string, const: base64 }
                        X-Part-Id:
                          required: true
                          schema: { type: string }
              responses:
                '204': { description: Accepted }
        """.trimIndent()

    private val parameterContentOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native parameter content client
          version: "1.0"
        paths:
          /things/{selector}:
            parameters:
              - name: selector
                in: path
                required: true
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Filter' }
            get:
              operationId: findThings
              parameters:
                - name: filter
                  in: query
                  required: true
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Filter' }
                - name: X-Filter
                  in: header
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Filter' }
                - name: filterState
                  in: cookie
                  content:
                    application/json:
                      schema: { ${'$'}ref: '#/components/schemas/Filter' }
              responses:
                '204': { description: Found }
        components:
          schemas:
            Filter:
              type: object
              required: [term]
              properties:
                term: { type: string }
                page: { type: integer }
        """.trimIndent()

    private val textParameterContentOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native text parameter content client
          version: "1.0"
        paths:
          /things/{selector}:
            get:
              operationId: findThings
              parameters:
                - name: selector
                  in: path
                  required: true
                  content:
                    text/plain:
                      schema: { type: integer }
                - name: active
                  in: query
                  content:
                    text/plain:
                      schema: { type: boolean, default: false }
              responses:
                '204': { description: Found }
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

    private val explicitApiKeyParameter =
        "parameters:\n" +
            "              - name: X-API-Key\n" +
            "                in: header\n" +
            "                required: true\n" +
            "                schema: { type: string }"

    private val authenticationOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native authentication
          version: "1.0"
        paths:
          /basic:
            get:
              security: [{ BasicAuth: [] }]
              responses:
                '204': { description: ok }
          /bearer:
            get:
              security: [{ BearerAuth: [] }]
              responses:
                '204': { description: ok }
          /oauth:
            get:
              security: [{ OAuth: [read] }]
              responses:
                '204': { description: ok }
        components:
          securitySchemes:
            BasicAuth:
              type: http
              scheme: basic
            BearerAuth:
              type: http
              scheme: bearer
              bearerFormat: JWT
            OAuth:
              type: oauth2
              flows:
                clientCredentials:
                  tokenUrl: https://example.test/token
                  scopes:
                    read: Read access
        """.trimIndent()

    private val authenticationAlternativesOpenApi =
        """
        openapi: 3.1.1
        info:
          title: Native authentication alternatives
          version: "1.0"
        paths:
          /secured:
            get:
              security:
                - BasicAuth: []
                - BearerAuth: []
                  ApiKey: []
              responses:
                '204': { description: ok }
        components:
          securitySchemes:
            BasicAuth:
              type: http
              scheme: basic
            BearerAuth:
              type: http
              scheme: bearer
            ApiKey:
              type: apiKey
              name: X-API-Key
              in: header
        """.trimIndent()

    companion object {
        @JvmStatic
        fun nativeCookieClientConfigurations(): Stream<Arguments> =
            Stream.of("3.0.4", "3.1.2", "3.2.0").flatMap { version ->
                Stream.of(ClientCodeGenTargetType.OK_HTTP, ClientCodeGenTargetType.KTOR).map { target -> Arguments.of(version, target) }
            }

        @JvmStatic
        fun nativeMultipartHeaderClientConfigurations(): Stream<Arguments> =
            Stream.of("3.0.4", "3.1.2", "3.2.0").flatMap { version ->
                Stream.of(ClientCodeGenTargetType.OK_HTTP, ClientCodeGenTargetType.KTOR).map { target -> Arguments.of(version, target) }
            }
    }
}
