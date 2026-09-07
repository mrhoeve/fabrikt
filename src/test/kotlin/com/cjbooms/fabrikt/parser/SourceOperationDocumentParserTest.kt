package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOperationDocumentParserTest {
    @Test
    fun `collects path operations and metadata without treating path item fields as operations`() {
        val document = SourceOpenApiDocumentParser.parse(pathOperationsOpenApi)
        val operations = document.operations

        assertThat(operations.paths).hasSize(1)
        val path = operations.paths.single()
        assertThat(path.location).isEqualTo("#/paths/~1subjects~1{id}")
        assertThat(path.key).isEqualTo("/subjects/{id}")
        assertThat(path.kind).isEqualTo(SourcePathItemKind.PATH)
        assertThat(path.reference).isEqualTo("#/components/pathItems/Subjects")
        assertThat(path.summary).isEqualTo("Subject operations")
        assertThat(path.description).isEqualTo("Operations for one subject")
        assertThat(path.node).isSameAs(document.root.at("/paths/~1subjects~1{id}"))
        assertThat(path.operations.map { it.method.wireName }).containsExactly("GET", "POST")

        val get = path.operations.first()
        assertThat(get.location).isEqualTo("#/paths/~1subjects~1{id}/get")
        assertThat(get.method).isEqualTo(SourceOperationMethod.Fixed(SourceFixedOperationMethod.GET))
        assertThat(get.operationId).isEqualTo("getSubject")
        assertThat(get.summary).isEqualTo("Get a subject")
        assertThat(get.description).isEqualTo("Returns one subject")
        assertThat(get.tags).containsExactly("subjects", "read")
        assertThat(get.deprecated).isTrue()

        val post = path.operations.last()
        assertThat(post.operationId).isNull()
        assertThat(post.tags).isEmpty()
        assertThat(post.deprecated).isFalse()
    }

    @Test
    fun `preserves path and operation source order`() {
        val operations = SourceOpenApiDocumentParser.parse(orderedPathsOpenApi).operations

        assertThat(operations.paths.map(SourcePathItem::key)).containsExactly("/z-last", "/a-first")
        assertThat(
            operations.paths
                .first()
                .operations
                .map { it.method.wireName },
        ).containsExactly("DELETE", "GET")
    }

    private val pathOperationsOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects/{id}:
            ${'$'}ref: '#/components/pathItems/Subjects'
            summary: Subject operations
            description: Operations for one subject
            parameters: []
            get:
              operationId: getSubject
              summary: Get a subject
              description: Returns one subject
              tags: [subjects, read]
              deprecated: true
              responses: {}
            post:
              responses: {}
            x-internal: true
        """.trimIndent()

    private val orderedPathsOpenApi =
        """
        openapi: 3.0.4
        info:
          title: Test
          version: "1.0"
        paths:
          x-ignored: true
          /z-last:
            delete:
              responses: {}
            get:
              responses: {}
          /a-first:
            post:
              responses: {}
        """.trimIndent()
}
