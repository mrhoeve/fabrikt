package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

class CodeGeneratorNativeQueryStringParameterTest {
    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `serializes form querystring parameters in native clients`(targetName: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(targetName),
        )

        val generated = generate()

        assertThat(generated)
            .contains("filters: Filters")
            .contains("filters.status.forEach")
            .contains("\"status\"")
            .contains("filters.tags?.let")
            .contains("joinToString(\",\")")
            .contains("filters.createdAfter?.let")
    }

    @ParameterizedTest
    @EnumSource(SerializationLibrary::class)
    fun `deserializes form querystring parameters in native Ktor controllers`(serializationLibrary: SerializationLibrary) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CONTROLLERS),
            controllerTarget = ControllerCodeGenTargetType.KTOR,
            serializationLibrary = serializationLibrary,
        )

        val generated = generate()
        val dateTimeType = if (serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) "Instant" else "OffsetDateTime"

        assertThat(generated)
            .contains("filters: Filters")
            .contains("val filtersTagsFields = parameters {")
            .contains("call.request.queryParameters[\"tags\"]?.split(\",\")")
            .contains("status = call.request.queryParameters.getTypedOrFail<List<String>>(\"status\"")
            .contains("tags = filtersTagsFields.getTyped<List<String>>(\"tags\"")
            .contains("createdAfter = call.request.queryParameters.getTyped<$dateTimeType>(\"createdAfter\"")
            .contains("controller.structuredQuery(filters, call)")
    }

    @ParameterizedTest
    @EnumSource(value = ClientCodeGenTargetType::class, names = ["OPEN_FEIGN", "SPRING_HTTP_INTERFACE"])
    fun `rejects querystring parameters for annotation clients`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CLIENT), clientTarget = target)

        assertThatThrownBy(::generate)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("supports native content-based parameters only for text/plain")
    }

    @ParameterizedTest
    @EnumSource(value = ControllerCodeGenTargetType::class, names = ["SPRING", "MICRONAUT"])
    fun `rejects querystring parameters for annotation controllers`(target: ControllerCodeGenTargetType) {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CONTROLLERS), controllerTarget = target)

        assertThatThrownBy(::generate)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("supports native content-based parameters only for text/plain")
    }

    @ParameterizedTest
    @ValueSource(strings = ["application/json", "text/plain"])
    fun `rejects unsupported querystring media types`(mediaType: String) {
        MutableSettings.updateSettings(genTypes = setOf(CodeGenerationType.CLIENT), clientTarget = ClientCodeGenTargetType.KTOR)

        assertThatThrownBy { generate(openApi.replace("application/x-www-form-urlencoded", mediaType)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("querystring parameters currently require object-valued application/x-www-form-urlencoded content")
    }

    private fun generate(input: String = openApi): String =
        CodeGenerator(
            Packages("com.example"),
            SourceApi(input),
            Paths.get(""),
            Paths.get(""),
            SchemaGenerationMode.NATIVE,
        ).generate()
            .filterIsInstance<KotlinSourceSet>()
            .flatMap { it.files }
            .joinToString("\n")

    private val openApi =
        """
        openapi: 3.2.0
        info: { title: Querystring parameters, version: "1.0" }
        paths:
          /structured-query:
            get:
              operationId: structuredQuery
              parameters:
                - name: filters
                  in: querystring
                  required: true
                  content:
                    application/x-www-form-urlencoded:
                      schema:
                        type: object
                        required: [status]
                        properties:
                          status:
                            type: array
                            items: { type: string }
                          tags:
                            type: array
                            items: { type: string }
                          createdAfter: { type: string, format: date-time }
                      encoding:
                        status: { style: form, explode: true }
                        tags: { style: form, explode: false }
              responses:
                '204': { description: accepted }
        """.trimIndent()
}
