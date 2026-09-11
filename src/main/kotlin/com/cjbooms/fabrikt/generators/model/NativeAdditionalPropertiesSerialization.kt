package com.cjbooms.fabrikt.generators.model

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

internal object NativeAdditionalPropertiesSerialization {
    data class DeclaredProperty(
        val sourceName: String,
        val generatedName: String,
        val type: TypeName,
        val required: Boolean,
        val defaultValue: CodeBlock?,
    )

    fun apply(
        type: TypeSpec.Builder,
        modelType: ClassName,
        properties: List<DeclaredProperty>,
        additionalPropertiesName: String,
        additionalPropertiesType: TypeName,
    ) {
        val serializer = modelType.nestedClass("Serializer")
        val kSerializer = ClassName("kotlinx.serialization", "KSerializer")
        type.addAnnotation(
            AnnotationSpec
                .builder(ClassName("kotlinx.serialization", "Serializable"))
                .addMember("with = %T::class", serializer)
                .build(),
        )
        type.addType(
            TypeSpec
                .objectBuilder("Serializer")
                .addSuperinterface(kSerializer.parameterizedBy(modelType))
                .addProperty(serializerDescriptor())
                .addFunction(serialize(modelType, properties, additionalPropertiesName, additionalPropertiesType))
                .addFunction(deserialize(modelType, properties, additionalPropertiesName, additionalPropertiesType))
                .build(),
        )
    }

    private fun serializerDescriptor(): PropertySpec {
        val serialDescriptor = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
        val jsonObject = ClassName("kotlinx.serialization.json", "JsonObject")
        return PropertySpec
            .builder("descriptor", serialDescriptor)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%T.serializer().descriptor", jsonObject)
            .build()
    }

    private fun serialize(
        modelType: ClassName,
        properties: List<DeclaredProperty>,
        additionalPropertiesName: String,
        additionalPropertiesType: TypeName,
    ): FunSpec {
        val encoder = ClassName("kotlinx.serialization.encoding", "Encoder")
        val jsonEncoder = ClassName("kotlinx.serialization.json", "JsonEncoder")
        val jsonObject = ClassName("kotlinx.serialization.json", "JsonObject")
        val encodeToJsonElement = MemberName("kotlinx.serialization.json", "encodeToJsonElement")
        val code =
            CodeBlock
                .builder()
                .addStatement("val jsonEncoder = encoder as? %T ?: error(%S)", jsonEncoder, "Additional properties require a JSON encoder")
                .addStatement("val json = jsonEncoder.json")
                .addStatement(
                    "val values = value.%N.mapValues { (_, item) -> json.%M<%T>(item) }.toMutableMap()",
                    additionalPropertiesName,
                    encodeToJsonElement,
                    additionalPropertiesType,
                )
        properties.forEach { property ->
            if (property.required) {
                code.addStatement(
                    "values[%S] = json.%M<%T>(value.%N)",
                    property.sourceName,
                    encodeToJsonElement,
                    property.type,
                    property.generatedName,
                )
            } else {
                val defaultValue = property.defaultValue ?: CodeBlock.of("null")
                code
                    .beginControlFlow("if (json.configuration.encodeDefaults || value.%N != %L)", property.generatedName, defaultValue)
                    .addStatement(
                        "values[%S] = json.%M<%T>(value.%N)",
                        property.sourceName,
                        encodeToJsonElement,
                        property.type,
                        property.generatedName,
                    ).endControlFlow()
            }
        }
        code.addStatement("jsonEncoder.encodeJsonElement(%T(values))", jsonObject)
        return FunSpec
            .builder("serialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("encoder", encoder)
            .addParameter("value", modelType)
            .addCode(code.build())
            .build()
    }

    private fun deserialize(
        modelType: ClassName,
        properties: List<DeclaredProperty>,
        additionalPropertiesName: String,
        additionalPropertiesType: TypeName,
    ): FunSpec {
        val decoder = ClassName("kotlinx.serialization.encoding", "Decoder")
        val jsonDecoder = ClassName("kotlinx.serialization.json", "JsonDecoder")
        val jsonObject = ClassName("kotlinx.serialization.json", "JsonObject")
        val decodeFromJsonElement = MemberName("kotlinx.serialization.json", "decodeFromJsonElement")
        val code =
            CodeBlock
                .builder()
                .addStatement("val jsonDecoder = decoder as? %T ?: error(%S)", jsonDecoder, "Additional properties require a JSON decoder")
                .addStatement("val json = jsonDecoder.json")
                .addStatement(
                    "val values = (jsonDecoder.decodeJsonElement() as? %T)?.toMutableMap() ?: error(%S)",
                    jsonObject,
                    "Expected a JSON object for ${modelType.simpleName}",
                )
        properties.forEachIndexed { index, property ->
            val elementName = "fabriktProperty${index}Element"
            code.addStatement("val %N = values.remove(%S)", elementName, property.sourceName)
            if (property.required) {
                code.addStatement(
                    "val %N = json.%M<%T>(%N ?: error(%S))",
                    property.generatedName,
                    decodeFromJsonElement,
                    property.type,
                    elementName,
                    "Missing required property ${property.sourceName}",
                )
            } else {
                code.addStatement(
                    "val %N = if (%N == null) %L else json.%M<%T>(%N)",
                    property.generatedName,
                    elementName,
                    property.defaultValue ?: CodeBlock.of("null"),
                    decodeFromJsonElement,
                    property.type,
                    elementName,
                )
            }
        }
        code.add("return %T(\n", modelType).indent()
        properties.forEach { property -> code.add("%N = %N,\n", property.generatedName, property.generatedName) }
        code.add(
            "%N = values.mapValues { (_, item) -> json.%M<%T>(item) }.toMutableMap(),\n",
            additionalPropertiesName,
            decodeFromJsonElement,
            additionalPropertiesType,
        )
        code.unindent().add(")\n")
        return FunSpec
            .builder("deserialize")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("decoder", decoder)
            .returns(modelType)
            .addCode(code.build())
            .build()
    }
}
