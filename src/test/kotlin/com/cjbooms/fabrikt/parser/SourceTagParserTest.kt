package com.cjbooms.fabrikt.parser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceTagParserTest {
    @Test
    fun `models root tag metadata`() {
        val tags = SourceOpenApiDocumentParser.parse(openApi).operations.tags

        assertThat(tags).hasSize(2)
        val child = tags.first()
        assertThat(child.location).isEqualTo("#/tags/0")
        assertThat(child.name).isEqualTo("partner")
        assertThat(child.summary).isEqualTo("Partner operations")
        assertThat(child.description).isEqualTo("Operations available to partners")
        assertThat(child.parent).isEqualTo("external")
        assertThat(child.kind).isEqualTo("audience")
        assertThat(child.externalDocumentation!!.location).isEqualTo("#/tags/0/externalDocs")
        assertThat(child.externalDocumentation!!.url).isEqualTo("https://docs.example.com/partners")
        assertThat(child.extensions).containsOnlyKeys("x-visible")
        assertThat(tags.last().name).isEqualTo("external")
    }

    @Test
    fun `only models OpenAPI 3_2 tag metadata for OpenAPI 3_2`() {
        val tag =
            SourceOpenApiDocumentParser
                .parse(openApi.replace("openapi: 3.2.0", "openapi: 3.1.2"))
                .operations
                .tags
                .first()

        assertThat(tag.name).isEqualTo("partner")
        assertThat(tag.description).isEqualTo("Operations available to partners")
        assertThat(tag.externalDocumentation).isNotNull
        assertThat(tag.summary).isNull()
        assertThat(tag.parent).isNull()
        assertThat(tag.kind).isNull()
    }

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Test
          version: "1.0"
        tags:
          - name: partner
            summary: Partner operations
            description: Operations available to partners
            externalDocs:
              description: Partner guide
              url: https://docs.example.com/partners
            parent: external
            kind: audience
            x-visible: true
          - name: external
            kind: audience
        paths: {}
        """.trimIndent()
}
