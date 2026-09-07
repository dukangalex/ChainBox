package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import java.io.File

/**
 * Compatibility entry point for existing update/startup flows.
 * All chain materialization is delegated to ChainRuntimeCompiler so save,
 * subscription refresh and runtime reload use exactly the same topology.
 */
object ConfigChainReapply {
    suspend fun apply(content: String): String {
        if (!Settings.chainEnabled) return content
        val currentProfileId = Settings.selectedProfile
        val bound = Settings.chainBoundProfileId
        if (bound >= 0L && bound != currentProfileId) return content
        if (bound < 0L) Settings.chainBoundProfileId = currentProfileId

        val landingId = Settings.chainLandingProfileId
        val landingTag = Settings.chainLandingTag.trim()
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
                entryTag = Settings.chainEntryTag.trim().ifEmpty { null },
                landingProfileId = landingId,
                landingTag = landingTag,
                landingContent = landingContent,
            ),
        )
    }
}
