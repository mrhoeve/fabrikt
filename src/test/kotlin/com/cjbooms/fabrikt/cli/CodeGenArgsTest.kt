package com.cjbooms.fabrikt.cli

import com.beust.jcommander.ParameterException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CodeGenArgsTest {
    @Test
    fun `defaults schema generation to legacy`() {
        val arguments = CodeGenArgs.parse(arrayOf("--base-package", "com.example"))

        assertThat(arguments.schemaGenerationMode).isEqualTo(SchemaGenerationMode.LEGACY)
    }

    @Test
    fun `selects native schema generation case insensitively`() {
        val arguments =
            CodeGenArgs.parse(
                arrayOf("--base-package", "com.example", "--schema-generation-mode", "native"),
            )

        assertThat(arguments.schemaGenerationMode).isEqualTo(SchemaGenerationMode.NATIVE)
    }

    @Test
    fun `rejects an unknown schema generation mode`() {
        assertThatThrownBy {
            CodeGenArgs.parse(
                arrayOf("--base-package", "com.example", "--schema-generation-mode", "unknown"),
            )
        }.isInstanceOf(ParameterException::class.java)
            .hasMessageContaining("Please choose from")
            .hasMessageContaining("LEGACY")
            .hasMessageContaining("NATIVE")
    }
}
