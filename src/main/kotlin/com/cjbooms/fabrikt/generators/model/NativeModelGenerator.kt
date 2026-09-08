package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.generators.OasDefault
import com.cjbooms.fabrikt.generators.TypeFactory.createMapOfStringToNonNullType
import com.cjbooms.fabrikt.generators.TypeFactory.createMutableMapOfStringToType
import com.cjbooms.fabrikt.generators.TypeFactory.maybeMakeMapValueNullable
import com.cjbooms.fabrikt.generators.ValidationAnnotations
import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolution
import com.cjbooms.fabrikt.model.GeneratorModelDescriptor
import com.cjbooms.fabrikt.model.GeneratorModelDirection
import com.cjbooms.fabrikt.model.GeneratorPropertyDescriptor
import com.cjbooms.fabrikt.model.GeneratorScalarUnionVariantDescriptor
import com.cjbooms.fabrikt.model.GeneratorUnionMemberDescriptor
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.ModelType
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.model.OasType
import com.cjbooms.fabrikt.model.asResolvedFallback
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.util.NormalisedString.toEnumName
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.fasterxml.jackson.databind.JsonNode
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

internal class NativeModelGenerator(
    private val basePackage: String,
) {
    private val serializationAnnotations = MutableSettings.effectiveSerializationAnnotations
    private val validationAnnotations = MutableSettings.validationLibrary.annotations

    fun generate(descriptors: Collection<GeneratorModelDescriptor>): Models {
        val interfacesByMember =
            descriptors
                .filter { descriptor -> descriptor.resolvedType() == OasType.Object }
                .flatMap { descriptor ->
                    descriptor.oneOfMembers.map { member ->
                        MemberDirection(member.schemaIdentity, descriptor.direction) to modelType(descriptor.name)
                    }
                }.groupBy({ it.first }, { it.second })
        return Models(
            descriptors.mapNotNull { descriptor ->
                val typeSpec =
                    when {
                        descriptor.scalarUnionVariants.isNotEmpty() -> descriptor.toScalarUnion()
                        descriptor.resolvedType() == null -> null
                        descriptor.oneOfMembers.isNotEmpty() -> descriptor.toUnionInterface()
                        descriptor.resolvedType() == OasType.Object ->
                            descriptor.toDataClass(
                                interfacesByMember[MemberDirection(descriptor.schemaIdentity, descriptor.direction)].orEmpty(),
                            )
                        descriptor.resolvedType() == OasType.Enum -> descriptor.toEnum()
                        else -> null
                    }
                typeSpec?.let { ModelType(it, basePackage) }
            },
        )
    }

    private fun GeneratorModelDescriptor.toScalarUnion(): TypeSpec {
        val unionType = modelType(name)
        val type = TypeSpec.interfaceBuilder(name).addModifiers(KModifier.SEALED)
        description?.let { type.addKdoc("%L", it) }
        scalarUnionVariants.forEach { variant -> type.addType(variant.toScalarUnionVariant(unionType)) }
        NativeScalarUnionSerialization.apply(type, unionType, scalarUnionVariants, MutableSettings.serializationLibrary)
        return type.build()
    }

    private fun GeneratorScalarUnionVariantDescriptor.toScalarUnionVariant(unionType: TypeName): TypeSpec {
        val valueType = ModelGenerator.toModelType(basePackage, kotlinType.typeInfo)
        val constructor = FunSpec.constructorBuilder().addParameter("value", valueType).build()
        return TypeSpec
            .classBuilder(name)
            .addModifiers(KModifier.DATA)
            .addSuperinterface(unionType)
            .primaryConstructor(constructor)
            .addProperty(PropertySpec.builder("value", valueType).initializer("value").build())
            .build()
    }

    private fun GeneratorModelDescriptor.toDataClass(superInterfaces: List<TypeName>): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
        val type = TypeSpec.classBuilder(name)
        description?.let { type.addKdoc("%L", it) }
        serializationAnnotations.addClassAnnotation(type)
        superInterfaces.forEach(type::addSuperinterface)

        properties.forEach { property ->
            val resolvedType = property.kotlinType.asResolvedFallback() ?: return@forEach
            val defaultCode = property.defaultCode(resolvedType)
            val required = property.requiredInCombinedModel
            val nullable = resolvedType.nullable || (!required && defaultCode == null)
            val propertyName = property.name.toKotlinParameterName()
            val typeName = ModelGenerator.toModelType(basePackage, resolvedType.typeInfo, nullable)
            val parameter = ParameterSpec.builder(propertyName, typeName)
            if (!required) {
                defaultCode?.let(parameter::defaultValue) ?: parameter.defaultValue("null")
            }
            constructor.addParameter(parameter.build())
            val generatedProperty =
                PropertySpec
                    .builder(propertyName, typeName)
                    .initializer(propertyName)
                    .apply { property.description?.let { addKdoc("%L", it) } }
            serializationAnnotations.addParameter(generatedProperty, property.name, required, resolvedType.typeInfo)
            serializationAnnotations.addProperty(generatedProperty, property.name, resolvedType.typeInfo)
            property.addValidationAnnotations(generatedProperty, resolvedType, nullable, validationAnnotations)
            type.addProperty(generatedProperty.build())
        }
        additionalPropertiesType?.let { additionalProperties ->
            if (!serializationAnnotations.supportsAdditionalProperties) {
                throw UnsupportedOperationException("Additional properties not supported by selected serialization library")
            }
            val valueType = ModelGenerator.toModelType(basePackage, additionalProperties.typeInfo).maybeMakeMapValueNullable()
            val mapType = createMutableMapOfStringToType(ModelGenerator.toModelType(basePackage, additionalProperties.typeInfo))
            constructor.addParameter(
                ParameterSpec
                    .builder("properties", mapType)
                    .defaultValue("mutableMapOf()")
                    .build(),
            )
            val additionalPropertiesProperty = PropertySpec.builder("properties", mapType).initializer("properties")
            serializationAnnotations.addIgnore(additionalPropertiesProperty)
            type.addProperty(additionalPropertiesProperty.build())

            val getter =
                FunSpec
                    .builder("get")
                    .returns(createMapOfStringToNonNullType(valueType))
                    .addStatement("return properties")
            serializationAnnotations.addGetter(getter)
            type.addFunction(getter.build())

            val setter =
                FunSpec
                    .builder("set")
                    .addParameter("name", String::class)
                    .addParameter("value", valueType)
                    .addStatement("properties[name] = value")
            serializationAnnotations.addSetter(setter)
            type.addFunction(setter.build())
        }

        if (constructor.parameters.isNotEmpty()) type.addModifiers(KModifier.DATA)
        return type.primaryConstructor(constructor.build()).build()
    }

    private fun GeneratorModelDescriptor.toUnionInterface(): TypeSpec {
        val members = oneOfMembers
        val type = TypeSpec.interfaceBuilder(name).addModifiers(KModifier.SEALED)
        description?.let { type.addKdoc("%L", it) }
        serializationAnnotations.addClassAnnotation(type)
        if (discriminator != null) {
            serializationAnnotations.addBasePolymorphicTypeAnnotation(type, discriminator.propertyName)
            serializationAnnotations.addPolymorphicSubTypesAnnotation(type, discriminatorMappings(members))
        } else {
            serializationAnnotations.addPolymorphicSubTypeDeductionAnnotation(type, members.map { it.typeName() })
        }
        return type.build()
    }

    private fun GeneratorModelDescriptor.discriminatorMappings(members: List<GeneratorUnionMemberDescriptor>): Map<String, TypeName> {
        val mappings = discriminator?.mapping.orEmpty()
        return if (mappings.isEmpty()) {
            members.associate { it.modelName() to it.typeName() }
        } else {
            mappings
                .mapNotNull { (key, reference) ->
                    members
                        .firstOrNull { member ->
                            member.canonicalReference == reference ||
                                member.canonicalReference?.endsWith(reference.substringAfterLast('/')) == true ||
                                member.modelName() == reference.substringAfterLast('/')
                        }?.let { key to it.typeName() }
                }.toMap()
        }
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

    private fun GeneratorUnionMemberDescriptor.typeName(): TypeName = ModelGenerator.toModelType(basePackage, kotlinType.typeInfo)

    private fun GeneratorUnionMemberDescriptor.modelName(): String = requireNotNull(kotlinType.typeInfo.generatedModelClassName)

    private fun modelType(name: String): ClassName = ModelGenerator.generatedType(basePackage, name)

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

    private data class MemberDirection(
        val identity: com.cjbooms.fabrikt.parser.GeneratorSchemaIdentity,
        val direction: GeneratorModelDirection,
    )

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
            annotations.lengthRestriction(restrictions.minItems, restrictions.maxItems)?.let(property::addAnnotation)
        }
        val validatesNestedValues =
            when (val typeInfo = type.typeInfo) {
                is KotlinTypeInfo.Array -> typeInfo.parameterizedType.isComplexType
                is KotlinTypeInfo.Map -> typeInfo.parameterizedType.isComplexType
                else -> typeInfo.isComplexType
            }
        if (validatesNestedValues) annotations.fieldValid()?.let(property::addAnnotation)
    }
}
