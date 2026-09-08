package io.nekohasekai.sfa.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Network
import android.net.NetworkCapabilities
import android.net.SSLCertificateSocketFactory
import android.util.Base64
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object BackupManager {
    private const val MANIFEST = "manifest.json"
    private const val VERSION = 2
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_SIZE = 32L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 128L * 1024 * 1024

    private val systemSslSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        try {
            val store = KeyStore.getInstance("AndroidCAStore")
            store.load(null)
            tmf.init(store)
        } catch (_: Exception) {
            tmf.init(null as KeyStore?)
        }
        SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, SecureRandom()) }.socketFactory
    }

    fun createBackupFile(context: Context, dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        val savedDavPass = Settings.webdavPassword
        val savedToken = Settings.githubToken
        Settings.webdavPassword = ""
        Settings.githubToken = ""
        try {
            Thread.sleep(250)
            checkpoint(context, Path.SETTINGS_DATABASE_PATH)
            checkpoint(context, Path.PROFILES_DATABASE_PATH)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
                val manifest = JSONObject()
                    .put("version", VERSION)
                    .put("app", "chainbox")
                    .put("time", System.currentTimeMillis())
                    .put("secrets", "omitted")
                putEntry(zos, MANIFEST, manifest.toString().toByteArray())
                copyMainDbOnly(zos, context, Path.SETTINGS_DATABASE_PATH)
                copyMainDbOnly(zos, context, Path.PROFILES_DATABASE_PATH)
                val configs = File(context.filesDir, "configs")
                if (configs.isDirectory) {
                    configs.listFiles()?.forEach { f ->
                        if (f.isFile) putFile(zos, "configs/${f.name}", f)
                    }
                }
            }
        } finally {
            Settings.webdavPassword = savedDavPass
            Settings.githubToken = savedToken
        }
        dest
    }

    fun restoreBackupFile(context: Context, src: File): Result<Unit> = runCatching {
        require(isZipFile(src)) { "不是有效的 ZIP 备份（可能下到了网页错误页）。请重新备份后再恢复。" }
        val keepDav = Settings.webdavPassword
        val keepTok = Settings.githubToken
        val staging = File(context.cacheDir, "restore-staging").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        var entries = 0
        var total = 0L
        var hasData = false
        ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > MAX_ENTRIES) error("备份包含过多文件")
                val name = entry.name.trimStart('/')
                if (!entry.isDirectory && name.isNotEmpty() && !name.contains("..") && !name.contains('\\')) {
                    val outFile = when {
                        name == MANIFEST -> File(staging, MANIFEST)
                        name.startsWith("configs/") -> {
                            val relative = name.removePrefix("configs/")
                            if (relative.isBlank() || relative.contains('/')) error("非法备份路径")
                            File(staging, "configs").also { it.mkdirs() }.let { File(it, relative) }
                        }
                        name.endsWith(".db") && !name.contains('/') -> File(staging, name)
                        else -> null
                    }
                    if (outFile != null) {
                        if (name.endsWith(".db") || name.startsWith("configs/")) hasData = true
                        outFile.parentFile?.mkdirs()
                        var entryBytes = 0L
                        FileOutputStream(outFile).use { fos ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zis.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                total += read
                                if (entryBytes > MAX_ENTRY_SIZE || total > MAX_TOTAL_SIZE) error("备份展开大小超过限制")
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (!hasData) error("备份里没有配置或数据库，无法恢复")

        runCatching { BoxService.stop() }
        Thread.sleep(350)
        Settings.closeDatabase()
        ProfileManager.closeDatabase()

        val settingsDb = File(staging, Path.SETTINGS_DATABASE_PATH)
        val profilesDb = File(staging, Path.PROFILES_DATABASE_PATH)
        if (settingsDb.isFile) {
            val dest = context.getDatabasePath(Path.SETTINGS_DATABASE_PATH)
            dest.parentFile?.mkdirs()
            deleteSidecars(dest)
            settingsDb.copyTo(dest, overwrite = true)
            deleteSidecars(dest)
        }
        if (profilesDb.isFile) {
            val dest = context.getDatabasePath(Path.PROFILES_DATABASE_PATH)
            dest.parentFile?.mkdirs()
            deleteSidecars(dest)
            profilesDb.copyTo(dest, overwrite = true)
            deleteSidecars(dest)
        }
        val stagedConfigs = File(staging, "configs")
        if (stagedConfigs.isDirectory) {
            val live = File(context.filesDir, "configs").also { it.mkdirs() }
            stagedConfigs.listFiles()?.forEach { f ->
                if (f.isFile) f.copyTo(File(live, f.name), overwrite = true)
            }
        }

        val liveSettings = context.getDatabasePath(Path.SETTINGS_DATABASE_PATH)
        if (keepDav.isNotEmpty()) putSettingString(liveSettings, SettingsKey.WEBDAV_PASSWORD, keepDav)
        if (keepTok.isNotEmpty()) putSettingString(liveSettings, SettingsKey.GITHUB_TOKEN, keepTok)
        staging.deleteRecursively()
    }

    fun webdavUpload(baseUrl: String, username: String, password: String, remoteName: String, localFile: File): Result<Unit> = runCatching {
        wrapSsl {
            val conn = openWebDav(joinUrl(baseUrl, remoteName), username, password).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/zip")
                setRequestProperty("Content-Length", localFile.length().toString())
            }
            try {
                FileInputStream(localFile).use { input -> conn.outputStream.use { output -> input.copyTo(output) } }
                val code = conn.responseCode
                val err = conn.errorStream?.bufferedReader()?.readText()
                if (code !in 200..299) error("WebDAV 上传失败 HTTP $code${err?.let { ": $it" } ?: ""}")
            } finally {
                conn.disconnect()
            }
        }
    }

    fun webdavDownload(baseUrl: String, username: String, password: String, remoteName: String, localFile: File): Result<File> = runCatching {
        wrapSsl {
            val conn = openWebDav(joinUrl(baseUrl, remoteName), username, password).apply { requestMethod = "GET" }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText()
                    error("WebDAV 下载失败 HTTP $code${err?.let { ": $it" } ?: ""}")
                }
                localFile.parentFile?.mkdirs()
                conn.inputStream.use { input -> FileOutputStream(localFile).use { output -> input.copyTo(output) } }
                require(isZipFile(localFile)) {
                    "下载内容不是 ZIP 备份（服务器可能返回了错误页）。请确认远程文件名「$remoteName」正确。"
                }
                localFile
            } finally {
                conn.disconnect()
            }
        }
    }

    fun webdavProbe(baseUrl: String, username: String, password: String): Result<Boolean> = runCatching {
        wrapSsl {
            val target = URL(requireHttps(baseUrl))
            var lastDetail = "no response"
            for (method in listOf("OPTIONS", "PROPFIND", "GET")) {
                val conn = openWebDav(target.toString(), username, password).apply {
                    requestMethod = method
                    instanceFollowRedirects = false
                    if (method == "PROPFIND") {
                        setRequestProperty("Depth", "0")
                        setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                        doOutput = true
                    }
                }
                try {
                    if (method == "PROPFIND") {
                        conn.outputStream.use { it.write(ByteArray(0)) }
                    }
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location").orEmpty()
                    lastDetail = "HTTP $code"
                    if (code in 300..399 && loc.isNotEmpty()) {
                        val next = try { URL(target, loc) } catch (_: Exception) { null }
                        if (next == null || next.protocol != "https" || next.host != target.host) {
                            error("连通性失败：重定向到不安全主机")
                        }
                        lastDetail = "redirect $code"
                    }
                    if (code == 404 || code == 410) continue
                    val dav = conn.getHeaderField("DAV").orEmpty()
                    val allow = conn.getHeaderField("Allow").orEmpty()
                    val davLike = dav.isNotEmpty() || allow.contains("PROPFIND", true) || code == 207 ||
                        (method == "OPTIONS" && code in 200..204) ||
                        (method == "PROPFIND" && code in 200..207) ||
                        (method == "GET" && code in 200..299)
                    if (code == 401 || code == 403) return@runCatching true
                    if (davLike && code in 200..299) return@runCatching true
                } catch (e: Exception) {
                    lastDetail = e.message ?: e.javaClass.simpleName
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            }
            error("连通性失败：$lastDetail")
        }
    }

    internal fun isZipFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return FileInputStream(file).use { ins ->
            val magic = ByteArray(4)
            if (ins.read(magic) != 4) return@use false
            magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        }
    }

    private inline fun <T> wrapSsl(block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            throw friendlySsl(e)
        }
    }

    internal fun friendlySsl(e: Exception): Exception {
        val text = buildString {
            append(e.message ?: "")
            var c = e.cause
            var n = 0
            while (c != null && n++ < 6) {
                append(' ')
                append(c.javaClass.simpleName)
                append(':')
                append(c.message ?: "")
                c = c.cause
            }
        }
        if (
            text.contains("Trust anchor", true) ||
            text.contains("CertPath", true) ||
            text.contains("SSLHandshake", true) ||
            text.contains("CertificateException", true)
        ) {
            return IllegalStateException(
                "证书校验失败。备份已尽量绕过 VPN 走系统网络直连。请确认设备时间正确，以及 WebDAV 站点证书链完整（常见于 TeraCLOUD / 自签证书）。原始错误：${e.message}",
                e,
            )
        }
        return if (e is IllegalStateException) e else IllegalStateException(e.message ?: e.javaClass.simpleName, e)
    }

    private fun openWebDav(url: String, username: String, password: String): HttpURLConnection {
        val conn = openConnection(URL(requireHttps(url)))
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "ChainBox-WebDAV")
        conn.setRequestProperty("Connection", "close")
        if (username.isNotEmpty()) {
            val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val network = pickNonVpnNetwork()
        val conn = try {
            if (network != null) network.openConnection(url) as HttpURLConnection
            else url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        } catch (_: Exception) {
            url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        }
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = platformSslSocketFactory()
            conn.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        }
        return conn
    }

    @Suppress("DEPRECATION")
    private fun platformSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        return try {
            SSLCertificateSocketFactory.getDefault(15_000, null)
        } catch (_: Exception) {
            try {
                HttpsURLConnection.getDefaultSSLSocketFactory()
            } catch (_: Exception) {
                systemSslSocketFactory
            }
        }
    }

    private fun pickNonVpnNetwork(): Network? {
        return try {
            val cm = Application.connectivity
            val candidates = mutableListOf<Pair<Int, Network>>()
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val score = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> 0
                }
                if (score > 0) candidates.add(score to n)
            }
            candidates.maxByOrNull { it.first }?.second
        } catch (_: Exception) {
            null
        }
    }

    private fun requireHttps(url: String): String {
        val normalized = url.trim()
        require(normalized.startsWith("https://", ignoreCase = true)) { "WebDAV 必须使用 HTTPS" }
        return normalized
    }

    private fun checkpoint(context: Context, dbName: String) {
        val main = context.getDatabasePath(dbName)
        if (!main.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
        }
    }

    private fun deleteSidecars(dbFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(dbFile.path + suffix).delete()
        }
    }

    private fun putSettingString(dbFile: File, key: String, value: String) {
        if (value.isEmpty() || !dbFile.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val cv = ContentValues()
                cv.put("key", key)
                cv.put("valueType", 4)
                cv.put("value", value.toByteArray())
                db.insertWithOnConflict("KeyValueEntity", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun copyMainDbOnly(zos: ZipOutputStream, context: Context, dbName: String) {
        val main = context.getDatabasePath(dbName)
        if (main.isFile) putFile(zos, dbName, main)
    }

    private fun putFile(zos: ZipOutputStream, name: String, file: File) {
        require(file.length() <= MAX_ENTRY_SIZE) { "备份文件过大：$name" }
        putEntry(zos, name, file.readBytes())
    }

    private fun putEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun joinUrl(base: String, name: String): String = base.trimEnd('/') + "/" + name.trimStart('/')
}
