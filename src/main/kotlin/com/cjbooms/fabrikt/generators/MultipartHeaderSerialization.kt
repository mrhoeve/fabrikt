package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.MultipartHeaderParameter
import com.squareup.kotlinpoet.CodeBlock

internal fun MultipartHeaderParameter.toWireValue(expression: String): CodeBlock =
    when (typeInfo) {
        is KotlinTypeInfo.Enum -> CodeBlock.of("%L.value", expression)
        is KotlinTypeInfo.Array -> {
            if (typeInfo.parameterizedType is KotlinTypeInfo.Enum) {
                CodeBlock.of("%L.joinToString(%S) { it.value }", expression, ",")
            } else {
                CodeBlock.of("%L.joinToString(%S)", expression, ",")
            }
        }
        is KotlinTypeInfo.Map,
        is KotlinTypeInfo.MapTypeAdditionalProperties,
        is KotlinTypeInfo.SimpleTypedAdditionalProperties,
        ->
            if (explode) {
                CodeBlock.of("%L.entries.joinToString(%S) { (key, value) -> %S + key + %S + value }", expression, ",", "", "=")
            } else {
                CodeBlock.of(
                    "%L.entries.flatMap { (key, value) -> listOf(key, value) }.joinToString(%S)",
                    expression,
                    ",",
                )
            }
        is KotlinTypeInfo.Object -> objectWireValue(expression)
        else -> CodeBlock.of("%L.toString()", expression)
    }

private fun MultipartHeaderParameter.objectWireValue(expression: String): CodeBlock {
    val value = CodeBlock.builder().add("buildList {")
    objectProperties.forEach { property ->
        val propertyExpression = "$expression.${property.propertyName}"
        if (property.nullable) value.add("\n%L?.let {", propertyExpression)
        if (explode) {
            value.add(
                "\nadd(%S + %L)",
                "${property.fieldName}=",
                property.typeInfo.toWireValue(if (property.nullable) "it" else propertyExpression),
            )
        } else {
            value.add("\nadd(%S)", property.fieldName)
            value.add("\nadd(%L)", property.typeInfo.toWireValue(if (property.nullable) "it" else propertyExpression))
        }
        if (property.nullable) value.add("\n}")
    }
    return value.add("\n}.joinToString(%S)", ",").build()
}

private fun KotlinTypeInfo.toWireValue(expression: String): CodeBlock =
    when (this) {
        is KotlinTypeInfo.Enum -> CodeBlock.of("%L.value", expression)
        is KotlinTypeInfo.Array ->
            if (parameterizedType is KotlinTypeInfo.Enum) {
                CodeBlock.of("%L.joinToString(%S) { it.value }", expression, ",")
            } else {
                CodeBlock.of("%L.joinToString(%S)", expression, ",")
            }
        else -> CodeBlock.of("%L.toString()", expression)
    }
