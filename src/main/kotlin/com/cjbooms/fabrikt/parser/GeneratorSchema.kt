package com.cjbooms.fabrikt.parser

internal interface GeneratorSchema {
    val location: String
    val identity: GeneratorSchemaIdentity
}

internal class GeneratorSchemaIdentity

internal interface GeneratorBooleanSchema : GeneratorSchema {
    val allowsAnyValue: Boolean
}

internal interface GeneratorObjectSchema : GeneratorSchema {
    val identifier: String?
    val anchor: String?
    val types: Set<SourceSchemaType>
    val reference: String?
    val metadata: SourceSchemaMetadata
    val constraints: SourceSchemaConstraints
    val requiredProperties: Set<String>
    val dependentRequired: Map<String, Set<String>>
    val discriminator: SourceSchemaDiscriminator?
    val definitions: Map<String, GeneratorSchema>
    val properties: Map<String, GeneratorSchema>
    val patternProperties: Map<String, GeneratorSchema>
    val dependentSchemas: Map<String, GeneratorSchema>
    val prefixItems: List<GeneratorSchema>
    val items: GeneratorSchema?
    val contains: GeneratorSchema?
    val propertyNames: GeneratorSchema?
    val ifSchema: GeneratorSchema?
    val thenSchema: GeneratorSchema?
    val elseSchema: GeneratorSchema?
    val allOf: List<GeneratorSchema>
    val anyOf: List<GeneratorSchema>
    val oneOf: List<GeneratorSchema>
    val not: GeneratorSchema?
    val additionalProperties: GeneratorSchema?
    val unevaluatedItems: GeneratorSchema?
    val unevaluatedProperties: GeneratorSchema?
    val contentSchema: GeneratorSchema?
}
