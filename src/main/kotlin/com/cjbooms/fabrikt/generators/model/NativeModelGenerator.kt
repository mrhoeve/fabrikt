package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.generators.OasDefault
import com.cjbooms.fabrikt.generators.TypeFactory.createMapOfStringToNonNullType
import com.cjbooms.fabrikt.generators.ValidationAnnotations
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolution
import com.cjbooms.fabrikt.model.GeneratorModelDescriptor
import com.cjbooms.fabrikt.model.GeneratorPropertyDescriptor
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.ModelType
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.model.OasType
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.util.NormalisedString.toEnumName
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.fasterxml.jackson.databind.JsonNode
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal class NativeModelGenerator(
    private val basePackage: String,
) {
    private val serializationAnnotations = MutableSettings.effectiveSerializationAnnotations
    private val validationAnnotations = MutableSettings.validationLibrary.annotations

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
        description?.let { type.addKdoc("%L", it) }
        serializationAnnotations.addClassAnnotation(type)

        properties.forEach { property ->
            val resolvedType = property.kotlinType as? GeneratorKotlinTypeResolution.Resolved ?: return@forEach
            val nullable = resolvedType.nullable || (!property.required && property.defaultValue == null)
            val propertyName = property.name.toKotlinParameterName()
            val typeName = ModelGenerator.toModelType(basePackage, resolvedType.typeInfo, nullable)
            val parameter = ParameterSpec.builder(propertyName, typeName)
            if (!property.required) {
                property.defaultCode(resolvedType)?.let(parameter::defaultValue) ?: parameter.defaultValue("null")
            }
            constructor.addParameter(parameter.build())
            val generatedProperty =
                PropertySpec
                    .builder(propertyName, typeName)
                    .initializer(propertyName)
                    .apply { property.description?.let { addKdoc("%L", it) } }
            serializationAnnotations.addParameter(generatedProperty, property.name, property.required, resolvedType.typeInfo)
            serializationAnnotations.addProperty(generatedProperty, property.name, resolvedType.typeInfo)
            property.addValidationAnnotations(generatedProperty, resolvedType, nullable, validationAnnotations)
            type.addProperty(generatedProperty.build())
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
        description?.let { type.addKdoc("%L", it) }
        serializationAnnotations.addClassAnnotation(type)

        enum.entries.forEach { value ->
            val constant =
                TypeSpec
                    .anonymousClassBuilder()
                    .addSuperclassConstructorParameter("%S", value)
            serializationAnnotations.addEnumConstantAnnotation(constant, value)
            type.addEnumConstant(
                value.toEnumName(),
                constant.build(),
            )
        }

        val valueProperty = PropertySpec.builder("value", String::class).initializer("value")
        serializationAnnotations.addEnumPropertyAnnotation(valueProperty)
        type.addProperty(valueProperty.build())
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

    private fun GeneratorPropertyDescriptor.defaultCode(type: GeneratorKotlinTypeResolution.Resolved) =
        defaultValue
            ?.toDefaultValue()
            ?.let { OasDefault.from(type.typeInfo, ModelGenerator.toModelType(basePackage, type.typeInfo), it) }
            ?.getDefault()

    private fun JsonNode.toDefaultValue(): Any? =
        when {
            isTextual -> textValue()
            isIntegralNumber -> longValue()
            isFloatingPointNumber -> decimalValue()
            isBoolean -> booleanValue()
            else -> null
        }

    private fun GeneratorPropertyDescriptor.addValidationAnnotations(
        property: PropertySpec.Builder,
        type: GeneratorKotlinTypeResolution.Resolved,
        nullable: Boolean,
        annotations: ValidationAnnotations,
    ) {
        if (!nullable) annotations.nonNullAnnotation?.let(property::addAnnotation)
        val restrictions = constraints ?: return
        restrictions.pattern?.let { annotations.regexPattern(it)?.let(property::addAnnotation) }
        if (restrictions.minLength != null || restrictions.maxLength != null) {
            annotations.lengthRestriction(restrictions.minLength, restrictions.maxLength)?.let(property::addAnnotation)
        }
        restrictions.minimum?.let { annotations.minRestriction(it.value, it.exclusive)?.let(property::addAnnotation) }
        restrictions.maximum?.let { annotations.maxRestriction(it.value, it.exclusive)?.let(property::addAnnotation) }
        if (restrictions.minItems != null || restrictions.maxItems != null) {
            annotations.size(restrictions.minItems, restrictions.maxItems)?.let(property::addAnnotation)
        }
        if (type.typeInfo.isComplexType) annotations.fieldValid()?.let(property::addAnnotation)
    }
}
