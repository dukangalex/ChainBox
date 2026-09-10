package io.nekohasekai.sfa.chain

data class ChainPathHop(
    val role: Role,
    val label: String = "",
    val detail: String = "",
    val highlighted: Boolean = false,
) {
    enum class Role {
        Device,
        Entry,
        Exit,
        Landing,
        Destination,
    }
}

data class ChainPath(
    val chained: Boolean,
    val hops: List<ChainPathHop>,
    val entryTag: String = "",
    val landingTag: String = "",
    val landingProfileName: String = "",
    val profileName: String = "",
) {
    companion object {
        fun regular(profileName: String = "", exitTag: String = ""): ChainPath {
            return ChainPathBuilder.build(
                profileName = profileName,
                defaultOutboundTag = exitTag,
                binding = null,
                landingProfileName = null,
            )
        }
    }
}

/**
 * Dashboard hop diagram. Bindings highlight 入口 → 落地; otherwise the
 * current profile's default outbound is the single exit hop.
 */
object ChainPathBuilder {
    fun build(
        profileName: String?,
        defaultOutboundTag: String?,
        binding: ChainBinding?,
        landingProfileName: String?,
    ): ChainPath {
        val profile = profileName?.trim().orEmpty()
        val exitLabel = defaultOutboundTag?.trim().orEmpty().ifEmpty { profile }
        if (binding == null) {
            return ChainPath(
                chained = false,
                hops = listOf(
                    ChainPathHop(ChainPathHop.Role.Device),
                    ChainPathHop(
                        role = ChainPathHop.Role.Exit,
                        label = exitLabel,
                        detail = profile,
                    ),
                    ChainPathHop(ChainPathHop.Role.Destination),
                ),
                profileName = profile,
            )
        }
        val landingName = landingProfileName?.trim().orEmpty()
        val entryLabel = binding.entryTag.trim().ifEmpty { exitLabel }
        val landingDetail = when {
            landingName.isNotEmpty() && landingName != profile -> landingName
            profile.isNotEmpty() -> profile
            else -> ""
        }
        return ChainPath(
            chained = true,
            hops = listOf(
                ChainPathHop(ChainPathHop.Role.Device),
                ChainPathHop(
                    role = ChainPathHop.Role.Entry,
                    label = entryLabel,
                    detail = profile,
                    highlighted = true,
                ),
                ChainPathHop(
                    role = ChainPathHop.Role.Landing,
                    label = binding.landingTag.trim(),
                    detail = landingDetail,
                    highlighted = true,
                ),
                ChainPathHop(ChainPathHop.Role.Destination),
            ),
            entryTag = binding.entryTag.trim(),
            landingTag = binding.landingTag.trim(),
            landingProfileName = landingName,
            profileName = profile,
        )
    }
}
