package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SimpleFile
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Paths

class CodeGeneratorNativeQuerySerializationTest {
    @ParameterizedTest
    @CsvSource(
        "OK_HTTP, 3.0.4",
        "OK_HTTP, 3.1.2",
        "OK_HTTP, 3.2.0",
        "KTOR, 3.0.4",
        "KTOR, 3.1.2",
        "KTOR, 3.2.0",
    )
    fun `serializes native query parameter styles`(
        targetName: String,
        version: String,
    ) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(targetName),
        )

        val generatedFiles =
            CodeGenerator(
                Packages("com.example"),
                SourceApi(openApi.replace("OPENAPI_VERSION", version)),
                Paths.get(""),
                Paths.get(""),
                SchemaGenerationMode.NATIVE,
            ).generate()
        val generated = generatedFiles.filterIsInstance<KotlinSourceSet>().flatMap { it.files }.joinToString("\n")

        assertThat(generated)
            .contains("colors")
            .contains("joinToString(\"|\")")
            .contains("tags.forEach")
            .contains("filter")
            .contains("flatFilter")
            .contains("compactFilter")
            .contains("value.role")
            .contains("status.value")
            .contains("joinToString(\",\")")
        if (targetName == "OK_HTTP") {
            assertThat(generated)
                .contains("builder.addEncodedQueryParameter(\"filter%5Brole%5D\"")
                .contains("builder.addQueryParameter(\"role\"")
                .contains("builder.addEncodedQueryParameter(\"redirect\"")
                .contains("builder.addEncodedQueryParameter(\"paths\"")
                .contains("builder.addEncodedQueryParameter(\"redirect%20target%2F%C3%A9\"")
        } else {
            val libraries = generatedFiles.filterIsInstance<SimpleFile>().joinToString("\n") { it.content }
            assertThat(generated)
                .contains("\"filter[role]\".encodeURLParameter()")
                .contains("\"role\".encodeURLParameter()")
                .contains(".encodeURLParameter()")
                .contains("redirect.toString()).encodeReservedQueryValue()")
                .contains("paths.joinToString(\"|\")).encodeReservedQueryValue()")
                .contains("\"redirect target/é\".encodeURLParameter()")
            assertThat(libraries)
                .contains("this@encodeReservedQueryValue[index] == '%'")
                .contains("digitToIntOrNull(16)")
        }
    }

    private val openApi =
        """
        openapi: OPENAPI_VERSION
        info: { title: Query serialization, version: "1.0" }
        paths:
          /search:
            get:
              parameters:
                - name: colors
                  in: query
                  style: pipeDelimited
                  explode: false
                  schema:
                    type: array
                    items: { type: string }
                - name: tags
                  in: query
                  required: true
                  style: form
                  explode: true
                  schema:
                    type: array
                    items: { type: string }
                - name: filter
                  in: query
                  style: deepObject
                  explode: true
                  allowReserved: true
                  schema: { ${'$'}ref: '#/components/schemas/Filter' }
                - name: compactFilter
                  in: query
                  style: form
                  explode: false
                  schema: { ${'$'}ref: '#/components/schemas/Filter' }
                - name: flatFilter
                  in: query
                  style: form
                  explode: true
                  schema: { ${'$'}ref: '#/components/schemas/Filter' }
                - name: status
                  in: query
                  required: true
                  schema:
                    type: string
                    enum: [active, inactive]
                - name: redirect
                  in: query
                  required: true
                  allowReserved: true
                  schema: { type: string }
                - name: paths
                  in: query
                  required: true
                  style: pipeDelimited
                  explode: false
                  allowReserved: true
                  schema:
                    type: array
                    items: { type: string }
                - name: redirect target/é
                  in: query
                  allowReserved: true
                  schema: { type: string }
              responses: { '204': { description: ok } }
        components:
          schemas:
            Filter:
              type: object
              required: [role]
              properties:
                role: { type: string }
                active: { type: boolean }
        """.trimIndent()
}
