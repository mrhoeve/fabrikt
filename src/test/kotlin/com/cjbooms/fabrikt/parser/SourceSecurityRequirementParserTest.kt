package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceSecurityRequirementParserTest {
    @Test
    fun `models global security alternatives and combined schemes`() {
        val security = SourceOpenApiDocumentParser.parse(openApi).operations.security!!

        assertThat(security.location).isEqualTo("#/security")
        assertThat(security.values).hasSize(3)
        assertThat(security.values[0].schemes).containsEntry("ApiKey", emptyList())
        assertThat(security.values[1].schemes)
            .containsExactlyEntriesOf(
                linkedMapOf(
                    "OAuth" to listOf("read:subjects", "write:subjects"),
                    "MutualTls" to emptyList(),
                ),
            )
        assertThat(security.values[2].schemes).isEmpty()
        assertThat(security.values.map(SourceSecurityRequirement::location))
            .containsExactly("#/security/0", "#/security/1", "#/security/2")
    }

    @Test
    fun `distinguishes inherited and explicitly disabled operation security`() {
        val operations =
            SourceOpenApiDocumentParser
                .parse(openApi)
                .operations.paths
                .single()
                .operations

        assertThat(operations[0].security).isNull()
        assertThat(operations[1].security).isNotNull
        assertThat(operations[1].security!!.values).isEmpty()
        assertThat(
            operations[2]
                .security!!
                .values
                .single()
                .schemes,
        ).containsExactlyEntriesOf(linkedMapOf("OAuth" to listOf("admin")))
    }

    private val openApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        security:
          - ApiKey: []
          - OAuth: [read:subjects, write:subjects]
            MutualTls: []
          - {}
        paths:
          /subjects:
            get:
              responses: {}
            post:
              security: []
              responses: {}
            delete:
              security:
                - OAuth: [admin]
              responses: {}
        """.trimIndent()
}
