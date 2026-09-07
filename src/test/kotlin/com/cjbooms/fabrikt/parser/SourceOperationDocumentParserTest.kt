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

    @Test
    fun `collects webhooks callbacks and reusable operation definitions in OpenAPI 3_1`() {
        val operations = SourceOpenApiDocumentParser.parse(modernOperationContainersOpenApi).operations

        assertThat(operations.webhooks.map(SourcePathItem::key)).containsExactly("subject~changed")
        val webhook = operations.webhooks.single()
        assertThat(webhook.kind).isEqualTo(SourcePathItemKind.WEBHOOK)
        assertThat(webhook.location).isEqualTo("#/webhooks/subject~0changed")
        assertThat(webhook.operations.single().operationId).isEqualTo("subjectChanged")

        val callback =
            operations.paths
                .single()
                .operations
                .single()
                .callbacks
                .single()
        assertThat(callback.location).isEqualTo("#/paths/~1subjects/post/callbacks/updates")
        assertThat(callback.name).isEqualTo("updates")
        assertThat(callback.reference).isNull()
        assertThat(callback.pathItems.map(SourcePathItem::key)).containsExactly("{${'$'}request.body#/~callbackUrl}")
        assertThat(callback.pathItems.single().kind).isEqualTo(SourcePathItemKind.CALLBACK)
        assertThat(
            callback.pathItems
                .single()
                .operations
                .single()
                .operationId,
        ).isEqualTo("receiveUpdate")

        val reusablePathItem = operations.reusablePathItems.getValue("Subjects")
        assertThat(reusablePathItem.kind).isEqualTo(SourcePathItemKind.REUSABLE_PATH_ITEM)
        assertThat(reusablePathItem.operations.single().operationId).isEqualTo("listSubjects")

        val reusableCallback = operations.reusableCallbacks.getValue("Audit")
        assertThat(reusableCallback.pathItems.single().kind).isEqualTo(SourcePathItemKind.REUSABLE_CALLBACK)
        assertThat(
            reusableCallback.pathItems
                .single()
                .operations
                .single()
                .operationId,
        ).isEqualTo("recordAudit")
        assertThat(operations.reusableCallbacks.getValue("Referenced").reference)
            .isEqualTo("#/components/callbacks/Audit")
        assertThat(operations.reusableCallbacks.getValue("Referenced").pathItems).isEmpty()
    }

    @Test
    fun `keeps OpenAPI 3_1 operation containers out of OpenAPI 3_0`() {
        val operations =
            SourceOpenApiDocumentParser
                .parse(modernOperationContainersOpenApi.replace("openapi: 3.1.2", "openapi: 3.0.4"))
                .operations

        assertThat(operations.webhooks).isEmpty()
        assertThat(operations.reusablePathItems).isEmpty()
        assertThat(operations.reusableCallbacks).containsOnlyKeys("Audit", "Referenced")
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

    private val modernOperationContainersOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects:
            post:
              operationId: createSubject
              callbacks:
                updates:
                  '{${'$'}request.body#/~callbackUrl}':
                    post:
                      operationId: receiveUpdate
                      responses: {}
                  x-ignore: true
              responses: {}
        webhooks:
          subject~changed:
            post:
              operationId: subjectChanged
              responses: {}
        components:
          pathItems:
            Subjects:
              get:
                operationId: listSubjects
                responses: {}
          callbacks:
            Audit:
              '{${'$'}request.body#/~auditUrl}':
                post:
                  operationId: recordAudit
                  responses: {}
              x-ignore: true
            Referenced:
              ${'$'}ref: '#/components/callbacks/Audit'
        """.trimIndent()
}
