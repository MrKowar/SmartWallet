package com.example.smartwallet

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Горячее обновление интерфейса (index.html и др.) без переустановки APK.
 *
 * Архив подписан тем же ECDSA-ключом, что и лицензии: подписывается строка
 * "SWWEB1|<versionCode>|<webVersion>|<sha256 архива>". Неподписанный или чужой архив не ставится:
 * у JS в WebView есть доступ к Android-мостам. Страницы грузятся по file://, как и встроенные,
 * поэтому localStorage с данными пользователя остаётся тем же.
 */
class WebBundleManager(context: Context) {

    private val prefs = context.getSharedPreferences("SmartWalletUpdates", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "web")

    /** Папка установленного обновления интерфейса или null, если используется встроенный. */
    private fun activeDir(): File? {
        val version = prefs.getInt(KEY_WEB_VERSION, 0)
        if (version == 0 || prefs.getInt(KEY_FOR_VERSION_CODE, 0) != BuildConfig.VERSION_CODE) return null
        val dir = File(root, version.toString())
        return if (File(dir, "index.html").isFile) dir else null
    }

    fun activeWebVersion(): Int = if (activeDir() != null) prefs.getInt(KEY_WEB_VERSION, 0) else 0

    fun mainUrl(): String = pageUrl("index.html")

    fun activationUrl(): String = pageUrl("activation.html")

    fun isMainUrl(url: String?): Boolean = url != null && url.endsWith("/index.html")

    private fun pageUrl(name: String): String {
        val file = activeDir()?.let { File(it, name) }
        return if (file != null && file.isFile) "file://" + file.absolutePath else "file:///android_asset/$name"
    }

    /** Возвращает false, если подпись не сошлась, архив не для этой версии приложения или он повреждён. */
    fun install(zip: ByteArray, signatureBase64: String, webVersion: Int, forVersionCode: Int): Boolean {
        if (forVersionCode != BuildConfig.VERSION_CODE || webVersion <= activeWebVersion()) return false
        if (!verify(zip, signatureBase64, webVersion, forVersionCode)) return false

        val target = File(root, webVersion.toString())
        val tmp = File(root, "$webVersion.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val out = File(tmp, entry.name)
                    // Защита от zip-slip: файлы только внутри папки обновления
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) return false
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
            }
            if (!File(tmp, "index.html").isFile) return false
            target.deleteRecursively()
            if (!tmp.renameTo(target)) return false
        } finally {
            tmp.deleteRecursively()
        }

        prefs.edit()
            .putInt(KEY_WEB_VERSION, webVersion)
            .putInt(KEY_FOR_VERSION_CODE, forVersionCode)
            .apply()
        root.listFiles()?.filter { it.name != webVersion.toString() }?.forEach { it.deleteRecursively() }
        return true
    }

    /** Откат на встроенный интерфейс, если обновлённая страница не открылась. */
    fun reset() {
        prefs.edit().remove(KEY_WEB_VERSION).remove(KEY_FOR_VERSION_CODE).apply()
        root.deleteRecursively()
    }

    private fun verify(zip: ByteArray, signatureBase64: String, webVersion: Int, forVersionCode: Int): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(LicenseVerifier.loadPublicKey())
            update(signedMessage(forVersionCode, webVersion, digest))
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val KEY_WEB_VERSION = "web_version"
        private const val KEY_FOR_VERSION_CODE = "web_for_version_code"

        fun signedMessage(forVersionCode: Int, webVersion: Int, sha256Hex: String): ByteArray =
            "SWWEB1|$forVersionCode|$webVersion|$sha256Hex".toByteArray(Charsets.UTF_8)
    }
}
