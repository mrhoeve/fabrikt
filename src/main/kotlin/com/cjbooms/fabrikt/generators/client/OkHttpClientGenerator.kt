package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.cli.ClientCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.GeneratorEndpointContext
import com.cjbooms.fabrikt.model.Clients
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.SourceApi
import java.nio.file.Path

class OkHttpClientGenerator private constructor(
    private val simpleClientGenerator: OkHttpSimpleClientGenerator,
    private val enhancedClientGenerator: OkHttpEnhancedClientGenerator,
) : ClientGenerator {
    constructor(packages: Packages, api: SourceApi, srcPath: Path) : this(
        OkHttpSimpleClientGenerator(packages, api, srcPath),
        OkHttpEnhancedClientGenerator(packages, api, srcPath),
    )

    internal constructor(
        packages: Packages,
        api: SourceApi,
        srcPath: Path,
        context: GeneratorEndpointContext,
    ) : this(
        OkHttpSimpleClientGenerator(packages, api, srcPath, context),
        OkHttpEnhancedClientGenerator(packages, api, srcPath, context),
    )

    override fun generate(options: Set<ClientCodeGenOptionType>): Clients {
        val simpleClient = simpleClientGenerator.generateDynamicClientCode(options)
        val enhancedClient = enhancedClientGenerator.generateDynamicClientCode(options)

        return Clients(enhancedClient.plus(simpleClient).toSet())
    }

    override fun generateLibrary(options: Set<ClientCodeGenOptionType>): Collection<GeneratedFile> {
        val simpleClientLibrary = simpleClientGenerator.generateLibrary(options)
        val enhancedClientLibrary = enhancedClientGenerator.generateLibrary(options)

        return simpleClientLibrary.plus(enhancedClientLibrary)
    }
}
