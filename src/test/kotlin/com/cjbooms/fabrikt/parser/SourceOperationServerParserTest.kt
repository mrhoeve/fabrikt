package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOperationServerParserTest {
    @Test
    fun `models path and operation server declarations`() {
        val operations = SourceOpenApiDocumentParser.parse(openApi).operations
        val path = operations.paths.single()

        assertThat(path.servers!!.location).isEqualTo("#/paths/~1subjects/servers")
        assertThat(
            path.servers!!
                .values
                .single()
                .url,
        ).isEqualTo("https://path.example.com")

        val get = path.operations.first()
        assertThat(get.servers).isNull()

        val post = path.operations.last()
        assertThat(post.servers!!.location).isEqualTo("#/paths/~1subjects/post/servers")
        assertThat(
            post.servers!!
                .values
                .single()
                .url,
        ).isEqualTo("https://operation.example.com/{tenant}")
        assertThat(
            post.servers!!
                .values
                .single()
                .variables
                .getValue("tenant")
                .defaultValue,
        ).isEqualTo("public")
    }

    @Test
    fun `preserves explicit empty path and operation server declarations`() {
        val path =
            SourceOpenApiDocumentParser
                .parse(emptyServersOpenApi)
                .operations.paths
                .single()

        assertThat(path.servers).isNotNull
        assertThat(path.servers!!.values).isEmpty()
        assertThat(path.operations.single().servers).isNotNull
        assertThat(
            path.operations
                .single()
                .servers!!
                .values,
        ).isEmpty()
    }

    private val openApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        servers:
          - url: https://root.example.com
        paths:
          /subjects:
            servers:
              - url: https://path.example.com
            get:
              responses: {}
            post:
              servers:
                - url: https://operation.example.com/{tenant}
                  variables:
                    tenant:
                      default: public
              responses: {}
        """.trimIndent()

    private val emptyServersOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            servers: []
            get:
              servers: []
              responses: {}
        """.trimIndent()
}
