package com.cjbooms.fabrikt.cli

import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

class CodeGeneratorNativeQuerySerializationTest {
    @ParameterizedTest
    @ValueSource(strings = ["OK_HTTP", "KTOR"])
    fun `serializes native query parameter styles`(targetName: String) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.valueOf(targetName),
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
            .contains("colors")
            .contains("joinToString(\"|\")")
            .contains("tags.forEach")
            .contains("filter[role]")
            .contains("flatFilter")
            .contains("compactFilter")
            .contains("value.role")
            .contains("status.value")
            .contains("joinToString(\",\")")
        if (targetName == "OK_HTTP") {
            assertThat(generated)
                .contains("builder.addQueryParameter(\"filter[role]\"")
                .contains("builder.addQueryParameter(\"role\"")
        } else {
            assertThat(generated)
                .contains("\"filter[role]\".encodeURLParameter()")
                .contains("\"role\".encodeURLParameter()")
                .contains(".encodeURLParameter()")
        }
    }

    private val openApi =
        """
        openapi: 3.1.1
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
