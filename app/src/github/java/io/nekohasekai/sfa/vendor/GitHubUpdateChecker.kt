package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

class GitHubUpdateChecker : Closeable {
    companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/dukangalex/ChainBox/releases"
        const val RELEASES_PAGE_URL =
            "https://github.com/dukangalex/ChainBox/releases"
        private const val PREFERRED_APK = "AngelaBox-android.apk"
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        val releases = getReleases(githubToken)
        var selected: ReleaseCandidate? = null

        for (release in releases) {
            if (!isReleaseInTrack(release, track)) continue
            val versionName = normalizeVersion(release.tagName.ifBlank { release.name })
            if (versionName.isEmpty()) continue
            if (!isNewerThanCurrent(versionName)) continue
            val metadata = VersionMetadata(
                versionCode = versionCodeFromName(versionName),
                versionName = versionName,
            )
            val currentBest = selected
            if (currentBest == null || isBetterVersion(metadata, currentBest.metadata)) {
                selected = ReleaseCandidate(release, metadata)
            }
        }

        val release = selected?.release ?: return null
        val metadata = selected.metadata

        val isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val apkAsset = pickApkAsset(release.assets, isLegacy)

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
            releaseUrl = release.htmlUrl.ifBlank { RELEASES_PAGE_URL },
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = apkAsset?.size ?: 0,
            sha256 = runCatching { pickSha256(release.assets, apkAsset, githubToken) }.getOrNull(),
        )
    }

    private fun pickApkAsset(assets: List<GitHubAsset>, isLegacy: Boolean): GitHubAsset? {
        val apks = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                !asset.name.contains("play", ignoreCase = true)
        }
        if (apks.isEmpty()) return null
        if (isLegacy) {
            return apks.find { it.name.contains("legacy", ignoreCase = true) } ?: apks.first()
        }
        return apks.find { it.name.equals(PREFERRED_APK, ignoreCase = true) }
            ?: apks.find { it.name.equals("ChainBox-android.apk", ignoreCase = true) }
            ?: apks.find {
                (it.name.contains("AngelaBox", ignoreCase = true) ||
                    it.name.contains("ChainBox", ignoreCase = true)) &&
                    !it.name.contains("legacy", ignoreCase = true)
            }
            ?: apks.find { !it.name.contains("legacy", ignoreCase = true) }
            ?: apks.first()
    }

    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val request = client.newRequest()
        request.setURL(RELEASES_URL)
        request.setHeader("Accept", "application/vnd.github.v3+json")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.setHeader("Authorization", "Bearer $token")
        }
        request.setUserAgent(HTTPClient.userAgent)

        val content = try {
            val response = request.execute()
            response.content.unwrap
        } catch (e: Exception) {
            throw IllegalStateException(
                "无法连接 GitHub Releases（${e.message ?: e.javaClass.simpleName}）。可在浏览器打开 $RELEASES_PAGE_URL",
                e,
            )
        }
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            throw IllegalStateException("GitHub Releases 返回空响应")
        }
        if (trimmed.startsWith("{")) {
            val err = runCatching { json.decodeFromString<GitHubApiError>(trimmed) }.getOrNull()
            val msg = err?.message.orEmpty()
            if (msg.contains("rate limit", ignoreCase = true)) {
                throw IllegalStateException("GitHub API 速率限制，请稍后重试，或在设置里填写 GitHub Token")
            }
            throw IllegalStateException(msg.ifBlank { "GitHub API 错误" })
        }
        return json.decodeFromString(trimmed)
    }

    private fun pickSha256(assets: List<GitHubAsset>, apk: GitHubAsset?, githubToken: String): String? {
        val apkName = apk?.name ?: PREFERRED_APK
        val shaAsset = assets.find { it.name.equals("$apkName.sha256", ignoreCase = true) }
            ?: assets.find { it.name.equals("$PREFERRED_APK.sha256", ignoreCase = true) }
            ?: assets.find { it.name.equals("ChainBox-android.apk.sha256", ignoreCase = true) }
            ?: return null
        val body = getText(shaAsset.browserDownloadUrl, githubToken)
        val hex = body.trim().substringBefore(' ').substringBefore('\t').lowercase()
        return hex.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }

    private fun getText(url: String, githubToken: String): String {
        val request = client.newRequest()
        request.setURL(url)
        request.setHeader("Accept", "application/octet-stream")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.setHeader("Authorization", "Bearer $token")
        }
        request.setUserAgent(HTTPClient.userAgent)
        return request.execute().content.unwrap
    }

    private fun isReleaseInTrack(release: GitHubRelease, track: UpdateTrack): Boolean {
        if (release.draft) return false
        return when (track) {
            UpdateTrack.STABLE -> !release.prerelease
            UpdateTrack.BETA -> true
        }
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore(" ")

    private fun versionCodeFromName(name: String): Int {
        val parts = name.split(".", "-")
        val major = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }

    private fun isNewerThanCurrent(versionName: String): Boolean {
        val byCode = versionCodeFromName(versionName) > BuildConfig.VERSION_CODE
        val bySemver = try {
            Libbox.compareSemver(versionName, BuildConfig.VERSION_NAME)
        } catch (_: Throwable) {
            false
        }
        return byCode || bySemver
    }

    private fun isBetterVersion(version: VersionMetadata, other: VersionMetadata): Boolean {
        val aNewer = try {
            Libbox.compareSemver(version.versionName, other.versionName)
        } catch (_: Throwable) {
            false
        }
        val bNewer = try {
            Libbox.compareSemver(other.versionName, version.versionName)
        } catch (_: Throwable) {
            false
        }
        if (aNewer) return true
        if (bNewer) return false
        return version.versionCode > other.versionCode
    }

    override fun close() {
        client.close()
    }

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class VersionMetadata(
        @SerialName("version_code") val versionCode: Int = 0,
        @SerialName("version_name") val versionName: String = "",
    )

    @Serializable
    data class GitHubApiError(
        val message: String = "",
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val metadata: VersionMetadata,
    )
}
