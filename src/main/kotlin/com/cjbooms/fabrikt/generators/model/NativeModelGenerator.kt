package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.SerializationLibrary
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
import com.cjbooms.fabrikt.parser.GeneratorSchemaIdentity
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.util.NormalisedString.toEnumName
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.fasterxml.jackson.databind.JsonNode
import com.squareup.kotlinpoet.AnnotationSpec
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
        val objectModelIdentities =
            descriptors
                .filter { descriptor -> descriptor.resolvedType() == OasType.Object }
                .mapTo(mutableSetOf(), GeneratorModelDescriptor::schemaIdentity)
        val membershipsByMember =
            descriptors
                .filter { descriptor -> descriptor.resolvedType() == OasType.Object }
                .flatMap { descriptor ->
                    descriptor.oneOfMembers.map { member ->
                        MemberDirection(member.schemaIdentity, descriptor.direction) to
                            UnionMembership(
                                interfaceType = modelType(descriptor.name),
                                discriminatorProperty = descriptor.discriminator?.propertyName,
                                discriminatorMapping = descriptor.discriminatorMapping(member),
                                usesCustomSerialization =
                                    descriptor.discriminator == null && descriptor.oneOfMembers.any { it.scalarVariant() != null },
                            )
                    }
                }.groupBy({ it.first }, { it.second })
        return Models(
            descriptors.mapNotNull { descriptor ->
                val typeSpec =
                    when {
                        descriptor.scalarUnionVariants.isNotEmpty() -> descriptor.toScalarUnion()
                        descriptor.resolvedType() == null -> null
                        descriptor.oneOfMembers.isNotEmpty() ->
                            descriptor.toUnionInterface(objectModelIdentities)
                        descriptor.resolvedType() == OasType.Object ->
                            descriptor.toDataClass(
                                membershipsByMember[MemberDirection(descriptor.schemaIdentity, descriptor.direction)].orEmpty(),
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
        val type = TypeSpec.interfaceBuilder(name).addModifiers(KModifier.SEALED).addDeprecation(deprecated, DEPRECATED_SCHEMA_MESSAGE)
        description?.let { type.addKdoc("%L", it) }
        scalarUnionVariants.forEach { variant -> type.addType(variant.toScalarUnionVariant(unionType)) }
        NativeScalarUnionSerialization.apply(type, unionType, scalarUnionVariants, MutableSettings.serializationLibrary)
        return type.build()
    }

    private fun GeneratorScalarUnionVariantDescriptor.toScalarUnionVariant(unionType: TypeName): TypeSpec =
        scalarUnionVariant(name, kotlinType, unionType)

    private fun scalarUnionVariant(
        variantName: String,
        kotlinType: GeneratorKotlinTypeResolution.Resolved,
        unionType: TypeName,
    ): TypeSpec {
        val valueType = ModelGenerator.toModelType(basePackage, kotlinType.typeInfo)
        val constructor = FunSpec.constructorBuilder().addParameter("value", valueType).build()
        return TypeSpec
            .classBuilder(variantName)
            .addModifiers(KModifier.DATA)
            .addSuperinterface(unionType)
            .primaryConstructor(constructor)
            .addProperty(PropertySpec.builder("value", valueType).initializer("value").build())
            .build()
    }

    private fun GeneratorModelDescriptor.toDataClass(unionMemberships: List<UnionMembership>): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
        val type = TypeSpec.classBuilder(name).addDeprecation(deprecated, DEPRECATED_SCHEMA_MESSAGE)
        val usesKotlinxAdditionalProperties =
            additionalPropertiesType != null && MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION
        val declaredProperties = mutableListOf<NativeAdditionalPropertiesSerialization.DeclaredProperty>()
        var additionalPropertiesSerialization: Pair<String, TypeName>? = null
        description?.let { type.addKdoc("%L", it) }
        if (!usesKotlinxAdditionalProperties) serializationAnnotations.addClassAnnotation(type)
        if (unionMemberships.any(UnionMembership::usesCustomSerialization)) {
            NativeScalarUnionSerialization.addJacksonMemberOverrides(type, MutableSettings.serializationLibrary)
        }
        unionMemberships.map(UnionMembership::interfaceType).forEach(type::addSuperinterface)
        if (MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            val mappings = unionMemberships.mapNotNull(UnionMembership::discriminatorMapping).distinct()
            require(mappings.size <= 1) {
                "Kotlinx serialization cannot represent conflicting discriminator mappings for $name: ${mappings.joinToString()}."
            }
            mappings.singleOrNull()?.let { mapping -> serializationAnnotations.addSubtypeMappingAnnotation(type, mapping) }
        }
        val virtualDiscriminatorProperties =
            if (MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
                unionMemberships.mapNotNullTo(mutableSetOf(), UnionMembership::discriminatorProperty)
            } else {
                emptySet()
            }

        properties.filterNot { property -> property.name in virtualDiscriminatorProperties }.forEach { property ->
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
            declaredProperties +=
                NativeAdditionalPropertiesSerialization.DeclaredProperty(
                    sourceName = property.name,
                    generatedName = propertyName,
                    type = typeName,
                    required = required,
                    defaultValue = defaultCode,
                )
            constructor.addParameter(parameter.build())
            val generatedProperty =
                PropertySpec
                    .builder(propertyName, typeName)
                    .initializer(propertyName)
                    .addDeprecation(property.deprecated, DEPRECATED_PROPERTY_MESSAGE)
                    .apply { property.description?.let { addKdoc("%L", it) } }
            serializationAnnotations.addParameter(generatedProperty, property.name, required, resolvedType.typeInfo)
            serializationAnnotations.addProperty(generatedProperty, property.name, resolvedType.typeInfo)
            property.addValidationAnnotations(generatedProperty, resolvedType, nullable, validationAnnotations)
            type.addProperty(generatedProperty.build())
        }
        additionalPropertiesType?.let { additionalProperties ->
            if (!serializationAnnotations.supportsAdditionalProperties && !usesKotlinxAdditionalProperties) {
                throw UnsupportedOperationException("Additional properties not supported by selected serialization library")
            }
            val valueType = ModelGenerator.toModelType(basePackage, additionalProperties.typeInfo).maybeMakeMapValueNullable()
            val mapType = createMutableMapOfStringToType(ModelGenerator.toModelType(basePackage, additionalProperties.typeInfo))
            val propertyName = if (usesKotlinxAdditionalProperties) additionalPropertiesName() else "properties"
            constructor.addParameter(
                ParameterSpec
                    .builder(propertyName, mapType)
                    .defaultValue("mutableMapOf()")
                    .build(),
            )
            val additionalPropertiesProperty = PropertySpec.builder(propertyName, mapType).initializer(propertyName)
            serializationAnnotations.addIgnore(additionalPropertiesProperty)
            type.addProperty(additionalPropertiesProperty.build())

            val getter =
                FunSpec
                    .builder("get")
                    .returns(createMapOfStringToNonNullType(valueType))
                    .addStatement("return %N", propertyName)
            serializationAnnotations.addGetter(getter)
            type.addFunction(getter.build())

            val setter =
                FunSpec
                    .builder("set")
                    .addParameter("name", String::class)
                    .addParameter("value", valueType)
                    .addStatement("%N[name] = value", propertyName)
            serializationAnnotations.addSetter(setter)
            type.addFunction(setter.build())
            if (usesKotlinxAdditionalProperties) additionalPropertiesSerialization = propertyName to valueType
        }

        additionalPropertiesSerialization?.let { (propertyName, valueType) ->
            NativeAdditionalPropertiesSerialization.apply(
                type,
                modelType(name),
                declaredProperties,
                propertyName,
                valueType,
            )
        }

        if (constructor.parameters.isNotEmpty()) type.addModifiers(KModifier.DATA)
        return type.primaryConstructor(constructor.build()).build()
    }

    private fun GeneratorModelDescriptor.additionalPropertiesName(): String {
        val names = properties.mapTo(mutableSetOf()) { it.name.toKotlinParameterName() }
        return generateSequence("additionalProperties") { it + "Extra" }.first(names::add)
    }

    private fun GeneratorModelDescriptor.toUnionInterface(objectModelIdentities: Set<GeneratorSchemaIdentity>): TypeSpec {
        val members = oneOfMembers
        val unionType = modelType(name)
        val objectMembers = members.filter { member -> member.schemaIdentity in objectModelIdentities }
        val scalarVariants = if (discriminator == null) members.mapNotNull { member -> member.scalarVariant() } else emptyList()
        val type = TypeSpec.interfaceBuilder(name).addModifiers(KModifier.SEALED).addDeprecation(deprecated, DEPRECATED_SCHEMA_MESSAGE)
        description?.let { type.addKdoc("%L", it) }
        scalarVariants.forEach { variant -> type.addType(scalarUnionVariant(variant.name, variant.kotlinType, unionType)) }
        when {
            scalarVariants.isNotEmpty() ->
                NativeScalarUnionSerialization.apply(
                    type,
                    unionType,
                    scalarVariants,
                    MutableSettings.serializationLibrary,
                    objectMembers.map { member -> member.typeName() },
                )
            discriminator == null && MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION ->
                NativeObjectUnionSerialization.apply(type, unionType, objectMembers.map { member -> member.typeName() })
            else -> serializationAnnotations.addClassAnnotation(type)
        }
        if (scalarVariants.isEmpty() && discriminator != null) {
            serializationAnnotations.addBasePolymorphicTypeAnnotation(type, discriminator.propertyName)
            serializationAnnotations.addPolymorphicSubTypesAnnotation(type, discriminatorMappings(members))
        } else if (
            scalarVariants.isEmpty() &&
            discriminator == null &&
            MutableSettings.serializationLibrary != SerializationLibrary.KOTLINX_SERIALIZATION
        ) {
            serializationAnnotations.addPolymorphicSubTypeDeductionAnnotation(type, members.map { it.typeName() })
        }
        return type.build()
    }

    private fun GeneratorUnionMemberDescriptor.scalarVariant(): GeneratorScalarUnionVariantDescriptor? {
        val type = (classification as? GeneratorSchemaTypeClassification.Resolved)?.type ?: return null
        val name =
            when (type) {
                OasType.Text -> "StringValue"
                OasType.Boolean -> "BooleanValue"
                OasType.Integer, OasType.Int32 -> "IntegerValue"
                OasType.Int64 -> "LongValue"
                OasType.Number -> "NumberValue"
                OasType.Float -> "FloatValue"
                OasType.Double -> "DoubleValue"
                else -> return null
            }
        return GeneratorScalarUnionVariantDescriptor(name, type, kotlinType)
    }

    private fun GeneratorModelDescriptor.discriminatorMappings(members: List<GeneratorUnionMemberDescriptor>): Map<String, TypeName> {
        return members
            .mapNotNull { member ->
                val modelName = member.modelName() ?: return@mapNotNull null
                val key = discriminatorMapping(member) ?: modelName
                key to member.typeName()
            }.toMap()
    }

    private fun GeneratorModelDescriptor.discriminatorMapping(member: GeneratorUnionMemberDescriptor): String? {
        val discriminator = discriminator ?: return null
        return discriminator.mapping.entries
            .firstOrNull { (_, reference) -> member.matchesDiscriminatorReference(reference) }
            ?.key
            ?: member.modelName()
    }

    private fun GeneratorUnionMemberDescriptor.matchesDiscriminatorReference(reference: String): Boolean =
        canonicalReference == reference ||
            canonicalReference?.endsWith(reference.substringAfterLast('/')) == true ||
            modelName() == reference.substringAfterLast('/')

    private fun GeneratorModelDescriptor.toEnum(): TypeSpec {
        val enum = (kotlinType as GeneratorKotlinTypeResolution.Resolved).typeInfo as KotlinTypeInfo.Enum
        val enumType = ModelGenerator.generatedType(basePackage, enum.enumClassName)
        val type =
            TypeSpec
                .enumBuilder(enumType)
                .addDeprecation(deprecated, DEPRECATED_SCHEMA_MESSAGE)
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

    private fun GeneratorModelDescriptor.resolvedType(): OasType? =
        (classification as? GeneratorSchemaTypeClassification.Resolved)?.type
            ?: (kotlinType as? GeneratorKotlinTypeResolution.Resolved)
                ?.typeInfo
                ?.takeIf { it is KotlinTypeInfo.Object }
                ?.let { OasType.Object }

    private fun GeneratorUnionMemberDescriptor.typeName(): TypeName = ModelGenerator.toModelType(basePackage, kotlinType.typeInfo)

    private fun GeneratorUnionMemberDescriptor.modelName(): String? = kotlinType.typeInfo.generatedModelClassName

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
        val identity: GeneratorSchemaIdentity,
        val direction: GeneratorModelDirection,
    )

    private data class UnionMembership(
        val interfaceType: TypeName,
        val discriminatorProperty: String?,
        val discriminatorMapping: String?,
        val usesCustomSerialization: Boolean,
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
        if (type.typeInfo is KotlinTypeInfo.Map && (restrictions.minProperties != null || restrictions.maxProperties != null)) {
            annotations.lengthRestriction(restrictions.minProperties, restrictions.maxProperties)?.let(property::addAnnotation)
        }
        val validatesNestedValues =
            when (val typeInfo = type.typeInfo) {
                is KotlinTypeInfo.Array -> typeInfo.parameterizedType.isComplexType
                is KotlinTypeInfo.Map -> typeInfo.parameterizedType.isComplexType
                else -> typeInfo.isComplexType
            }
        if (validatesNestedValues) annotations.fieldValid()?.let(property::addAnnotation)
    }

    private fun TypeSpec.Builder.addDeprecation(
        deprecated: Boolean,
        message: String,
    ): TypeSpec.Builder = apply { if (deprecated) addAnnotation(deprecationAnnotation(message)) }

    private fun PropertySpec.Builder.addDeprecation(
        deprecated: Boolean,
        message: String,
    ): PropertySpec.Builder = apply { if (deprecated) addAnnotation(deprecationAnnotation(message)) }

    private fun deprecationAnnotation(message: String): AnnotationSpec =
        AnnotationSpec
            .builder(Deprecated::class)
            .addMember("message = %S", message)
            .build()

    private companion object {
        const val DEPRECATED_SCHEMA_MESSAGE = "This API schema is deprecated."
        const val DEPRECATED_PROPERTY_MESSAGE = "This API property is deprecated."
    }
}
