package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.model.GeneratorScalarUnionVariantDescriptor
import com.cjbooms.fabrikt.model.OasType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal object NativeScalarUnionSerialization {
    fun apply(
        type: TypeSpec.Builder,
        unionType: ClassName,
        variants: List<GeneratorScalarUnionVariantDescriptor>,
        library: SerializationLibrary,
    ) {
        when (library) {
            SerializationLibrary.JACKSON -> type.addJacksonSerialization(unionType, variants, jackson3 = false)
            SerializationLibrary.JACKSON_3 -> type.addJacksonSerialization(unionType, variants, jackson3 = true)
            SerializationLibrary.KOTLINX_SERIALIZATION -> type.addKotlinxSerialization(unionType, variants)
        }
    }

    private fun TypeSpec.Builder.addJacksonSerialization(
        unionType: ClassName,
        variants: List<GeneratorScalarUnionVariantDescriptor>,
        jackson3: Boolean,
    ) {
        val databindPackage = if (jackson3) "tools.jackson.databind" else "com.fasterxml.jackson.databind"
        val corePackage = if (jackson3) "tools.jackson.core" else "com.fasterxml.jackson.core"
        val serializerType = ClassName(databindPackage, if (jackson3) "ValueSerializer" else "JsonSerializer")
        val deserializerType = ClassName(databindPackage, if (jackson3) "ValueDeserializer" else "JsonDeserializer")
        val serializationContext = ClassName(databindPackage, if (jackson3) "SerializationContext" else "SerializerProvider")
        val deserializationContext = ClassName(databindPackage, "DeserializationContext")
        val jsonGenerator = ClassName(corePackage, "JsonGenerator")
        val jsonParser = ClassName(corePackage, "JsonParser")
        val jsonToken = ClassName(corePackage, "JsonToken")
        addAnnotation(
            AnnotationSpec
                .builder(ClassName("$databindPackage.annotation", "JsonSerialize"))
                .addMember("using = %T::class", unionType.nestedClass(SERIALIZER_NAME))
                .build(),
        )
        addAnnotation(
            AnnotationSpec
                .builder(ClassName("$databindPackage.annotation", "JsonDeserialize"))
                .addMember("using = %T::class", unionType.nestedClass(DESERIALIZER_NAME))
                .build(),
        )
        addType(
            TypeSpec
                .classBuilder(SERIALIZER_NAME)
                .superclass(serializerType.parameterizedBy(unionType))
                .addFunction(
                    FunSpec
                        .builder("serialize")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("value", unionType)
                        .addParameter("generator", jsonGenerator)
                        .addParameter("serializers", serializationContext)
                        .addCode(variants.jacksonSerializeCode(unionType))
                        .build(),
                ).build(),
        )
        addType(
            TypeSpec
                .classBuilder(DESERIALIZER_NAME)
                .superclass(deserializerType.parameterizedBy(unionType))
                .addFunction(
                    FunSpec
                        .builder("deserialize")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("parser", jsonParser)
                        .addParameter("context", deserializationContext)
                        .returns(unionType)
                        .addCode(variants.jacksonDeserializeCode(unionType, jsonToken))
                        .build(),
                ).build(),
        )
    }

    private fun List<GeneratorScalarUnionVariantDescriptor>.jacksonSerializeCode(unionType: ClassName): CodeBlock =
        CodeBlock
            .builder()
            .beginControlFlow("when (value)")
            .apply {
                forEach { variant ->
                    addStatement(
                        "is %T -> generator.%L(value.value)",
                        unionType.nestedClass(variant.name),
                        variant.type.jacksonWriteMethod(),
                    )
                }
            }.endControlFlow()
            .build()

    private fun List<GeneratorScalarUnionVariantDescriptor>.jacksonDeserializeCode(
        unionType: ClassName,
        jsonToken: ClassName,
    ): CodeBlock {
        val integerVariant = firstOrNull { it.type.isInteger() }
        val numberVariant = firstOrNull { it.type.isNumber() }
        return CodeBlock
            .builder()
            .beginControlFlow("return when (parser.currentToken())")
            .apply {
                firstOrNull { it.type == OasType.Text }?.let { variant ->
                    addStatement(
                        "%T.VALUE_STRING -> %T(parser.valueAsString)",
                        jsonToken,
                        unionType.nestedClass(variant.name),
                    )
                }
                firstOrNull { it.type == OasType.Boolean }?.let { variant ->
                    addStatement(
                        "%T.VALUE_TRUE, %T.VALUE_FALSE -> %T(parser.booleanValue)",
                        jsonToken,
                        jsonToken,
                        unionType.nestedClass(variant.name),
                    )
                }
                integerVariant?.let { variant ->
                    addStatement(
                        "%T.VALUE_NUMBER_INT -> %T(parser.%L)",
                        jsonToken,
                        unionType.nestedClass(variant.name),
                        variant.type.jacksonReadProperty(),
                    )
                }
                numberVariant?.let { variant ->
                    val tokens =
                        if (integerVariant == null) {
                            "%T.VALUE_NUMBER_INT, %T.VALUE_NUMBER_FLOAT"
                        } else {
                            "%T.VALUE_NUMBER_FLOAT"
                        }
                    val arguments =
                        if (integerVariant == null) {
                            arrayOf(
                                jsonToken,
                                jsonToken,
                                unionType.nestedClass(variant.name),
                                variant.type.jacksonReadProperty(),
                            )
                        } else {
                            arrayOf(
                                jsonToken,
                                unionType.nestedClass(variant.name),
                                variant.type.jacksonReadProperty(),
                            )
                        }
                    addStatement("$tokens -> %T(parser.%L)", *arguments)
                }
                addStatement(
                    "else -> context.reportInputMismatch(%T::class.java, %S)",
                    unionType,
                    "Unexpected JSON value for ${unionType.simpleName}",
                )
            }.endControlFlow()
            .build()
    }

    private fun TypeSpec.Builder.addKotlinxSerialization(
        unionType: ClassName,
        variants: List<GeneratorScalarUnionVariantDescriptor>,
    ) {
        val serializer = unionType.nestedClass(SERIALIZER_NAME)
        val kSerializer = ClassName("kotlinx.serialization", "KSerializer")
        val serialDescriptor = ClassName("kotlinx.serialization.descriptors", "SerialDescriptor")
        val encoder = ClassName("kotlinx.serialization.encoding", "Encoder")
        val decoder = ClassName("kotlinx.serialization.encoding", "Decoder")
        val jsonEncoder = ClassName("kotlinx.serialization.json", "JsonEncoder")
        val jsonDecoder = ClassName("kotlinx.serialization.json", "JsonDecoder")
        val jsonElement = ClassName("kotlinx.serialization.json", "JsonElement")
        val jsonPrimitive = ClassName("kotlinx.serialization.json", "JsonPrimitive")
        addAnnotation(
            AnnotationSpec
                .builder(ClassName("kotlinx.serialization", "Serializable"))
                .addMember("with = %T::class", serializer)
                .build(),
        )
        addType(
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
                        .addCode(variants.kotlinxSerializeCode(unionType, jsonEncoder, jsonPrimitive))
                        .build(),
                ).addFunction(
                    FunSpec
                        .builder("deserialize")
                        .addModifiers(KModifier.OVERRIDE)
                        .addParameter("decoder", decoder)
                        .returns(unionType)
                        .addCode(variants.kotlinxDeserializeCode(unionType, jsonDecoder, jsonPrimitive))
                        .build(),
                ).build(),
        )
    }

    private fun List<GeneratorScalarUnionVariantDescriptor>.kotlinxSerializeCode(
        unionType: ClassName,
        jsonEncoder: ClassName,
        jsonPrimitive: ClassName,
    ): CodeBlock =
        CodeBlock
            .builder()
            .addStatement("val jsonEncoder = encoder as? %T ?: error(%S)", jsonEncoder, "Scalar unions require a JSON encoder")
            .beginControlFlow("val primitive = when (value)")
            .apply {
                forEach { variant ->
                    addStatement(
                        "is %T -> %T(value.value)",
                        unionType.nestedClass(variant.name),
                        jsonPrimitive,
                    )
                }
            }.endControlFlow()
            .addStatement("jsonEncoder.encodeJsonElement(primitive)")
            .build()

    private fun List<GeneratorScalarUnionVariantDescriptor>.kotlinxDeserializeCode(
        unionType: ClassName,
        jsonDecoder: ClassName,
        jsonPrimitive: ClassName,
    ): CodeBlock =
        CodeBlock
            .builder()
            .addStatement("val jsonDecoder = decoder as? %T ?: error(%S)", jsonDecoder, "Scalar unions require a JSON decoder")
            .addStatement(
                "val primitive = jsonDecoder.decodeJsonElement() as? %T ?: error(%S)",
                jsonPrimitive,
                "Expected a JSON scalar for ${unionType.simpleName}",
            ).beginControlFlow("return when")
            .apply {
                firstOrNull { it.type == OasType.Text }?.let { variant ->
                    addStatement("primitive.isString -> %T(primitive.content)", unionType.nestedClass(variant.name))
                }
                firstOrNull { it.type == OasType.Boolean }?.let { variant ->
                    addStatement(
                        "primitive.content == %S || primitive.content == %S -> %T(primitive.content.toBooleanStrict())",
                        "true",
                        "false",
                        unionType.nestedClass(variant.name),
                    )
                }
                firstOrNull { it.type.isInteger() }?.let { variant ->
                    addStatement(
                        "primitive.content.%L() != null -> %T(primitive.content.%L())",
                        variant.type.kotlinParseOrNullMethod(),
                        unionType.nestedClass(variant.name),
                        variant.type.kotlinParseMethod(),
                    )
                }
                firstOrNull { it.type.isNumber() }?.let { variant ->
                    addStatement(
                        "primitive.content.%L() != null -> %T(primitive.content.%L())",
                        variant.type.kotlinParseOrNullMethod(),
                        unionType.nestedClass(variant.name),
                        variant.type.kotlinParseMethod(),
                    )
                }
                addStatement("else -> error(%S)", "Unexpected JSON value for ${unionType.simpleName}")
            }.endControlFlow()
            .build()

    private fun OasType.isInteger(): Boolean = this == OasType.Integer || this == OasType.Int32 || this == OasType.Int64

    private fun OasType.isNumber(): Boolean = this == OasType.Number || this == OasType.Float || this == OasType.Double

    private fun OasType.jacksonWriteMethod(): String =
        when (this) {
            OasType.Text -> "writeString"
            OasType.Boolean -> "writeBoolean"
            OasType.Integer, OasType.Int32, OasType.Int64, OasType.Number, OasType.Float, OasType.Double -> "writeNumber"
            else -> error("Unsupported scalar union type: $this")
        }

    private fun OasType.jacksonReadProperty(): String =
        when (this) {
            OasType.Integer, OasType.Int32 -> "intValue"
            OasType.Int64 -> "longValue"
            OasType.Number -> "decimalValue"
            OasType.Float -> "floatValue"
            OasType.Double -> "doubleValue"
            else -> error("Unsupported numeric scalar union type: $this")
        }

    private fun OasType.kotlinParseOrNullMethod(): String =
        when (this) {
            OasType.Integer, OasType.Int32 -> "toIntOrNull"
            OasType.Int64 -> "toLongOrNull"
            OasType.Number -> "toBigDecimalOrNull"
            OasType.Float -> "toFloatOrNull"
            OasType.Double -> "toDoubleOrNull"
            else -> error("Unsupported numeric scalar union type: $this")
        }

    private fun OasType.kotlinParseMethod(): String = kotlinParseOrNullMethod().removeSuffix("OrNull")

    private const val SERIALIZER_NAME = "Serializer"
    private const val DESERIALIZER_NAME = "Deserializer"
}
