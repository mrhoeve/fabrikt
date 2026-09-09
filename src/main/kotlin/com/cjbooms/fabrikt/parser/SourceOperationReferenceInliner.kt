package com.cjbooms.fabrikt.parser

import com.cjbooms.fabrikt.util.YamlObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI

internal class SourceOperationReferenceInliner(
    private val documentLoader: SourceDocumentLoader,
) {
    private val documents = mutableMapOf<URI, JsonNode>()

    fun inline(
        input: String,
        documentUri: URI,
    ): JsonNode {
        val canonicalDocumentUri = documentUri.withoutFragment().normalize().toAsciiUri()
        val root = YamlObjectMapper.instance.readTree(input)
        documents[canonicalDocumentUri] = root
        return inlineDocument(root.deepCopy(), canonicalDocumentUri)
    }

    private fun inlineDocument(
        root: JsonNode,
        documentUri: URI,
    ): JsonNode {
        val document = root as? ObjectNode ?: return root
        inlineMap(document["paths"], documentUri, ReferenceKind.PATH_ITEM)
        inlineMap(document["webhooks"], documentUri, ReferenceKind.PATH_ITEM)
        (document["components"] as? ObjectNode)?.let { components ->
            inlineMap(components["pathItems"], documentUri, ReferenceKind.PATH_ITEM)
            inlineMap(components["callbacks"], documentUri, ReferenceKind.CALLBACK)
            inlineMap(components["parameters"], documentUri, ReferenceKind.PARAMETER)
            inlineMap(components["requestBodies"], documentUri, ReferenceKind.REQUEST_BODY)
            inlineMap(components["responses"], documentUri, ReferenceKind.RESPONSE)
            inlineMap(components["headers"], documentUri, ReferenceKind.HEADER)
            inlineMap(components["mediaTypes"], documentUri, ReferenceKind.MEDIA_TYPE)
        }
        return document
    }

    private fun inlineMap(
        node: JsonNode?,
        documentUri: URI,
        kind: ReferenceKind,
    ) {
        val values = node as? ObjectNode ?: return
        values.properties().toList().forEach { (name, value) ->
            resolve(value, documentUri, kind, emptySet())?.let { values.replace(name, it) }
        }
    }

    private fun inlineArray(
        node: JsonNode?,
        documentUri: URI,
        kind: ReferenceKind,
        visited: Set<URI>,
    ) {
        val values = node as? ArrayNode ?: return
        (0 until values.size()).forEach { index ->
            resolve(values[index], documentUri, kind, visited)?.let { values.set(index, it) }
        }
    }

    private fun resolve(
        value: JsonNode,
        documentUri: URI,
        kind: ReferenceKind,
        visited: Set<URI>,
    ): ObjectNode? {
        val source = value as? ObjectNode ?: return null
        val reference = source["\$ref"]?.takeIf(JsonNode::isTextual)?.textValue()
        if (reference == null) return inlineContents(source.deepCopy(), documentUri, kind, visited)

        val resolvedUri = reference.resolveAgainst(documentUri) ?: return inlineContents(source.deepCopy(), documentUri, kind, visited)
        if (resolvedUri in visited) return inlineContents(source.deepCopy(), documentUri, kind, visited)
        val targetDocumentUri = resolvedUri.withoutFragment()
        val targetDocument =
            documents.getOrPut(targetDocumentUri) {
                YamlObjectMapper.instance.readTree(documentLoader.load(targetDocumentUri))
            }
        val target =
            resolvedUri.fragment
                ?.takeIf { it.startsWith('/') }
                ?.let(targetDocument::at)
                ?: targetDocument
        require(!target.isMissingNode) { "Unresolvable operation reference: $resolvedUri" }
        val resolved =
            inlineContents(
                (target as? ObjectNode)?.deepCopy() ?: return null,
                targetDocumentUri,
                kind,
                visited + resolvedUri,
            )
        val siblings = source.deepCopy().also { it.remove("\$ref") }
        val resolvedSiblings = inlineContents(siblings, documentUri, kind, visited)
        resolvedSiblings.properties().forEach { (name, sibling) -> resolved.replace(name, sibling) }
        return resolved
    }

    private fun inlineContents(
        value: ObjectNode,
        documentUri: URI,
        kind: ReferenceKind,
        visited: Set<URI>,
    ): ObjectNode {
        when (kind) {
            ReferenceKind.PATH_ITEM -> {
                inlineArray(value["parameters"], documentUri, ReferenceKind.PARAMETER, visited)
                SourceFixedOperationMethod.entries.forEach { method ->
                    resolve(value[method.fieldName] ?: return@forEach, documentUri, ReferenceKind.OPERATION, visited)
                        ?.let { value.replace(method.fieldName, it) }
                }
                inlineMapWithVisited(value["additionalOperations"], documentUri, ReferenceKind.OPERATION, visited)
            }
            ReferenceKind.OPERATION -> {
                inlineArray(value["parameters"], documentUri, ReferenceKind.PARAMETER, visited)
                resolveField(value, "requestBody", documentUri, ReferenceKind.REQUEST_BODY, visited)
                inlineMapWithVisited(value["responses"], documentUri, ReferenceKind.RESPONSE, visited)
                inlineMapWithVisited(value["callbacks"], documentUri, ReferenceKind.CALLBACK, visited)
            }
            ReferenceKind.PARAMETER, ReferenceKind.HEADER -> {
                absolutizeSchemaReferences(value["schema"], documentUri)
                inlineContent(value["content"], documentUri, visited)
            }
            ReferenceKind.REQUEST_BODY -> inlineContent(value["content"], documentUri, visited)
            ReferenceKind.RESPONSE -> {
                inlineMapWithVisited(value["headers"], documentUri, ReferenceKind.HEADER, visited)
                inlineContent(value["content"], documentUri, visited)
                inlineMapWithVisited(value["links"], documentUri, ReferenceKind.LINK, visited)
            }
            ReferenceKind.CALLBACK -> inlineMapWithVisited(value, documentUri, ReferenceKind.PATH_ITEM, visited)
            ReferenceKind.MEDIA_TYPE -> {
                absolutizeSchemaReferences(value["schema"], documentUri)
                inlineMapWithVisited(value["encoding"], documentUri, ReferenceKind.ENCODING, visited)
            }
            ReferenceKind.ENCODING -> inlineMapWithVisited(value["headers"], documentUri, ReferenceKind.HEADER, visited)
            ReferenceKind.LINK -> Unit
        }
        return value
    }

    private fun inlineContent(
        node: JsonNode?,
        documentUri: URI,
        visited: Set<URI>,
    ) = inlineMapWithVisited(node, documentUri, ReferenceKind.MEDIA_TYPE, visited)

    private fun inlineMapWithVisited(
        node: JsonNode?,
        documentUri: URI,
        kind: ReferenceKind,
        visited: Set<URI>,
    ) {
        val values = node as? ObjectNode ?: return
        values.properties().toList().forEach { (name, value) ->
            resolve(value, documentUri, kind, visited)?.let { values.replace(name, it) }
        }
    }

    private fun resolveField(
        parent: ObjectNode,
        fieldName: String,
        documentUri: URI,
        kind: ReferenceKind,
        visited: Set<URI>,
    ) {
        resolve(parent[fieldName] ?: return, documentUri, kind, visited)?.let { parent.replace(fieldName, it) }
    }

    private fun absolutizeSchemaReferences(
        schema: JsonNode?,
        inheritedBaseUri: URI,
    ) {
        when (schema) {
            is ObjectNode -> {
                val baseUri = schema["\$id"]?.takeIf(JsonNode::isTextual)?.textValue()?.resolveAgainst(inheritedBaseUri) ?: inheritedBaseUri
                schema["\$ref"]
                    ?.takeIf(JsonNode::isTextual)
                    ?.textValue()
                    ?.resolveAgainst(baseUri)
                    ?.let { schema.put("\$ref", it.toASCIIString()) }
                schema.properties().forEach { (_, child) -> absolutizeSchemaReferences(child, baseUri) }
            }
            is ArrayNode -> schema.forEach { child -> absolutizeSchemaReferences(child, inheritedBaseUri) }
        }
    }

    private enum class ReferenceKind {
        PATH_ITEM,
        OPERATION,
        PARAMETER,
        REQUEST_BODY,
        RESPONSE,
        CALLBACK,
        HEADER,
        MEDIA_TYPE,
        ENCODING,
        LINK,
    }
}
