package com.cjbooms.fabrikt.cli

internal fun SchemaGenerationMode.toParserMode(): com.cjbooms.fabrikt.parser.SchemaGenerationMode =
    when (this) {
        SchemaGenerationMode.LEGACY -> com.cjbooms.fabrikt.parser.SchemaGenerationMode.LEGACY
        SchemaGenerationMode.NATIVE -> com.cjbooms.fabrikt.parser.SchemaGenerationMode.NATIVE
    }
