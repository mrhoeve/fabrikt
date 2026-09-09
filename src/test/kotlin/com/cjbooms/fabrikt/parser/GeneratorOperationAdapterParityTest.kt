package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorOperationAdapterParityTest {
    @Test
    fun `adapts common endpoints consistently in legacy and native modes`() {
        val parsed = OpenApiDocumentParser.parse(openApi)
        val legacy = parsed.toGeneratorOperationDocument(SchemaGenerationMode.LEGACY)
        val native = parsed.toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)

        assertThat(native.basePath).isEqualTo(legacy.basePath)
        assertThat(native.security).isEqualTo(legacy.security)
        assertThat(native.securitySchemes.keys).isEqualTo(legacy.securitySchemes.keys)
        assertThat(native.securitySchemes.getValue("oauth").type).isEqualTo(legacy.securitySchemes.getValue("oauth").type)
        assertThat(native.paths.map(GeneratorPathItem::path)).isEqualTo(legacy.paths.map(GeneratorPathItem::path))

        val legacyOperation =
            legacy.paths
                .single()
                .operations
                .single()
        val nativeOperation =
            native.paths
                .single()
                .operations
                .single()
        assertThat(nativeOperation.method).isEqualTo(legacyOperation.method)
        assertThat(nativeOperation.operationId).isEqualTo(legacyOperation.operationId)
        assertThat(nativeOperation.summary).isEqualTo(legacyOperation.summary)
        assertThat(nativeOperation.description).isEqualTo(legacyOperation.description)
        assertThat(nativeOperation.tags).isEqualTo(legacyOperation.tags)
        assertThat(nativeOperation.deprecated).isEqualTo(legacyOperation.deprecated)
        assertThat(nativeOperation.security).isEqualTo(legacyOperation.security)
        assertThat(nativeOperation.extensions.keys).isEqualTo(legacyOperation.extensions.keys)

        val legacyParameter = legacyOperation.parameters.single()
        val nativeParameter = nativeOperation.parameters.single()
        assertThat(nativeParameter.copy(schema = null)).isEqualTo(legacyParameter.copy(schema = null))
        assertThat(GeneratorSchemaTypeClassifier.classify(nativeParameter.schema!!))
            .isEqualTo(GeneratorSchemaTypeClassifier.classify(legacyParameter.schema!!))

        val legacyResponse = legacyOperation.responses.single()
        val nativeResponse = nativeOperation.responses.single()
        assertThat(nativeResponse.status).isEqualTo(legacyResponse.status)
        assertThat(nativeResponse.description).isEqualTo(legacyResponse.description)
        assertThat(nativeResponse.content.map(GeneratorMediaType::key))
            .isEqualTo(legacyResponse.content.map(GeneratorMediaType::key))
        assertThat(GeneratorSchemaTypeClassifier.classify(nativeResponse.content.single().schema!!))
            .isEqualTo(GeneratorSchemaTypeClassifier.classify(legacyResponse.content.single().schema!!))
    }

    @Test
    fun `preserves native security scheme definitions`() {
        val native = OpenApiDocumentParser.parse(securitySchemesOpenApi).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)

        assertThat(native.securitySchemes.keys).containsExactly("ApiKey", "Bearer", "OAuth", "Referenced")
        val apiKey = native.securitySchemes.getValue("ApiKey")
        assertThat(apiKey.type).isEqualTo("apiKey")
        assertThat(apiKey.parameterName).isEqualTo("X-API-Key")
        assertThat(apiKey.placement).isEqualTo("header")
        assertThat(apiKey.deprecated).isTrue()
        assertThat(apiKey.extensions).containsOnlyKeys("x-managed")
        val bearer = native.securitySchemes.getValue("Bearer")
        assertThat(bearer.scheme).isEqualTo("bearer")
        assertThat(bearer.bearerFormat).isEqualTo("JWT")
        val oauthFlow =
            native.securitySchemes
                .getValue("OAuth")
                .flows
                .single()
        assertThat(oauthFlow.type).isEqualTo("deviceAuthorization")
        assertThat(oauthFlow.deviceAuthorizationUrl).isEqualTo("https://auth.example.com/device")
        assertThat(oauthFlow.tokenUrl).isEqualTo("https://auth.example.com/token")
        assertThat(oauthFlow.scopes).containsEntry("items:read", "Read items")
        assertThat(native.securitySchemes.getValue("Referenced"))
            .isEqualTo(native.securitySchemes.getValue("Bearer").copy(name = "Referenced"))
    }

    @Test
    fun `resolves chained local operation component references in native mode`() {
        val native = OpenApiDocumentParser.parse(openApi).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)
        val path = native.paths.single()
        val operation = path.operations.single()

        assertThat(path.parameters.single().name).isEqualTo("id")
        assertThat(
            operation.requestBody!!
                .content
                .single()
                .key,
        ).isEqualTo("application/json")
        assertThat(
            operation.responses
                .single()
                .headers
                .getValue("X-Trace")
                .name,
        ).isEqualTo("X-Trace")
        assertThat(
            operation.responses
                .single()
                .content
                .single()
                .schema,
        ).isNotNull()
    }

    @Test
    fun `preserves OpenAPI 3_2 endpoint semantics in native mode`() {
        val native = OpenApiDocumentParser.parse(openApi32).toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)
        val operations = native.paths.single().operations

        assertThat(operations.map(GeneratorOperation::method)).containsExactly("query", "poll")
        val sequentialMedia =
            operations
                .first()
                .responses
                .single()
                .content
                .single()
        assertThat(sequentialMedia.key).isEqualTo("application/json-seq")
        assertThat(GeneratorSchemaTypeClassifier.classify(sequentialMedia.itemSchema!!))
            .isEqualTo(GeneratorSchemaTypeClassification.Resolved(OasType.Text, false))
    }

    private val openApi =
        """
        openapi: 3.0.4
        info:
          title: Test
          version: "1.0"
        servers:
          - url: https://api.example.com/v1/
        security:
          - oauth: []
        paths:
          /items/{id}:
            parameters:
              - ${'$'}ref: '#/components/parameters/ItemIdAlias'
            post:
              operationId: replaceItem
              summary: Replace an item
              description: Replaces one item.
              tags: [items]
              deprecated: true
              parameters:
                - name: verbose
                  in: query
                  schema: { type: boolean }
              requestBody:
                ${'$'}ref: '#/components/requestBodies/ItemBody'
              security:
                - oauth: [items:write]
              responses:
                '200':
                  ${'$'}ref: '#/components/responses/ItemResponse'
              x-audience: internal
        components:
          parameters:
            ItemIdAlias:
              ${'$'}ref: '#/components/parameters/ItemId'
            ItemId:
              name: id
              in: path
              required: true
              schema: { type: string }
          requestBodies:
            ItemBody:
              required: true
              content:
                application/json:
                  schema:
                    ${'$'}ref: '#/components/schemas/Item'
          responses:
            ItemResponse:
              description: ok
              headers:
                X-Trace:
                  ${'$'}ref: '#/components/headers/Trace'
              content:
                application/json:
                  schema:
                    ${'$'}ref: '#/components/schemas/Item'
          headers:
            Trace:
              schema: { type: string }
          schemas:
            Item:
              type: object
              properties:
                id: { type: string }
          securitySchemes:
            oauth:
              type: oauth2
              flows:
                clientCredentials:
                  tokenUrl: https://auth.example.com/token
                  scopes:
                    items:write: Write items
        """.trimIndent()

    private val openApi32 =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /items:
            query:
              responses:
                '200':
                  description: ok
                  content:
                    application/json-seq:
                      itemSchema: { type: string }
            additionalOperations:
              poll:
                responses:
                  '204': { description: pending }
        """.trimIndent()

    private val securitySchemesOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Security schemes
          version: "1.0"
        paths: {}
        components:
          securitySchemes:
            ApiKey:
              type: apiKey
              name: X-API-Key
              in: header
              deprecated: true
              x-managed: true
            Bearer:
              type: http
              scheme: bearer
              bearerFormat: JWT
            OAuth:
              type: oauth2
              flows:
                deviceAuthorization:
                  deviceAuthorizationUrl: https://auth.example.com/device
                  tokenUrl: https://auth.example.com/token
                  scopes:
                    items:read: Read items
            Referenced:
              ${'$'}ref: '#/components/securitySchemes/Bearer'
        """.trimIndent()
}
