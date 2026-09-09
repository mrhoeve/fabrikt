package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.parser.OpenApiDocumentParser
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import com.cjbooms.fabrikt.parser.toGeneratorOperationDocument
import com.cjbooms.fabrikt.parser.toGeneratorSchemaDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorEndpointSecurityPlanTest {
    @Test
    fun `resolves global security alternatives and combined schemes`() {
        val context = context()
        val operation =
            context.operations.paths
                .first { it.path == "/global" }
                .operations
                .single()

        val alternatives = context.securityPlan(operation).alternatives

        assertThat(alternatives).hasSize(2)
        assertThat(alternatives.first().schemes.map { it.name }).containsExactly("ApiKey", "OAuth")
        assertThat(
            alternatives
                .first()
                .schemes
                .last()
                .scopes,
        ).containsExactly("items:read")
        assertThat(alternatives.first().schemes).allMatch { it.scheme != null }
        assertThat(
            alternatives
                .last()
                .schemes
                .single()
                .scheme
                ?.scheme,
        ).isEqualTo("bearer")
    }

    @Test
    fun `preserves operation overrides anonymous alternatives and unresolved names`() {
        val context = context()
        val operations = context.operations.paths.associate { it.path to it.operations.single() }

        assertThat(context.securityPlan(operations.getValue("/public")).alternatives).isEmpty()
        assertThat(context.securityPlan(operations.getValue("/optional")).alternatives.map { it.schemes })
            .satisfiesExactly(
                { assertThat(it).isEmpty() },
                { assertThat(it.single().name).isEqualTo("ApiKey") },
            )
        val unresolved =
            context
                .securityPlan(operations.getValue("/unknown"))
                .alternatives
                .single()
                .schemes
                .single()
        assertThat(unresolved.name).isEqualTo("Missing")
        assertThat(unresolved.scheme).isNull()
    }

    private fun context(): GeneratorEndpointContext {
        val parsed = OpenApiDocumentParser.parse(openApi)
        return GeneratorEndpointContext(
            parsed.toGeneratorOperationDocument(SchemaGenerationMode.NATIVE),
            parsed.toGeneratorSchemaDocument(SchemaGenerationMode.NATIVE),
            "com.example",
        )
    }

    private val openApi =
        """
        openapi: 3.1.1
        info:
          title: Security plan
          version: "1.0"
        security:
          - ApiKey: []
            OAuth: [items:read]
          - Bearer: []
        paths:
          /global:
            get:
              responses: { '204': { description: ok } }
          /public:
            get:
              security: []
              responses: { '204': { description: ok } }
          /optional:
            get:
              security:
                - {}
                - ApiKey: []
              responses: { '204': { description: ok } }
          /unknown:
            get:
              security:
                - Missing: []
              responses: { '204': { description: ok } }
        components:
          securitySchemes:
            ApiKey:
              type: apiKey
              name: X-API-Key
              in: header
            OAuth:
              type: oauth2
              flows:
                clientCredentials:
                  tokenUrl: https://auth.example.com/token
                  scopes:
                    items:read: Read items
            Bearer:
              type: http
              scheme: bearer
        """.trimIndent()
}
