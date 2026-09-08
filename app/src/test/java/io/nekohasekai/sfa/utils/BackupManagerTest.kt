package io.nekohasekai.sfa.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupManagerTest {

    @Test
    fun probeRejectsCleartext() {
        val result = BackupManager.webdavProbe("http://example.com/dav/", "u", "p")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    fun sslErrorsGetFriendlyHint() {
        val raw = RuntimeException(
            "javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
        )
        val wrapped = BackupManager.friendlySsl(raw)
        val msg = wrapped.message.orEmpty()
        assertTrue(msg.contains("证书校验失败"))
        assertTrue(msg.contains("绕过 VPN"))
    }

    @Test
    fun zipMagicDetection() {
        val tmp = File.createTempFile("cb-zip", ".bin")
        tmp.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        assertTrue(BackupManager.isZipFile(tmp))
        tmp.writeText("<html>error</html>")
        assertFalse(BackupManager.isZipFile(tmp))
        tmp.delete()
    }
}