package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.model.OasType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratorOperationAdapterParityTest {
    @Test
    fun `expands native server variables with their defaults`() {
        listOf("3.0.4", "3.1.2", "3.2.0").forEach { version ->
            val native =
                OpenApiDocumentParser
                    .parse(serverVariablesOpenApi(version, "https://{region}.example.com:{port}/{version}/"))
                    .toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)

            assertThat(native.serverUrl).isEqualTo("https://eu.example.com:8443/v1/")
            assertThat(native.basePath).isEqualTo("/v1")
        }
    }

    @Test
    fun `expands variables in relative native server URLs`() {
        val native =
            OpenApiDocumentParser
                .parse(serverVariablesOpenApi("3.2.0", "/{version}/"))
                .toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)

        assertThat(native.serverUrl).isEqualTo("/v1/")
        assertThat(native.basePath).isEqualTo("/v1")
    }

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
        assertThat(nativeOperation.externalDocumentation?.description).isEqualTo("Migration guide")
        assertThat(nativeOperation.externalDocumentation?.url).isEqualTo("https://docs.example.com/migrate")
        assertThat(nativeOperation.security).isEqualTo(legacyOperation.security)
        assertThat(nativeOperation.extensions.keys).isEqualTo(legacyOperation.extensions.keys)

        val legacyParameter = legacyOperation.parameters.single()
        val nativeParameter = nativeOperation.parameters.single()
        assertThat(nativeParameter.copy(schema = null)).isEqualTo(legacyParameter.copy(schema = null))
        assertThat(nativeParameter.style).isEqualTo("form")
        assertThat(nativeParameter.allowReserved).isTrue()
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

    @Test
    fun `preserves native operation examples links and extensions`() {
        val document =
            OpenApiDocumentParser
                .parse(operationMetadataOpenApi)
                .toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)
        val operation =
            document
                .paths
                .single()
                .operations
                .single()

        val parameter = operation.parameters.single()
        assertThat(parameter.allowEmptyValue).isTrue()
        assertThat(parameter.example!!.textValue()).isEqualTo("compact")
        assertThat(
            parameter.examples
                .getValue("expanded")
                .value!!
                .textValue(),
        ).isEqualTo("full")
        assertThat(parameter.extensions).containsOnlyKeys("x-parameter")

        val requestBody = operation.requestBody!!
        assertThat(requestBody.extensions).containsOnlyKeys("x-request")
        val requestMediaType = requestBody.content.single()
        assertThat(requestMediaType.example!!.path("id").intValue()).isEqualTo(1)
        val sharedExample = requestMediaType.examples.getValue("shared")
        assertThat(sharedExample.name).isEqualTo("shared")
        assertThat(sharedExample.summary).isEqualTo("Reusable example")
        assertThat(sharedExample.dataValue!!.path("id").intValue()).isEqualTo(42)
        assertThat(sharedExample.serializedValue).isEqualTo("{\"id\":42}")
        assertThat(sharedExample.extensions).containsOnlyKeys("x-example")
        assertThat(requestMediaType.extensions).containsOnlyKeys("x-media")

        val response = operation.responses.single()
        assertThat(response.extensions).containsOnlyKeys("x-response")
        val header = response.headers.getValue("X-Result")
        assertThat(header.allowEmptyValue).isFalse()
        assertThat(header.style).isEqualTo("simple")
        assertThat(header.example!!.intValue()).isEqualTo(1)
        assertThat(
            header.examples
                .getValue("two")
                .value!!
                .intValue(),
        ).isEqualTo(2)
        assertThat(header.extensions).containsOnlyKeys("x-header")

        val link = response.links.getValue("createdItem")
        assertThat(link.name).isEqualTo("createdItem")
        assertThat(link.operationId).isEqualTo("getItem")
        assertThat(link.parameters.getValue("path.id").textValue()).isEqualTo("${'$'}response.body#/id")
        assertThat(link.requestBody!!.path("audit").booleanValue()).isTrue()
        assertThat(link.description).isEqualTo("Fetch the created item")
        assertThat(link.extensions).containsOnlyKeys("x-link")
        assertThat(link.server!!.url).isEqualTo("https://{region}.example.com")
        assertThat(link.server!!.name).isEqualTo("primary")
        assertThat(link.server!!.extensions).containsOnlyKeys("x-server")
        val region = link.server!!.variables.getValue("region")
        assertThat(region.name).isEqualTo("region")
        assertThat(region.enumValues).containsExactly("eu", "us")
        assertThat(region.defaultValue).isEqualTo("eu")
        assertThat(region.extensions).containsOnlyKeys("x-variable")

        val reusableExample = document.reusableExamples.getValue("ReusableAlias")
        assertThat(reusableExample.name).isEqualTo("ReusableAlias")
        assertThat(reusableExample.summary).isEqualTo("Reusable example")
        assertThat(reusableExample.dataValue!!.path("id").intValue()).isEqualTo(42)
        val reusableLink = document.reusableLinks.getValue("ItemByIdAlias")
        assertThat(reusableLink.name).isEqualTo("ItemByIdAlias")
        assertThat(reusableLink.operationId).isEqualTo("getItem")
        assertThat(reusableLink.server!!.url).isEqualTo("https://{region}.example.com")
        val reusableHeader = document.reusableHeaders.getValue("TraceAlias")
        assertThat(reusableHeader.name).isEqualTo("TraceAlias")
        assertThat(reusableHeader.description).isEqualTo("Trace identifier")
        assertThat(reusableHeader.example!!.textValue()).isEqualTo("trace-123")
        val reusableMediaType = document.reusableMediaTypes.getValue("ItemJsonAlias")
        assertThat(reusableMediaType.key).isEqualTo("ItemJsonAlias")
        assertThat(reusableMediaType.example!!.path("id").intValue()).isEqualTo(7)
        assertThat(reusableMediaType.extensions).containsOnlyKeys("x-component-media")
    }

    @Test
    fun `preserves native API hierarchy metadata and effective servers`() {
        val document =
            OpenApiDocumentParser
                .parse(apiMetadataOpenApi)
                .toGeneratorOperationDocument(SchemaGenerationMode.NATIVE)

        assertThat(document.externalDocumentation!!.url).isEqualTo("https://docs.example.com")
        assertThat(document.externalDocumentation!!.extensions).containsOnlyKeys("x-docs")
        val tag = document.tags.single()
        assertThat(tag.name).isEqualTo("items")
        assertThat(tag.summary).isEqualTo("Items summary")
        assertThat(tag.parent).isEqualTo("resources")
        assertThat(tag.kind).isEqualTo("nav")
        assertThat(tag.externalDocumentation!!.url).isEqualTo("https://docs.example.com/items")
        assertThat(tag.extensions).containsOnlyKeys("x-tag")

        assertThat(document.servers.map(GeneratorServer::url))
            .containsExactly("https://{region}.example.com", "https://backup.example.com")
        val documentRegion =
            document.servers
                .first()
                .variables
                .getValue("region")
        assertThat(documentRegion.defaultValue).isEqualTo("eu")
        assertThat(documentRegion.enumValues).containsExactly("eu", "us")

        val path = document.paths.single()
        assertThat(path.summary).isEqualTo("Items path")
        assertThat(path.description).isEqualTo("Operations on items")
        assertThat(path.extensions).containsOnlyKeys("x-path")
        assertThat(path.servers.single().url).isEqualTo("https://path.example.com")
        assertThat(
            path.operations
                .first { it.method == "get" }
                .servers
                .single()
                .url,
        ).isEqualTo("https://path.example.com")
        val postServers = path.operations.first { it.method == "post" }.servers
        assertThat(postServers.map(GeneratorServer::url))
            .containsExactly("https://write.example.com", "https://write-backup.example.com")
        assertThat(postServers.first().name).isEqualTo("primary-write")
        assertThat(postServers.first().extensions).containsOnlyKeys("x-operation-server")
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
              externalDocs:
                description: Migration guide
                url: https://docs.example.com/migrate
              parameters:
                - name: verbose
                  in: query
                  style: form
                  allowReserved: true
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

    private fun serverVariablesOpenApi(
        version: String,
        serverUrl: String,
    ): String =
        """
        openapi: $version
        info:
          title: Test
          version: "1.0"
        servers:
          - url: $serverUrl
            variables:
              region:
                default: eu
              port:
                default: "8443"
              version:
                default: v1
        paths: {}
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

    private val operationMetadataOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Operation metadata
          version: "1.0"
        paths:
          /items:
            post:
              parameters:
                - name: mode
                  in: query
                  allowEmptyValue: true
                  schema: { type: string }
                  example: compact
                  examples:
                    expanded: { value: full }
                  x-parameter: retained
              requestBody:
                x-request: retained
                content:
                  application/json:
                    schema: { type: object }
                    example: { id: 1 }
                    examples:
                      shared:
                        ${'$'}ref: '#/components/examples/Reusable'
                    x-media: retained
              responses:
                '201':
                  description: created
                  x-response: retained
                  headers:
                    X-Result:
                      allowEmptyValue: false
                      style: simple
                      schema: { type: integer }
                      example: 1
                      examples:
                        two: { value: 2 }
                      x-header: retained
                  links:
                    createdItem:
                      ${'$'}ref: '#/components/links/ItemById'
        components:
          examples:
            Reusable:
              summary: Reusable example
              dataValue: { id: 42 }
              serializedValue: '{"id":42}'
              x-example: retained
            ReusableAlias:
              ${'$'}ref: '#/components/examples/Reusable'
          links:
            ItemById:
              operationId: getItem
              parameters:
                path.id: '${'$'}response.body#/id'
              requestBody: { audit: true }
              description: Fetch the created item
              server:
                url: https://{region}.example.com
                name: primary
                variables:
                  region:
                    enum: [eu, us]
                    default: eu
                    x-variable: retained
                x-server: retained
              x-link: retained
            ItemByIdAlias:
              ${'$'}ref: '#/components/links/ItemById'
          headers:
            Trace:
              description: Trace identifier
              schema: { type: string }
              example: trace-123
            TraceAlias:
              ${'$'}ref: '#/components/headers/Trace'
          mediaTypes:
            ItemJson:
              schema: { type: object }
              example: { id: 7 }
              x-component-media: retained
            ItemJsonAlias:
              ${'$'}ref: '#/components/mediaTypes/ItemJson'
        """.trimIndent()

    private val apiMetadataOpenApi =
        """
        openapi: 3.2.0
        info:
          title: API metadata
          version: "1.0"
        externalDocs:
          description: API documentation
          url: https://docs.example.com
          x-docs: retained
        tags:
          - name: items
            summary: Items summary
            description: Item operations
            parent: resources
            kind: nav
            externalDocs:
              url: https://docs.example.com/items
            x-tag: retained
        servers:
          - url: https://{region}.example.com
            description: Primary API
            variables:
              region:
                enum: [eu, us]
                default: eu
          - url: https://backup.example.com
        paths:
          /items:
            summary: Items path
            description: Operations on items
            x-path: retained
            servers:
              - url: https://path.example.com
            get:
              responses:
                '200': { description: ok }
            post:
              servers:
                - url: https://write.example.com
                  name: primary-write
                  x-operation-server: retained
                - url: https://write-backup.example.com
              responses:
                '201': { description: created }
        """.trimIndent()
}
