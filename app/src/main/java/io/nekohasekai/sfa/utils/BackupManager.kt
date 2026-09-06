package io.nekohasekai.sfa.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.nekohasekai.sfa.constant.Path
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    private const val MANIFEST = "manifest.json"
    private const val VERSION = 2
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_SIZE = 32L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 128L * 1024 * 1024

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
        val keepDav = Settings.webdavPassword
        val keepTok = Settings.githubToken
        var entries = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > MAX_ENTRIES) error("备份包含过多文件")
                val name = entry.name.trimStart('/')
                if (!entry.isDirectory && name.isNotEmpty() && !name.contains("..") && !name.contains('\\')) {
                    val outFile = when {
                        name == MANIFEST -> null
                        name.startsWith("configs/") -> {
                            val relative = name.removePrefix("configs/")
                            if (relative.isBlank() || relative.contains('/')) error("非法备份路径")
                            File(context.filesDir, "configs").also { it.mkdirs() }.let { File(it, relative) }
                        }
                        name.endsWith(".db") && !name.contains('/') -> {
                            context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
                        }
                        else -> null
                    }
                    if (outFile != null) {
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
        if (Settings.webdavPassword.isEmpty() && keepDav.isNotEmpty()) Settings.webdavPassword = keepDav
        if (Settings.githubToken.isEmpty() && keepTok.isNotEmpty()) Settings.githubToken = keepTok
    }

    fun webdavUpload(baseUrl: String, username: String, password: String, remoteName: String, localFile: File): Result<Unit> = runCatching {
        val conn = openWebDav(joinUrl(baseUrl, remoteName), username, password).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/zip")
            setRequestProperty("Content-Length", localFile.length().toString())
        }
        FileInputStream(localFile).use { input -> conn.outputStream.use { output -> input.copyTo(output) } }
        val code = conn.responseCode
        val err = conn.errorStream?.bufferedReader()?.readText()
        conn.disconnect()
        if (code !in 200..299) error("WebDAV 上传失败 HTTP $code${err?.let { ": $it" } ?: ""}")
    }

    fun webdavDownload(baseUrl: String, username: String, password: String, remoteName: String, localFile: File): Result<File> = runCatching {
        val conn = openWebDav(joinUrl(baseUrl, remoteName), username, password).apply { requestMethod = "GET" }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            conn.disconnect()
            error("WebDAV 下载失败 HTTP $code${err?.let { ": $it" } ?: ""}")
        }
        localFile.parentFile?.mkdirs()
        conn.inputStream.use { input -> FileOutputStream(localFile).use { output -> input.copyTo(output) } }
        conn.disconnect()
        localFile
    }

    fun webdavProbe(baseUrl: String, username: String, password: String): Result<Boolean> = runCatching {
        val target = URL(requireHttps(baseUrl))
        var lastDetail = "no response"
        for (method in listOf("OPTIONS", "PROPFIND")) {
            val conn = openWebDav(target.toString(), username, password).apply {
                requestMethod = method
                instanceFollowRedirects = false
                if (method == "PROPFIND") {
                    setRequestProperty("Depth", "0")
                    setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                }
            }
            try {
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location").orEmpty()
                lastDetail = "HTTP $code"
                if (code in 300..399 && loc.isNotEmpty()) {
                    val next = try { URL(target, loc) } catch (_: Exception) { null }
                    if (next == null || next.protocol != "https" || next.host != target.host) error("连通性失败：重定向到不安全主机")
                }
                if (code == 404 || code == 410) continue
                val dav = conn.getHeaderField("DAV").orEmpty()
                val allow = conn.getHeaderField("Allow").orEmpty()
                val davLike = dav.isNotEmpty() || allow.contains("PROPFIND", true) || code == 207 ||
                    (method == "OPTIONS" && code in 200..204) || (method == "PROPFIND" && code in 200..207)
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

    private fun openWebDav(url: String, username: String, password: String): HttpURLConnection {
        val conn = URL(requireHttps(url)).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        if (username.isNotEmpty()) {
            val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
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
