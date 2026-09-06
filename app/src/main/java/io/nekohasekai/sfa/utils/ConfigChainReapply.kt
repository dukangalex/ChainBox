package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.database.Settings

/**
 * Compatibility entry point for existing update/startup flows.
 * All chain materialization is delegated to ChainRuntimeCompiler so save,
 * subscription refresh and runtime reload use exactly the same topology.
 */
object ConfigChainReapply {
    suspend fun apply(content: String): String {
        return ChainRuntimeCompiler.apply(content, Settings.selectedProfile)
    }
}
