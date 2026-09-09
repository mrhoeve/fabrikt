package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.cli.CodeGenTypeOverride
import com.cjbooms.fabrikt.cli.InstantLibrary
import com.cjbooms.fabrikt.cli.SerializationLibrary
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.parser.GeneratorArrayItems
import com.cjbooms.fabrikt.parser.GeneratorBooleanSchema
import com.cjbooms.fabrikt.parser.GeneratorObjectSchema
import com.cjbooms.fabrikt.parser.GeneratorReferenceSiblingSchema
import com.cjbooms.fabrikt.parser.GeneratorSchema
import com.cjbooms.fabrikt.parser.GeneratorSchemaDocument
import com.cjbooms.fabrikt.parser.GeneratorSchemaIdentity
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassification
import com.cjbooms.fabrikt.parser.GeneratorSchemaTypeClassifier
import com.cjbooms.fabrikt.parser.GeneratorSchemaValueConstraint
import com.cjbooms.fabrikt.parser.SourceSchemaType
import com.cjbooms.fabrikt.parser.arrayItems
import com.cjbooms.fabrikt.parser.valueConstraint
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.fasterxml.jackson.databind.JsonNode

internal sealed interface GeneratorKotlinTypeResolution {
    data class Resolved(
        val typeInfo: KotlinTypeInfo,
        val nullable: Boolean,
    ) : GeneratorKotlinTypeResolution

    data class Unsupported(
        val reason: GeneratorSchemaTypeClassification.Reason,
    ) : GeneratorKotlinTypeResolution

    data class Fallback(
        val typeInfo: KotlinTypeInfo,
        val nullable: Boolean,
        val classification: GeneratorSchemaTypeClassification.Fallback,
    ) : GeneratorKotlinTypeResolution
}

internal class GeneratorKotlinTypeResolver(
    private val document: GeneratorSchemaDocument,
    registeredModelNames: Map<GeneratorSchemaIdentity, String> = emptyMap(),
) {
    private val componentNames =
        buildMap {
            putAll(registeredModelNames)
            document.componentSchemas.forEach { (name, schema) ->
                putIfAbsent(document.resolve(schema).identity, name)
            }
        }

    fun resolve(schema: GeneratorSchema): GeneratorKotlinTypeResolution {
        val resolvedSchema = document.resolve(schema)
        val classification = classify(resolvedSchema)
        val objectSchema = resolvedSchema as? GeneratorObjectSchema
        return when (classification) {
            is GeneratorSchemaTypeClassification.Unsupported ->
                GeneratorKotlinTypeResolution.Unsupported(classification.reason)
            is GeneratorSchemaTypeClassification.Fallback ->
                if (classification.supportsGeneratedScalarUnion()) {
                    GeneratorKotlinTypeResolution.Resolved(
                        KotlinTypeInfo.Object(modelName(resolvedSchema)),
                        classification.nullable,
                    )
                } else {
                    GeneratorKotlinTypeResolution.Fallback(unionFallback(), classification.nullable, classification)
                }
            is GeneratorSchemaTypeClassification.Resolved ->
                GeneratorKotlinTypeResolution.Resolved(
                    typeInfo = resolveTypeInfo(classification.type, objectSchema, resolvedSchema),
                    nullable = classification.nullable,
                )
        }
    }

    private fun resolveTypeInfo(
        type: OasType,
        objectSchema: GeneratorObjectSchema?,
        resolvedSchema: GeneratorSchema,
    ): KotlinTypeInfo =
        when (type) {
            OasType.Date -> dateType()
            OasType.DateTime -> dateTimeType()
            OasType.Text -> KotlinTypeInfo.Text
            OasType.Enum ->
                KotlinTypeInfo.Enum(
                    objectSchema?.enumEntries().orEmpty(),
                    modelName(resolvedSchema),
                )
            OasType.Uuid -> overridable(CodeGenTypeOverride.UUID_AS_STRING, KotlinTypeInfo.Uuid)
            OasType.Uri -> overridable(CodeGenTypeOverride.URI_AS_STRING, KotlinTypeInfo.Uri)
            OasType.Base64String -> overridable(CodeGenTypeOverride.BYTE_AS_STRING, KotlinTypeInfo.ByteArray)
            OasType.Binary -> overridable(CodeGenTypeOverride.BINARY_AS_STRING, KotlinTypeInfo.ByteArray)
            OasType.Double -> KotlinTypeInfo.Double
            OasType.Float -> KotlinTypeInfo.Float
            OasType.Number -> KotlinTypeInfo.Numeric
            OasType.Int32, OasType.Integer -> KotlinTypeInfo.Integer
            OasType.Int64 -> KotlinTypeInfo.BigInt
            OasType.Boolean -> KotlinTypeInfo.Boolean
            OasType.Array, OasType.Set -> resolveArray(requireNotNull(objectSchema), type == OasType.Set)
            OasType.Map -> resolveMap(requireNotNull(objectSchema))
            OasType.Object -> KotlinTypeInfo.Object(modelName(resolvedSchema))
            OasType.UntypedObject -> KotlinTypeInfo.UntypedObject
            OasType.Any -> anyType()
            else -> anyType()
        }

    fun classify(schema: GeneratorSchema): GeneratorSchemaTypeClassification =
        GeneratorSchemaTypeClassifier.classify(document.resolve(schema), document::resolve)

    fun resolveUnionVariant(
        schema: GeneratorSchema,
        type: OasType,
    ): GeneratorKotlinTypeResolution.Resolved {
        val resolvedSchema = document.resolve(schema)
        return GeneratorKotlinTypeResolution.Resolved(
            resolveTypeInfo(type, resolvedSchema as? GeneratorObjectSchema, resolvedSchema),
            nullable = false,
        )
    }

    fun resolveProperty(
        schema: GeneratorSchema,
        enclosingModelName: String,
    ): GeneratorKotlinTypeResolution {
        val resolvedSchema = document.resolve(schema)
        val objectSchema = resolvedSchema as? GeneratorObjectSchema ?: return resolvePropertyFallback(schema)
        val reference = (schema as? GeneratorObjectSchema)?.reference ?: return resolvePropertyFallback(schema)
        val classification =
            classify(objectSchema) as? GeneratorSchemaTypeClassification.Resolved
                ?: return resolvePropertyFallback(schema)
        if (classification.type != OasType.Array && classification.type != OasType.Set) return resolvePropertyFallback(schema)

        val itemSchema = objectSchema.items ?: return resolvePropertyFallback(schema)
        val itemType = resolve(itemSchema) as? GeneratorKotlinTypeResolution.Resolved ?: return resolvePropertyFallback(schema)
        val itemEnum = itemType.typeInfo as? KotlinTypeInfo.Enum ?: return resolvePropertyFallback(schema)
        val referencedName = reference.substringAfterLast('/').toModelClassName()
        return GeneratorKotlinTypeResolution.Resolved(
            KotlinTypeInfo.Array(
                parameterizedType = itemEnum.copy(enumClassName = enclosingModelName + referencedName),
                isParameterizedTypeNullable = itemType.nullable,
                hasUniqueItems = classification.type == OasType.Set,
            ),
            classification.nullable,
        )
    }

    private fun resolvePropertyFallback(schema: GeneratorSchema): GeneratorKotlinTypeResolution =
        when (val resolution = resolve(schema)) {
            is GeneratorKotlinTypeResolution.Resolved -> resolution
            is GeneratorKotlinTypeResolution.Fallback -> resolution
            is GeneratorKotlinTypeResolution.Unsupported -> {
                val nullable =
                    (document.resolve(schema) as? GeneratorObjectSchema)?.types?.contains(SourceSchemaType.NULL) == true
                GeneratorKotlinTypeResolution.Resolved(anyType(), nullable)
            }
        }

    private fun resolveArray(
        schema: GeneratorObjectSchema,
        unique: Boolean,
    ): KotlinTypeInfo {
        val resolvedItems =
            when (val items = schema.arrayItems()) {
                GeneratorArrayItems.Unconstrained -> GeneratorKotlinTypeResolution.Resolved(unionFallback(), nullable = false)
                is GeneratorArrayItems.Homogeneous ->
                    resolveArrayItem(items.items) ?: GeneratorKotlinTypeResolution.Resolved(unionFallback(), nullable = true)
                is GeneratorArrayItems.Tuple -> resolveTupleItems(items)
            }
        return KotlinTypeInfo.Array(
            parameterizedType = resolvedItems.typeInfo,
            isParameterizedTypeNullable = resolvedItems.nullable,
            hasUniqueItems = unique,
        )
    }

    private fun resolveTupleItems(items: GeneratorArrayItems.Tuple): GeneratorKotlinTypeResolution.Resolved {
        val candidates = items.prefixItems.mapNotNull(::resolveArrayItem).toMutableList()
        when (val additionalItems = items.additionalItems) {
            null -> candidates.add(GeneratorKotlinTypeResolution.Resolved(unionFallback(), nullable = true))
            else -> resolveArrayItem(additionalItems)?.let(candidates::add)
        }
        if (candidates.isEmpty()) return GeneratorKotlinTypeResolution.Resolved(unionFallback(), nullable = true)
        val types = candidates.map { it.typeInfo }.distinct()
        return GeneratorKotlinTypeResolution.Resolved(
            typeInfo = types.singleOrNull() ?: unionFallback(),
            nullable = candidates.any { it.nullable },
        )
    }

    private fun resolveArrayItem(schema: GeneratorSchema): GeneratorKotlinTypeResolution.Resolved? =
        when (val resolvedSchema = document.resolve(schema)) {
            is GeneratorBooleanSchema ->
                resolvedSchema
                    .takeIf { it.allowsAnyValue }
                    ?.let { GeneratorKotlinTypeResolution.Resolved(unionFallback(), nullable = true) }
            else -> resolve(resolvedSchema).asResolvedFallback()
        }

    private fun resolveMap(schema: GeneratorObjectSchema): KotlinTypeInfo {
        val valueSchema = schema.additionalProperties
        val valueType = valueSchema?.let(::resolve)?.asResolvedFallback()
        return KotlinTypeInfo.Map(valueType?.typeInfo ?: anyType())
    }

    private fun modelName(schema: GeneratorSchema): String =
        (
            componentNames[schema.identity] ?: (schema as? GeneratorReferenceSiblingSchema)
                ?.takeUnless { it.changesGeneratedShape }
                ?.referencedSchema
                ?.let(::modelNameWithoutSuffix) ?: schema.location
                .substringAfterLast('/')
                .replace("~1", "-")
                .replace("~0", "~")
        ).toModelClassName() + MutableSettings.modelSuffix

    private fun modelNameWithoutSuffix(schema: GeneratorSchema): String =
        componentNames[document.resolve(schema).identity] ?: schema.location
            .substringAfterLast('/')
            .replace("~1", "-")
            .replace("~0", "~")

    private fun GeneratorObjectSchema.enumEntries(): List<String> {
        val values =
            if (this is GeneratorReferenceSiblingSchema) {
                (valueConstraint() as? GeneratorSchemaValueConstraint.Allowed)?.values.orEmpty()
            } else if (metadata.constValue == null && SourceSchemaType.STRING in types) {
                metadata.enumValues
            } else {
                (valueConstraint() as? GeneratorSchemaValueConstraint.Allowed)?.values.orEmpty()
            }
        return values.filterNot(JsonNode::isNull).map(JsonNode::asText)
    }

    private fun dateType(): KotlinTypeInfo =
        when {
            CodeGenTypeOverride.DATE_AS_STRING in MutableSettings.typeOverrides -> KotlinTypeInfo.Text
            MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION -> KotlinTypeInfo.KotlinxLocalDate
            else -> KotlinTypeInfo.Date
        }

    private fun dateTimeType(): KotlinTypeInfo =
        when {
            CodeGenTypeOverride.DATETIME_AS_STRING in MutableSettings.typeOverrides -> KotlinTypeInfo.Text
            CodeGenTypeOverride.DATETIME_AS_INSTANT in MutableSettings.typeOverrides -> KotlinTypeInfo.Instant
            CodeGenTypeOverride.DATETIME_AS_LOCALDATETIME in MutableSettings.typeOverrides -> KotlinTypeInfo.LocalDateTime
            MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION &&
                MutableSettings.instantLibrary == InstantLibrary.KOTLINX_INSTANT -> KotlinTypeInfo.KotlinxInstant
            MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION -> KotlinTypeInfo.KotlinInstant
            else -> KotlinTypeInfo.DateTime
        }

    private fun overridable(
        override: CodeGenTypeOverride,
        default: KotlinTypeInfo,
    ): KotlinTypeInfo = if (override in MutableSettings.typeOverrides) KotlinTypeInfo.Text else default

    private fun anyType(): KotlinTypeInfo =
        if (MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            KotlinTypeInfo.JsonElement
        } else {
            KotlinTypeInfo.AnyType
        }

    private fun unionFallback(): KotlinTypeInfo =
        if (MutableSettings.serializationLibrary == SerializationLibrary.KOTLINX_SERIALIZATION) {
            KotlinTypeInfo.JsonElement
        } else {
            KotlinTypeInfo.AnyType
        }
}

internal fun GeneratorSchemaTypeClassification.Fallback.supportsGeneratedScalarUnion(): Boolean =
    this !is GeneratorSchemaTypeClassification.ValueUnion &&
        types.size > 1 &&
        types.all(SCALAR_UNION_TYPES::contains) &&
        types.map(OasType::scalarUnionTokenKind).distinct().size == types.size &&
        !(this is GeneratorSchemaTypeClassification.CompositionUnion && types.hasOverlappingNumericTypes())

private fun Set<OasType>.hasOverlappingNumericTypes(): Boolean = any(OasType::isIntegerScalar) && any(OasType::isNumberScalar)

private fun OasType.isIntegerScalar(): Boolean = this == OasType.Integer || this == OasType.Int32 || this == OasType.Int64

private fun OasType.isNumberScalar(): Boolean = this == OasType.Number || this == OasType.Float || this == OasType.Double

private fun OasType.scalarUnionTokenKind(): ScalarUnionTokenKind =
    when (this) {
        OasType.Text -> ScalarUnionTokenKind.STRING
        OasType.Boolean -> ScalarUnionTokenKind.BOOLEAN
        OasType.Integer, OasType.Int32, OasType.Int64 -> ScalarUnionTokenKind.INTEGER
        OasType.Number, OasType.Float, OasType.Double -> ScalarUnionTokenKind.NUMBER
        else -> error("Unsupported scalar union type: $this")
    }

private enum class ScalarUnionTokenKind {
    STRING,
    BOOLEAN,
    INTEGER,
    NUMBER,
}

private val SCALAR_UNION_TYPES =
    setOf(
        OasType.Text,
        OasType.Boolean,
        OasType.Integer,
        OasType.Int32,
        OasType.Int64,
        OasType.Number,
        OasType.Float,
        OasType.Double,
    )

internal fun GeneratorKotlinTypeResolution.asResolvedFallback(): GeneratorKotlinTypeResolution.Resolved? =
    when (this) {
        is GeneratorKotlinTypeResolution.Resolved -> this
        is GeneratorKotlinTypeResolution.Fallback -> GeneratorKotlinTypeResolution.Resolved(typeInfo, nullable)
        is GeneratorKotlinTypeResolution.Unsupported -> null
    }
