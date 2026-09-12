package com.cjbooms.fabrikt.generators.client

import com.cjbooms.fabrikt.cli.ClientCodeGenTargetType
import com.cjbooms.fabrikt.cli.CodeGenerationType
import com.cjbooms.fabrikt.cli.CodeGenerator
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.MutableSettings
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.parser.SchemaGenerationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Paths

class ClientServerGeneratorTest {
    @ParameterizedTest
    @EnumSource(ClientCodeGenTargetType::class)
    fun `generates native server catalogs for every client target`(target: ClientCodeGenTargetType) {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = target,
        )

        val generated = generate(SchemaGenerationMode.NATIVE)
        val catalog =
            generated
                .filterIsInstance<KotlinSourceSet>()
                .flatMap(KotlinSourceSet::files)
                .single { it.name == "ApiServers" }
                .toString()

        assertThat(catalog)
            .contains("public data class ApiServerVariable(")
            .contains("public data class ApiServer(")
            .contains("public fun resolve(overrides: Map<String, String> = emptyMap()): String")
            .contains("Unknown server variables")
            .contains("Invalid value '${'$'}value' for server variable '${'$'}variableName'")
            .contains("public object ApiServers")
            .contains("public val defaultServer: ApiServer? = all[0]")
            .contains("urlTemplate = \"https://{region}.example.com\"")
            .contains("defaultValue = \"eu\"")
            .contains("allowedValues = setOf(\"eu\", \"us\")")
            .contains("urlTemplate = \"https://path.example.com\"")
            .contains("urlTemplate = \"https://write.example.com\"")
            .contains("urlTemplate = \"https://callback.example.com\"")
            .contains("\"readItems\" to listOf(all[1])")
            .contains("\"POST /items\" to listOf(all[2])")
            .contains("\"notifyItem\" to listOf(all[3])")
    }

    @Test
    fun `does not add server catalogs to legacy clients`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val generatedNames =
            generate(SchemaGenerationMode.LEGACY)
                .filterIsInstance<KotlinSourceSet>()
                .flatMap(KotlinSourceSet::files)
                .map { it.name }

        assertThat(generatedNames).doesNotContain("ApiServers")
    }

    @Test
    fun `does not promote path servers to the default API server`() {
        MutableSettings.updateSettings(
            genTypes = setOf(CodeGenerationType.CLIENT),
            clientTarget = ClientCodeGenTargetType.OK_HTTP,
        )

        val catalog =
            generate(SchemaGenerationMode.NATIVE, pathServerOnlyOpenApi)
                .filterIsInstance<KotlinSourceSet>()
                .flatMap(KotlinSourceSet::files)
                .single { it.name == "ApiServers" }
                .toString()

        assertThat(catalog).contains("public val defaultServer: ApiServer? = null")
    }

    private fun generate(
        mode: SchemaGenerationMode,
        source: String = openApi,
    ) = CodeGenerator(
        Packages("com.example"),
        SourceApi(source),
        Paths.get(""),
        Paths.get(""),
        mode,
    ).generate()

    private val openApi =
        """
        openapi: 3.2.0
        info:
          title: Server catalog
          version: "1.0"
        servers:
          - url: https://{region}.example.com
            description: Primary API
            variables:
              region:
                enum: [eu, us]
                default: eu
        paths:
          /items:
            servers:
              - url: https://path.example.com
            get:
              operationId: readItems
              responses:
                '200': { description: ok }
            post:
              servers:
                - url: https://write.example.com
                  name: primary-write
              callbacks:
                itemChanged:
                  '{${'$'}request.body#/callbackUrl}':
                    servers:
                      - url: https://callback.example.com
                    post:
                      operationId: notifyItem
                      responses:
                        '204': { description: accepted }
              responses:
                '201': { description: created }
        """.trimIndent()

    private val pathServerOnlyOpenApi =
        """
        openapi: 3.1.2
        info:
          title: Path server
          version: "1.0"
        paths:
          /items:
            servers:
              - url: https://path.example.com
            get:
              responses:
                '200': { description: ok }
        """.trimIndent()
}
