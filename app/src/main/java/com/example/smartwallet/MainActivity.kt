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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var licenseManager: LicenseManager
    private var isWebViewLoaded = false

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

        // ВОТ ОН: Мост между JavaScript и нативной камерой Android
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

        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pushReceiver)
    }

    // === КЛАСС ДЛЯ РАБОТЫ СКАНЕРА ЧЕКОВ ===
    inner class AndroidScannerInterface(private val context: Context) {
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

    companion object {
        private const val MAIN_URL = "file:///android_asset/index.html"
        private const val ACTIVATION_URL = "file:///android_asset/activation.html"
    }
}