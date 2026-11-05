package com.example.smartwallet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Удалённое обновление в обход магазинов.
 *
 * update.json (GitHub Releases, собирает tools/Publish.java):
 * {
 *   "versionCode": 4, "versionName": "1.3", "changelog": "...",
 *   "apkUrl": "...", "apkSha256": "...",
 *   "web": { "versionCode": 3, "webVersion": 2, "url": "...", "signature": "..." }
 * }
 * Блок "web" ставится тихо и включается при следующем запуске; новый APK предлагается пользователю.
 */
class UpdateManager(private val context: Context, private val webBundles: WebBundleManager) {

    private val prefs = context.getSharedPreferences("SmartWalletUpdates", Context.MODE_PRIVATE)

    /** Проверяет обновления. Возвращает манифест, если доступен новый APK, иначе null. */
    fun check(force: Boolean = false): JSONObject? {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return null
        if (BuildConfig.UPDATE_MANIFEST_URL.isBlank()) return null

        val manifest = JSONObject(String(download(BuildConfig.UPDATE_MANIFEST_URL, MAX_MANIFEST_BYTES), Charsets.UTF_8))
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        manifest.optJSONObject("web")?.let { web ->
            val forCode = web.optInt("versionCode")
            val webVersion = web.optInt("webVersion")
            if (forCode == BuildConfig.VERSION_CODE && webVersion > webBundles.activeWebVersion()) {
                try {
                    val zip = download(web.getString("url"), MAX_WEB_BYTES)
                    webBundles.install(zip, web.getString("signature"), webVersion, forCode)
                } catch (e: Exception) {
                    // Интерфейс обновится при следующей проверке
                }
            }
        }

        return if (manifest.optInt("versionCode") > BuildConfig.VERSION_CODE && manifest.optString("apkUrl").isNotBlank()) manifest else null
    }

    /** Скачивает APK в cache/updates и сверяет SHA-256 из манифеста. */
    fun downloadApk(manifest: JSONObject, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "SmartWallet-${manifest.optString("versionName", "update")}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        val connection = open(manifest.getString("apkUrl"))
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(manifest.optString("apkSha256"), ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("Файл обновления повреждён, попробуйте ещё раз")
        }
        return file
    }

    /**
     * Ставит обновление через PackageInstaller (SelfInstaller). Если ещё нет разрешения на установку
     * из этого приложения, отправляет пользователя в настройки и возвращает false.
     */
    fun install(activity: Activity, apk: File, versionName: String): Boolean {
        if (!SelfInstaller.canInstall(activity)) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        SelfInstaller.install(activity, apk, versionName, fromUi = true)
        return true
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        if (connection.responseCode !in 200..299) throw IllegalStateException("Сервер обновлений вернул HTTP ${connection.responseCode}")
        return connection
    }

    private fun download(url: String, limit: Int): ByteArray = open(url).inputStream.use { readLimited(it, limit) }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw IllegalStateException("Файл обновления слишком большой")
        }
        return out.toByteArray()
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_update_check"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_WEB_BYTES = 10 * 1024 * 1024
    }
}
