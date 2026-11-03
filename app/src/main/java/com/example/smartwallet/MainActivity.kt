package com.example.smartwallet

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var licenseManager: LicenseManager
    private var isWebViewLoaded = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    private val pushReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("pushText") ?: return
            sendToWeb(text)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Запрос разрешения на отправку собственных уведомлений (для Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        licenseManager = LicenseManager(this)

        webView = findViewById(R.id.webView)
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.sw_navy))

        // Android 15+ рисует приложение под системными панелями — отодвигаем контент от часов и жестовой полосы
        ViewCompat.setOnApplyWindowInsetsListener(webView.parent as View) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Мост между JavaScript и нативной частью Android
        webView.addJavascriptInterface(AndroidScannerInterface(this), "AndroidScanner")
        webView.addJavascriptInterface(LicenseInterface(this), "License")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Пуши отдаём только в основной интерфейс, а не на экран активации
                isWebViewLoaded = url == MAIN_URL
                if (!isWebViewLoaded) return

                // Даем интерфейсу 1.5 секунды на полную прогрузку
                Handler(Looper.getMainLooper()).postDelayed({
                    checkPendingPushes()
                }, 1500)
            }
        }

        // Выбор фото из галереи для аватарки (<input type="file"> в WebView)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                imagePicker.launch("image/*")
                return true
            }
        }

        if (licenseManager.shouldShowActivation()) {
            webView.loadUrl(ACTIVATION_URL)
        } else {
            webView.loadUrl(MAIN_URL)
        }

        val filter = IntentFilter("com.example.smartwallet.PUSH_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pushReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pushReceiver, filter)
        }

        // Экран доступа к уведомлениям больше не открываем принудительно:
        // он откроется только когда пользователь включит ползунок в настройках.
    }

    // Проверяем память каждый раз, когда разворачиваем приложение из свернутого состояния
    override fun onResume() {
        super.onResume()
        if (isWebViewLoaded) {
            checkPendingPushes()
        }
    }

    // Безопасная отправка прямого пуша с шифрованием спецсимволов
    private fun sendToWeb(text: String) {
        if (!isWebViewLoaded) return
        val encodedText = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        runOnUiThread {
            webView.evaluateJavascript("javascript:handlePushNotificationEncoded('$encodedText');", null)
        }
    }

    // Безопасная выгрузка всей очереди
    private fun checkPendingPushes() {
        if (!isWebViewLoaded) return
        val prefs = getSharedPreferences("SmartWalletPrefs", Context.MODE_PRIVATE)
        val savedPushes = prefs.getString("pending_pushes", "[]") ?: "[]"

        if (savedPushes != "[]") {
            val encodedJson = URLEncoder.encode(savedPushes, "UTF-8").replace("+", "%20")
            runOnUiThread {
                webView.evaluateJavascript("javascript:processPendingQueueEncoded('$encodedJson');", null)
            }
            prefs.edit().putString("pending_pushes", "[]").apply()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pushReceiver)
    }

    // === КЛАСС ДЛЯ РАБОТЫ С СКАНЕРОМ ЧЕКОВ И ИИ ===
    inner class AndroidScannerInterface(private val context: Context) {

        // Ползунок «Читать уведомления Mir Pay» из настроек приложения
        @JavascriptInterface
        fun isMirPayNotificationsEnabled(): Boolean {
            return context.getSharedPreferences(NotificationService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(NotificationService.KEY_MIRPAY_ENABLED, false)
        }

        @JavascriptInterface
        fun setMirPayNotificationsEnabled(enabled: Boolean) {
            context.getSharedPreferences(NotificationService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(NotificationService.KEY_MIRPAY_ENABLED, enabled).apply()

            // Включили чтение, но доступ к уведомлениям не выдан — открываем системный экран
            if (enabled && !isNotificationListenerEnabled()) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "Разрешите SmartWallet доступ к уведомлениям",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        @JavascriptInterface
        fun isNotificationAccessGranted(): Boolean = isNotificationListenerEnabled()

        // Запрос к ИИ. Провайдеры перебираются по порядку: OpenRouter → Gemini → DeepSeek.
        // Первый успешный ответ уходит в JS через window.onDeepSeekAnswer.
        @JavascriptInterface
        fun askAI(requestId: String, prompt: String) {
            val providers = buildList {
                if (BuildConfig.OPENROUTER_API_KEY.isNotBlank()) {
                    add(AiProvider("OpenRouter") { askOpenRouter(BuildConfig.OPENROUTER_API_KEY, prompt) })
                }
                if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                    add(AiProvider("Gemini") { askGemini(BuildConfig.GEMINI_API_KEY, prompt) })
                }
                if (BuildConfig.DEEPSEEK_API_KEY.isNotBlank()) {
                    add(AiProvider("DeepSeek") { askDeepSeekApi(BuildConfig.DEEPSEEK_API_KEY, prompt) })
                }
            }

            if (providers.isEmpty()) {
                callJsError(requestId, "API-ключи ИИ не настроены")
                return
            }

            Thread {
                val errors = mutableListOf<String>()
                for (provider in providers) {
                    try {
                        val answer = provider.call().trim()
                        if (answer.isNotEmpty()) {
                            callJsAnswer(requestId, answer)
                            return@Thread
                        }
                        errors.add("${provider.name}: пустой ответ")
                    } catch (e: Exception) {
                        errors.add("${provider.name}: ${e.message ?: "ошибка запроса"}")
                    }
                }
                callJsError(requestId, errors.joinToString("; "))
            }.start()
        }

        @JavascriptInterface
        fun startScan() {
            Toast.makeText(context, "Открываю камеру для сканирования...", Toast.LENGTH_SHORT).show()

            // Пока это заглушка, имитирующая успешное распознавание чека через 2 секунды.
            // Позже мы прикрутим сюда реальную библиотеку для камеры!
            Handler(Looper.getMainLooper()).postDelayed({
                val mockJson = """{"amount": 1250.50, "store": "Пятёрочка"}"""
                val encodedJson = URLEncoder.encode(mockJson, "UTF-8").replace("+", "%20")
                runOnUiThread {
                    webView.evaluateJavascript("javascript:handleScannedReceiptEncoded('$encodedJson');", null)
                }
            }, 2000)
        }
    }

    // === КЛАСС ДЛЯ ЭКРАНА АКТИВАЦИИ ===
    inner class LicenseInterface(private val context: Context) {
        @JavascriptInterface
        fun getState(): String {
            val json = JSONObject().put("deviceCode", licenseManager.deviceCode)
            when (val status = licenseManager.status()) {
                is LicenseManager.Status.Licensed -> json.put("status", "licensed")
                is LicenseManager.Status.Trial -> json.put("status", "trial").put("daysLeft", status.daysLeft)
                LicenseManager.Status.TrialExpired -> json.put("status", "trial_expired")
                LicenseManager.Status.LicenseExpired -> json.put("status", "license_expired")
            }
            return json.toString()
        }

        @JavascriptInterface
        fun activate(key: String): String {
            val license = licenseManager.activate(key)
                ?: return JSONObject()
                    .put("ok", false)
                    .put("message", "Ключ не подходит к этому устройству или срок его действия истёк")
                    .toString()

            runOnUiThread {
                Toast.makeText(context, "SmartWallet активирован ${licenseManager.expiryText(license)}", Toast.LENGTH_LONG).show()
                webView.loadUrl(MAIN_URL)
            }
            return JSONObject().put("ok", true).put("message", "Готово! Открываю приложение…").toString()
        }

        @JavascriptInterface
        fun continueTrial() {
            if (licenseManager.status() !is LicenseManager.Status.Trial) return
            licenseManager.markTrialPromptShown()
            runOnUiThread { webView.loadUrl(MAIN_URL) }
        }

        @JavascriptInterface
        fun copyDeviceCode() {
            copyText(licenseManager.deviceCode)
            runOnUiThread { Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show() }
        }

        private fun copyText(text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SmartWallet", text))
        }

        @JavascriptInterface
        fun readClipboard(): String {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        }

        @JavascriptInterface
        fun openBuy() {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LicenseManager.BUY_URL)))
            } catch (e: ActivityNotFoundException) {
                copyText(LicenseManager.BUY_URL)
                runOnUiThread { Toast.makeText(context, "Ссылка на группу скопирована", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private class AiException(message: String) : Exception(message)

    private class AiProvider(val name: String, val call: () -> String)

    private fun askOpenRouter(key: String, prompt: String): String {
        val connection = URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("HTTP-Referer", "https://smartwallet.app")
        connection.setRequestProperty("X-Title", "SmartWallet")

        val body = JSONObject().apply {
            put("model", OPENROUTER_MODEL)
            put("temperature", 0.4)
            put("max_tokens", 700)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", aiSystemPrompt()))
                    put(JSONObject().put("role", "user").put("content", prompt))
                }
            )
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val (code, raw) = readResponse(connection)

        if (code !in 200..299) {
            throw AiException(
                when (code) {
                    400 -> "OpenRouter: неверный запрос"
                    401, 403 -> "OpenRouter: неверный API-ключ"
                    402 -> "OpenRouter: закончились кредиты"
                    404 -> "OpenRouter: модель недоступна"
                    429 -> "OpenRouter: лимит бесплатных запросов исчерпан"
                    502, 503 -> "OpenRouter: модель перегружена"
                    else -> "OpenRouter вернул HTTP $code"
                }
            )
        }

        val choices = JSONObject(raw).optJSONArray("choices")
            ?: throw AiException("OpenRouter вернул пустой ответ")
        if (choices.length() == 0) throw AiException("OpenRouter не дал ответа")

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw AiException("OpenRouter вернул пустой ответ")

        val text = message.optString("content", "").trim()
        if (text.isEmpty()) {
            val reasoning = message.optString("reasoning", "").trim()
            if (reasoning.isEmpty()) throw AiException("OpenRouter вернул пустой ответ")
            return reasoning
        }
        return text
    }

    private fun aiSystemPrompt(): String =
        "Ты финансовый помощник в приложении SmartWallet. " +
            "Отвечай по-русски, кратко (до 120 слов), без markdown и списков. " +
            "Опирайся только на переданные цифры, не выдумывай доходы и траты. " +
            "Давай конкретный практичный совет."

    private fun readResponse(connection: HttpURLConnection): Pair<Int, String> {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return code to raw
    }

    private fun askGemini(key: String, prompt: String): String {
        val connection = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        ).openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-goog-api-key", key)

        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", aiSystemPrompt())))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 700)
                }
            )
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val (code, raw) = readResponse(connection)

        if (code !in 200..299) {
            throw AiException(
                when (code) {
                    400 -> "Неверный запрос к Gemini (проверьте API-ключ)"
                    403 -> "Ключ Gemini не имеет доступа к API"
                    404 -> "Модель Gemini недоступна для этого ключа"
                    429 -> "Лимит запросов Gemini исчерпан, попробуйте позже"
                    else -> "Gemini вернул HTTP $code"
                }
            )
        }

        val candidates = JSONObject(raw).optJSONArray("candidates")
            ?: throw AiException("Gemini вернул пустой ответ")
        if (candidates.length() == 0) {
            throw AiException("Gemini не дал ответа (возможно, сработал фильтр)")
        }

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw AiException("Gemini вернул пустой ответ")

        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought", false)) continue
            val text = part.optString("text", "")
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(text)
            }
        }

        if (builder.isEmpty()) throw AiException("Gemini вернул пустой ответ")
        return builder.toString()
    }

    private fun askDeepSeekApi(key: String, prompt: String): String {
        val connection = URL("https://api.deepseek.com/chat/completions")
            .openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $key")

        val body = JSONObject().apply {
            put("model", DEEPSEEK_MODEL)
            put("temperature", 0.4)
            put("max_tokens", 500)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", aiSystemPrompt()))
                    put(JSONObject().put("role", "user").put("content", prompt))
                }
            )
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val (code, raw) = readResponse(connection)

        if (code !in 200..299) {
            throw AiException(
                when (code) {
                    401 -> "Неверный API-ключ DeepSeek"
                    402 -> "На балансе DeepSeek недостаточно средств"
                    429 -> "Слишком много запросов к DeepSeek, попробуйте позже"
                    else -> "DeepSeek вернул HTTP $code"
                }
            )
        }

        return JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    private fun callJsAnswer(requestId: String, text: String) {
        webView.post {
            webView.evaluateJavascript(
                "window.onDeepSeekAnswer(${JSONObject.quote(requestId)}, ${JSONObject.quote(text)});",
                null
            )
        }
    }

    private fun callJsError(requestId: String, message: String) {
        webView.post {
            webView.evaluateJavascript(
                "window.onDeepSeekError(${JSONObject.quote(requestId)}, ${JSONObject.quote(message)});",
                null
            )
        }
    }

    companion object {
        private const val MAIN_URL = "file:///android_asset/index.html"
        private const val ACTIVATION_URL = "file:///android_asset/activation.html"
        private const val OPENROUTER_MODEL = "google/gemma-4-31b-it:free"
        private const val GEMINI_MODEL = "gemini-3.8-flash"
        private const val DEEPSEEK_MODEL = "deepseek-chat"
    }
}
