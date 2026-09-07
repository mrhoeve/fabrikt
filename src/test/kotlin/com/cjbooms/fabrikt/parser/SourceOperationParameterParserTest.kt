package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceOperationParameterParserTest {
    @Test
    fun `models path and operation parameters with their serialization metadata`() {
        val document = SourceOpenApiDocumentParser.parse(parameterOpenApi)
        val path = document.operations.paths.single()

        val pathParameter = path.parameters.single()
        assertThat(pathParameter.location).isEqualTo("#/paths/~1subjects~1{id}/parameters/0")
        assertThat(pathParameter.name).isEqualTo("id")
        assertThat(pathParameter.placement)
            .isEqualTo(SourceParameterPlacement.Fixed(SourceFixedParameterPlacement.PATH))
        assertThat(pathParameter.description).isEqualTo("Subject identifier")
        assertThat(pathParameter.required).isTrue()
        assertThat(pathParameter.deprecated).isTrue()
        assertThat(pathParameter.allowEmptyValue).isFalse()
        assertThat(pathParameter.style).isEqualTo("simple")
        assertThat(pathParameter.explode).isFalse()
        assertThat(pathParameter.allowReserved).isTrue()
        assertThat(pathParameter.extensions).containsOnlyKeys("x-kotlin-name")
        assertThat(pathParameter.schema)
            .isSameAs(document.schemaEntryPoints.getValue("#/paths/~1subjects~1{id}/parameters/0/schema"))

        val operationParameters = path.operations.single().parameters
        assertThat(operationParameters).hasSize(2)
        assertThat(operationParameters[0].reference).isEqualTo("#/components/parameters/Trace~0~1Id")
        assertThat(operationParameters[0].name).isNull()
        assertThat(operationParameters[0].schema).isNull()
        assertThat(operationParameters[1].name).isEqualTo("filter")
        assertThat(operationParameters[1].placement)
            .isEqualTo(SourceParameterPlacement.Fixed(SourceFixedParameterPlacement.QUERY))
        assertThat(operationParameters[1].required).isFalse()
        assertThat(operationParameters[1].deprecated).isFalse()
    }

    @Test
    fun `models reusable parameters and preserves escaped source locations`() {
        val document = SourceOpenApiDocumentParser.parse(parameterOpenApi)

        assertThat(document.operations.reusableParameters).containsOnlyKeys("Trace~/Id")
        val parameter = document.operations.reusableParameters.getValue("Trace~/Id")
        assertThat(parameter.location).isEqualTo("#/components/parameters/Trace~0~1Id")
        assertThat(parameter.name).isEqualTo("X-Trace-Id")
        assertThat(parameter.placement)
            .isEqualTo(SourceParameterPlacement.Fixed(SourceFixedParameterPlacement.HEADER))
        assertThat(parameter.schema)
            .isSameAs(document.schemaEntryPoints.getValue("#/components/parameters/Trace~0~1Id/schema"))
    }

    @Test
    fun `models parameter content schemas and media type references`() {
        val document = SourceOpenApiDocumentParser.parse(contentParameterOpenApi)
        val parameters =
            document.operations.paths
                .single()
                .operations
                .single()
                .parameters
        val parameter = parameters[0]

        assertThat(parameter.schema).isNull()
        assertThat(parameter.content.map(SourceParameterContent::mediaType))
            .containsExactly("application/json")
        val jsonContent = parameter.content.single()
        assertThat(jsonContent.location)
            .isEqualTo("#/paths/~1search/get/parameters/0/content/application~1json")
        assertThat(jsonContent.schema)
            .isSameAs(document.schemaEntryPoints.getValue("#/paths/~1search/get/parameters/0/content/application~1json/schema"))
        assertThat(jsonContent.itemSchema).isNull()
        assertThat(jsonContent.extensions).containsOnlyKeys("x-parser")
        val referencedContent = parameters[1].content.single()
        assertThat(referencedContent.reference).isEqualTo("#/components/mediaTypes/Search")
        assertThat(referencedContent.schema).isNull()
    }

    @Test
    fun `recognises querystring parameters and sequential content in OpenAPI 3_2`() {
        val document = SourceOpenApiDocumentParser.parse(openApi32Parameters)
        val operations =
            document.operations.paths
                .single()
                .operations

        val queryStringParameter = operations[0].parameters.single()
        assertThat(queryStringParameter.placement)
            .isEqualTo(SourceParameterPlacement.Fixed(SourceFixedParameterPlacement.QUERYSTRING))
        assertThat(queryStringParameter.content.single().schema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1search/query/parameters/0/content/application~1x-www-form-urlencoded/schema",
                ),
            )

        val sequentialParameter = operations[1].parameters.single()
        assertThat(sequentialParameter.placement)
            .isEqualTo(SourceParameterPlacement.Unrecognised("future-location"))
        assertThat(sequentialParameter.content.single().schema).isNull()
        assertThat(sequentialParameter.content.single().itemSchema)
            .isSameAs(
                document.schemaEntryPoints.getValue(
                    "#/paths/~1search/get/parameters/0/content/application~1jsonl/itemSchema",
                ),
            )
    }

    @Test
    fun `preserves querystring as unrecognised before OpenAPI 3_2`() {
        val document =
            SourceOpenApiDocumentParser.parse(
                openApi32Parameters
                    .replace("openapi: 3.2.0", "openapi: 3.1.2")
                    .replace("\n    query:", "\n    post:"),
            )
        val queryStringParameter =
            document.operations.paths
                .single()
                .operations[0]
                .parameters
                .single()

        assertThat(queryStringParameter.placement)
            .isEqualTo(SourceParameterPlacement.Unrecognised("querystring"))
        assertThat(document.schemaEntryPoints)
            .doesNotContainKey("#/paths/~1search/get/parameters/0/content/application~1jsonl/itemSchema")
    }

    @Test
    fun `collects parameters throughout modern operation containers`() {
        val operations = SourceOpenApiDocumentParser.parse(operationContainersOpenApi).operations

        val rootOperation =
            operations.paths
                .single()
                .operations
                .single()
        assertThat(rootOperation.method).isEqualTo(SourceOperationMethod.Additional("PURGE"))
        assertThat(rootOperation.parameters.single().name).isEqualTo("root")
        val callbackOperation =
            rootOperation.callbacks
                .single()
                .pathItems
                .single()
                .operations
                .single()
        assertThat(callbackOperation.method)
            .isEqualTo(SourceOperationMethod.Fixed(SourceFixedOperationMethod.QUERY))
        assertThat(callbackOperation.parameters.single().name).isEqualTo("callback")

        assertThat(
            operations.webhooks
                .single()
                .operations
                .single()
                .parameters
                .single()
                .name,
        ).isEqualTo("webhook")
        assertThat(
            operations.reusablePathItems
                .getValue("Reusable")
                .operations
                .single()
                .parameters
                .single()
                .name,
        ).isEqualTo("pathItem")
        assertThat(
            operations.reusableCallbacks
                .getValue("ReusableCallback")
                .pathItems
                .single()
                .operations
                .single()
                .parameters
                .single()
                .name,
        ).isEqualTo("reusableCallback")
    }

    private val parameterOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Test
          version: "1.0"
        paths:
          /subjects/{id}:
            parameters:
              - name: id
                in: path
                description: Subject identifier
                required: true
                deprecated: true
                allowEmptyValue: false
                style: simple
                explode: false
                allowReserved: true
                schema:
                  type: string
                x-kotlin-name: subjectId
            get:
              parameters:
                - ${'$'}ref: '#/components/parameters/Trace~0~1Id'
                - name: filter
                  in: query
                  schema:
                    type: object
              responses: {}
        components:
          parameters:
            Trace~/Id:
              name: X-Trace-Id
              in: header
              schema:
                type: string
        """.trimIndent()

    private val contentParameterOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /search:
            get:
              parameters:
                - name: criteria
                  in: query
                  content:
                    application/json:
                      schema:
                        type: object
                      x-parser: json
                - name: reusableCriteria
                  in: query
                  content:
                    application/vnd.example+json:
                      ${'$'}ref: '#/components/mediaTypes/Search'
              responses: {}
        components:
          mediaTypes:
            Search:
              schema:
                type: object
        """.trimIndent()

    private val openApi32Parameters =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /search:
            query:
              parameters:
                - name: queryString
                  in: querystring
                  content:
                    application/x-www-form-urlencoded:
                      schema:
                        type: object
              responses: {}
            get:
              parameters:
                - name: future
                  in: future-location
                  content:
                    application/jsonl:
                      itemSchema:
                        type: object
              responses: {}
        """.trimIndent()

    private val operationContainersOpenApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        paths:
          /root:
            additionalOperations:
              PURGE:
                parameters:
                  - name: root
                    in: query
                    schema: { type: string }
                callbacks:
                  changed:
                    '{${'$'}request.body#/~callbackUrl}':
                      query:
                        parameters:
                          - name: callback
                            in: header
                            schema: { type: string }
                        responses: {}
                responses: {}
        webhooks:
          changed:
            post:
              parameters:
                - name: webhook
                  in: header
                  schema: { type: string }
              responses: {}
        components:
          pathItems:
            Reusable:
              get:
                parameters:
                  - name: pathItem
                    in: query
                    schema: { type: string }
                responses: {}
          callbacks:
            ReusableCallback:
              '{${'$'}request.body#/~callbackUrl}':
                post:
                  parameters:
                    - name: reusableCallback
                      in: cookie
                      schema: { type: string }
                  responses: {}
        """.trimIndent()
}
