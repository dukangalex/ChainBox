package io.nekohasekai.sfa.utils

import android.content.Context
import android.util.Base64
import io.nekohasekai.sfa.constant.Path
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup / restore profiles + settings into a zip.
 */
object BackupManager {
    private const val MANIFEST = "manifest.json"
    private const val VERSION = 1

    fun createBackupFile(context: Context, dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
            val manifest = JSONObject()
                .put("version", VERSION)
                .put("app", "chainbox")
                .put("time", System.currentTimeMillis())
            putEntry(zos, MANIFEST, manifest.toString().toByteArray())
            copyDbGroup(zos, context, Path.SETTINGS_DATABASE_PATH)
            copyDbGroup(zos, context, Path.PROFILES_DATABASE_PATH)
            val configs = File(context.filesDir, "configs")
            if (configs.isDirectory) {
                configs.listFiles()?.forEach { f ->
                    if (f.isFile) putFile(zos, "configs/${f.name}", f)
                }
            }
        }
        dest
    }

    fun restoreBackupFile(context: Context, src: File): Result<Unit> = runCatching {
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
                        name.endsWith(".db") || name.contains(".db-") -> {
                            context.getDatabasePath(name.substringAfterLast('/')).also {
                                it.parentFile?.mkdirs()
                            }
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
    }

    fun webdavUpload(
        baseUrl: String,
        username: String,
        password: String,
        remoteName: String,
        localFile: File,
    ): Result<Unit> = runCatching {
        val url = joinUrl(baseUrl, remoteName)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/zip")
            if (username.isNotEmpty()) {
                val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        FileInputStream(localFile).use { input ->
            conn.outputStream.use { output -> input.copyTo(output) }
        }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) error("WebDAV 上传失败 HTTP $code")
    }

    fun webdavDownload(
        baseUrl: String,
        username: String,
        password: String,
        remoteName: String,
        localFile: File,
    ): Result<File> = runCatching {
        val url = joinUrl(baseUrl, remoteName)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            if (username.isNotEmpty()) {
                val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("WebDAV 下载失败 HTTP $code")
        }
        localFile.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(localFile).use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        localFile
    }

    fun webdavProbe(baseUrl: String, username: String, password: String): Result<Boolean> = runCatching {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PROPFIND"
            setRequestProperty("Depth", "0")
            connectTimeout = 15_000
            readTimeout = 15_000
            if (username.isNotEmpty()) {
                val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299 || code == 207 || code == 405 || code == 501
    }

    private fun copyDbGroup(zos: ZipOutputStream, context: Context, dbName: String) {
        val main = context.getDatabasePath(dbName)
        if (main.isFile) putFile(zos, dbName, main)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val side = File(main.path + suffix)
            if (side.isFile) putFile(zos, dbName + suffix, side)
        }
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
        val b = base.trimEnd('/')
        val n = name.trimStart('/')
        return "$b/$n"
    }
}
