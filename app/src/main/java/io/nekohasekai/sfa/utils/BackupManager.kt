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
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Backup / restore profiles + settings into a zip.
 * WebDAV uses Proxy.NO_PROXY so traffic is not forced through the local VPN proxy
 * (which often breaks TLS with "Trust anchor for certification path not found").
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
        val conn = openWebDav(url, username, password).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/zip")
            setRequestProperty("Content-Length", localFile.length().toString())
        }
        FileInputStream(localFile).use { input ->
            conn.outputStream.use { output -> input.copyTo(output) }
        }
        val code = conn.responseCode
        val err = conn.errorStream?.bufferedReader()?.readText()
        conn.disconnect()
        if (code !in 200..299) {
            error("WebDAV 上传失败 HTTP $code${err?.let { ": $it" } ?: ""}")
        }
    }

    fun webdavDownload(
        baseUrl: String,
        username: String,
        password: String,
        remoteName: String,
        localFile: File,
    ): Result<File> = runCatching {
        val url = joinUrl(baseUrl, remoteName)
        val conn = openWebDav(url, username, password).apply {
            requestMethod = "GET"
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            conn.disconnect()
            error("WebDAV 下载失败 HTTP $code${err?.let { ": $it" } ?: ""}")
        }
        localFile.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(localFile).use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        localFile
    }

    /**
     * Reachability check. Some hosts (e.g. TeraCloud) reject or mishandle PROPFIND
     * while PUT/GET still work — try several safe methods and treat any HTTP
     * response as "reachable".
     */
    fun webdavProbe(baseUrl: String, username: String, password: String): Result<Boolean> = runCatching {
        val methods = listOf("OPTIONS", "PROPFIND", "HEAD", "GET")
        var lastError: Exception? = null
        for (method in methods) {
            val conn = openWebDav(baseUrl, username, password).apply {
                requestMethod = method
                if (method == "PROPFIND") {
                    setRequestProperty("Depth", "0")
                    setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                }
                if (method == "GET") {
                    setRequestProperty("Range", "bytes=0-0")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 100..599) {
                    return@runCatching true
                }
            } catch (e: Exception) {
                lastError = e
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        if (lastError != null) throw lastError
        false
    }

    private fun openWebDav(url: String, username: String, password: String): HttpURLConnection {
        val conn = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        if (username.isNotEmpty()) {
            val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        if (conn is HttpsURLConnection) {
            applyLenientSsl(conn)
        }
        return conn
    }

    private fun applyLenientSsl(conn: HttpsURLConnection) {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            },
        )
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
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
