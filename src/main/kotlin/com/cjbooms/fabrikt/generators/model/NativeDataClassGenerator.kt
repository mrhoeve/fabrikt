package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.model.GeneratorKotlinTypeResolution
import com.cjbooms.fabrikt.model.GeneratorModelDescriptor
import com.cjbooms.fabrikt.model.OasType
import com.cjbooms.fabrikt.model.ModelType
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.util.NormalisedString.toKotlinParameterName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal class NativeDataClassGenerator(
    private val basePackage: String,
) {
    fun generate(descriptors: Collection<GeneratorModelDescriptor>): Models =
        Models(
            descriptors.mapNotNull { descriptor ->
                val classification = descriptor.classification as? GeneratorSchemaTypeClassification.Resolved
                if (classification?.type != OasType.Object) return@mapNotNull null
                ModelType(descriptor.toTypeSpec(), basePackage)
            },
        )

    private fun GeneratorModelDescriptor.toTypeSpec(): TypeSpec {
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
}
