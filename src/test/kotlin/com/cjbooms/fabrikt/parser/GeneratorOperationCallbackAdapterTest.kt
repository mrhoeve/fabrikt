package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorOperationCallbackAdapterTest {
    @Test
    fun `preserves OpenAPI webhooks in the native generator contract`() {
        val document = OpenApiDocumentParser.parse(openApi).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)

        assertThat(document.paths.map(GeneratorPathItem::path)).containsExactly("/subscriptions")
        assertThat(document.paths.single().kind).isEqualTo(GeneratorPathItemKind.PATH)
        assertThat(document.webhooks.map(GeneratorPathItem::path)).containsExactly("eventReceived")
        val webhook = document.webhooks.single()
        assertThat(webhook.kind).isEqualTo(GeneratorPathItemKind.WEBHOOK)
        val webhookOperation = webhook.operations.single()
        assertThat(webhookOperation.method).isEqualTo("post")
        val webhookContentType =
            webhookOperation.requestBody
                ?.content
                ?.single()
                ?.key
        assertThat(webhookContentType).isEqualTo("application/json")
    }

    @Test
    fun `resolves reusable callbacks while retaining their operation name`() {
        val document = OpenApiDocumentParser.parse(openApi).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)
        val callback =
            document.paths
                .single()
                .operations
                .single()
                .callbacks
                .single()

        assertThat(callback.name).isEqualTo("onEvent")
        assertThat(callback.extensions).containsOnlyKeys("x-callback")
        assertThat(callback.pathItems.map(GeneratorPathItem::path)).containsExactly("{${'$'}request.body#/callbackUrl}")
        val callbackPath = callback.pathItems.single()
        assertThat(callbackPath.kind).isEqualTo(GeneratorPathItemKind.CALLBACK)
        assertThat(callbackPath.operations.single().method).isEqualTo("post")
        assertThat(callbackPath.operations.single().operationId).isEqualTo("receiveEvent")
    }

    private val openApi =
        """
        openapi: 3.1.1
        info:
          title: Generator callback adapter
          version: "1.0"
        paths:
          /subscriptions:
            post:
              operationId: createSubscription
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [callbackUrl]
                      properties:
                        callbackUrl: { type: string, format: uri }
              callbacks:
                onEvent:
                  ${'$'}ref: '#/components/callbacks/EventCallback'
              responses:
                '201': { description: Created }
        webhooks:
          eventReceived:
            post:
              operationId: eventReceived
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { ${'$'}ref: '#/components/schemas/Event' }
              responses:
                '204': { description: Accepted }
        components:
          callbacks:
            EventCallback:
              '{${'$'}request.body#/callbackUrl}':
                post:
                  operationId: receiveEvent
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema: { ${'$'}ref: '#/components/schemas/Event' }
                  responses:
                    '204': { description: Accepted }
              x-callback: retained
          schemas:
            Event:
              type: object
              required: [id]
              properties:
                id: { type: string }
        """.trimIndent()
}
