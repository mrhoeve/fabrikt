package com.cjbooms.fabrikt.generators.model

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

internal object NativeObjectUnionSerialization {
    fun apply(
        type: TypeSpec.Builder,
        unionType: ClassName,
        members: List<TypeName>,
    ) {
        val serializer = unionType.nestedClass(SERIALIZER_NAME)
        val kSerializer = ClassName("kotlinx.serialization", "KSerializer")
        val serialDescriptor = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
        val encoder = ClassName("kotlinx.serialization.encoding", "Encoder")
        val decoder = ClassName("kotlinx.serialization.encoding", "Decoder")
        val jsonEncoder = ClassName("kotlinx.serialization.json", "JsonEncoder")
        val jsonDecoder = ClassName("kotlinx.serialization.json", "JsonDecoder")
        val jsonElement = ClassName("kotlinx.serialization.json", "JsonElement")

        type.addAnnotation(
            AnnotationSpec
                .builder(ClassName("kotlinx.serialization", "Serializable"))
                .addMember("with = %T::class", serializer)
                .build(),
        )
        type.addType(
            TypeSpec
                .objectBuilder(SERIALIZER_NAME)
                .addSuperinterface(kSerializer.parameterizedBy(unionType))
                .addProperty(
                    PropertySpec
                        .builder("descriptor", serialDescriptor)
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer("%T.serializer().descriptor", jsonElement)
                        .build(),
                ).addFunction(
                    FunSpec
                        .builder("serialize")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("encoder", encoder)
                        .addParameter("value", unionType)
                        .addCode(members.serializeCode(unionType, jsonEncoder))
                        .build(),
                ).addFunction(
                    FunSpec
                        .builder("deserialize")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("decoder", decoder)
                        .returns(unionType)
                        .addCode(members.deserializeCode(unionType, jsonDecoder))
                        .build(),
                ).build(),
        )
    }

    private fun List<TypeName>.serializeCode(
        unionType: ClassName,
        jsonEncoder: ClassName,
    ): CodeBlock =
        CodeBlock
            .builder()
            .addStatement("val jsonEncoder = encoder as? %T ?: error(%S)", jsonEncoder, "Object unions require a JSON encoder")
            .beginControlFlow("val element = when (value)")
            .apply {
                forEach { member ->
                    addStatement(
                        "is %T -> jsonEncoder.json.encodeToJsonElement(%T.serializer(), value)",
                        member,
                        member,
                    )
                }
            }.endControlFlow()
            .addStatement("jsonEncoder.encodeJsonElement(element)")
            .build()

    private fun List<TypeName>.deserializeCode(
        unionType: ClassName,
        jsonDecoder: ClassName,
    ): CodeBlock =
        CodeBlock
            .builder()
            .addStatement("val jsonDecoder = decoder as? %T ?: error(%S)", jsonDecoder, "Object unions require a JSON decoder")
            .addStatement("val element = jsonDecoder.decodeJsonElement()")
            .beginControlFlow("val matches = buildList<%T>", unionType)
            .apply {
                forEach { member ->
                    addStatement(
                        "runCatching { jsonDecoder.json.decodeFromJsonElement(%T.serializer(), element) }.getOrNull()?.let(::add)",
                        member,
                    )
                }
            }.endControlFlow()
            .addStatement(
                "return matches.singleOrNull() ?: error(%S + matches.size)",
                "Expected exactly one ${unionType.simpleName} variant but matched ",
            ).build()

    private const val SERIALIZER_NAME = "Serializer"
}
