package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.generators.TypeFactory.createMapOfStringToNonNullType
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolution
import com.cjbooms.fabrikt.model.GeneratorModelDescriptor
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.ModelType
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.model.OasType
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.util.NormalisedString.toEnumName
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal class NativeModelGenerator(
    private val basePackage: String,
) {
    fun generate(descriptors: Collection<GeneratorModelDescriptor>): Models =
        Models(
            descriptors.mapNotNull { descriptor ->
                val type = descriptor.resolvedType() ?: return@mapNotNull null
                val typeSpec =
                    when (type) {
                        OasType.Object -> descriptor.toDataClass()
                        OasType.Enum -> descriptor.toEnum()
                        else -> null
                    }
                typeSpec?.let { ModelType(it, basePackage) }
            },
        )

    private fun GeneratorModelDescriptor.toDataClass(): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
        val type = TypeSpec.classBuilder(name)

        properties.forEach { property ->
            val resolvedType = property.kotlinType as? GeneratorKotlinTypeResolution.Resolved ?: return@forEach
            val nullable = !property.required || resolvedType.nullable
            val propertyName = property.name.toKotlinParameterName()
            val typeName = ModelGenerator.toModelType(basePackage, resolvedType.typeInfo, nullable)
            val parameter = ParameterSpec.builder(propertyName, typeName)
            if (!property.required) parameter.defaultValue("null")
            constructor.addParameter(parameter.build())
            type.addProperty(
                PropertySpec
                    .builder(propertyName, typeName)
                    .initializer(propertyName)
                    .build(),
            )
        }

        if (constructor.parameters.isNotEmpty()) type.addModifiers(KModifier.DATA)
        return type.primaryConstructor(constructor.build()).build()
    }

    private fun GeneratorModelDescriptor.toEnum(): TypeSpec {
        val enum = (kotlinType as GeneratorKotlinTypeResolution.Resolved).typeInfo as KotlinTypeInfo.Enum
        val enumType = ModelGenerator.generatedType(basePackage, enum.enumClassName)
        val type =
            TypeSpec
                .enumBuilder(enumType)
                .primaryConstructor(
                    FunSpec
                        .constructorBuilder()
                        .addParameter("value", String::class)
                        .build(),
                )

        enum.entries.forEach { value ->
            type.addEnumConstant(
                value.toEnumName(),
                TypeSpec
                    .anonymousClassBuilder()
                    .addSuperclassConstructorParameter("%S", value)
                    .build(),
            )
        }

        type.addProperty(PropertySpec.builder("value", String::class).initializer("value").build())
        type.addFunction(
            FunSpec
                .builder("toString")
                .addModifiers(KModifier.OVERRIDE)
                .returns(String::class)
                .addStatement("return value")
                .build(),
        )
        type.addType(
            TypeSpec
                .companionObjectBuilder()
                .addProperty(
                    PropertySpec
                        .builder("mapping", createMapOfStringToNonNullType(enumType))
                        .initializer("entries.associateBy(%T::value)", enumType)
                        .addModifiers(KModifier.PRIVATE)
                        .build(),
                ).addFunction(
                    FunSpec
                        .builder("fromValue")
                        .addParameter("value", String::class)
                        .returns(enumType.copy(nullable = true))
                        .addStatement("return mapping[value]")
                        .build(),
                ).build(),
        )
        return type.build()
    }

    private fun GeneratorModelDescriptor.resolvedType(): OasType? = (classification as? GeneratorSchemaTypeClassification.Resolved)?.type
}
