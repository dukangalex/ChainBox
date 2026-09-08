package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import java.io.File

/**
 * Compatibility entry point for existing update/startup flows.
 * All chain materialization is delegated to ChainRuntimeCompiler so save,
 * subscription refresh and runtime reload use exactly the same topology.
 * Bindings are per-profile: only the currently selected configuration is chained.
 */
object ConfigChainReapply {
    suspend fun apply(content: String): String {
        val currentProfileId = Settings.selectedProfile
        val binding = ChainBindings.get(currentProfileId) ?: return content
        val landingId = binding.landingProfileId
        val landingTag = binding.landingTag.trim()
        require(landingId >= 0L && landingTag.isNotEmpty()) { "未选择链式落地出口" }

        val landingContent = if (landingId == currentProfileId) {
            null
        } else {
            val landingProfile = ProfileManager.get(landingId) ?: error("落地配置不存在或已被删除")
            File(landingProfile.typed.path).readText()
        }
        return ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = content,
                currentProfileId = currentProfileId,
                entryTag = binding.entryTag.trim().ifEmpty { null },
                landingProfileId = landingId,
                landingTag = landingTag,
                landingContent = landingContent,
            ),
        )
    }
}
