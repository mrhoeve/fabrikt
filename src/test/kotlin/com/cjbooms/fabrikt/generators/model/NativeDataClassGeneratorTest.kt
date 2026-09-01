package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.model.GeneratorModelDescriptorBuilder
import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NativeDataClassGeneratorTest {
    @ParameterizedTest
    @ValueSource(strings = ["3.0.4", "3.1.2", "3.2.0"])
    fun `generates basic data classes from native schemas`(version: String) {
        val parsed = OpenApiDocumentParser.parse(openApi.replace("VERSION", version))
        val descriptors =
            GeneratorModelDescriptorBuilder.build(
                parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
            )

        val file = NativeDataClassGenerator("com.example").generate(descriptors).files.single()

        assertThat(file.toString()).contains(
            """
            public data class Subject(
              public val id: String,
              public val count: Int? = null,
            )
            """.trimIndent(),
        )
    }

    private val openApi =
        """
        openapi: VERSION
        info:
          title: Test
          version: "1.0"
        paths: {}
        components:
          schemas:
            Subject:
              type: object
              required: [id]
              properties:
                id: { type: string }
                count: { type: integer }
        """.trimIndent()
}
