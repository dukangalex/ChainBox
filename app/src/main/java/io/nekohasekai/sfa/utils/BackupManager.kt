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
    private const val VERSION = 1

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
        ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/')
                if (!entry.isDirectory && name.isNotEmpty() && !name.contains("..")) {
                    val outFile = when {
                        name == MANIFEST -> null
                        name.startsWith("configs/") -> {
                            val configs = File(context.filesDir, "configs").also { it.mkdirs() }
                            File(configs, name.removePrefix("configs/"))
                        }
                        name.endsWith(".db") -> {
                            context.getDatabasePath(name.substringAfterLast('/')).also { it.parentFile?.mkdirs() }
                        }
                        else -> null
                    }
                    if (outFile != null) {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (Settings.webdavPassword.isEmpty() && keepDav.isNotEmpty()) Settings.webdavPassword = keepDav
        if (Settings.githubToken.isEmpty() && keepTok.isNotEmpty()) Settings.githubToken = keepTok
    }

    fun webdavUpload(
        baseUrl: String, username: String, password: String, remoteName: String, localFile: File,
    ): Result<Unit> = runCatching {
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

    fun webdavDownload(
        baseUrl: String, username: String, password: String, remoteName: String, localFile: File,
    ): Result<File> = runCatching {
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
        val target = URL(baseUrl)
        var lastDetail = "no response"
        for (method in listOf("OPTIONS", "PROPFIND")) {
            val conn = openWebDav(baseUrl, username, password).apply {
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
                    if (next == null || next.host != target.host) error("连通性失败：重定向到其他主机 ($code)")
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
        val conn = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        if (username.isNotEmpty()) {
            val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
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
        putEntry(zos, name, file.readBytes())
    }

    private fun putEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun joinUrl(base: String, name: String): String {
        return base.trimEnd('/') + "/" + name.trimStart('/')
    }
}
