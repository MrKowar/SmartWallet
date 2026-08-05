package com.example.smartwallet

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isWebViewLoaded = false

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val content = result.contents
            val regex = Regex("s=(\\d+\\.\\d{2})")
            val matchResult = regex.find(content)
            if (matchResult != null) {
                val amount = matchResult.groupValues[1].toDoubleOrNull()
                if (amount != null) {
                    val json = JSONObject()
                    json.put("amount", amount)
                    json.put("store", "Чек ФНС")
                    val encodedJson = URLEncoder.encode(json.toString(), "UTF-8").replace("+", "%20")
                    runOnUiThread {
                        webView.evaluateJavascript("javascript:handleScannedReceiptEncoded('$encodedJson');", null)
                    }
                }
            } else {
                Toast.makeText(this, "Сумма в QR коде чека не найдена", Toast.LENGTH_SHORT).show()
            }
        }
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

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // ВОТ ОН: Мост между JavaScript и нативной камерой Android
        webView.addJavascriptInterface(AndroidScannerInterface(this), "AndroidScanner")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isWebViewLoaded = true

                // Даем интерфейсу 1.5 секунды на полную прогрузку
                Handler(Looper.getMainLooper()).postDelayed({
                    checkPendingPushes()
                }, 1500)
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

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
            runOnUiThread {
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                options.setPrompt("Отсканируйте QR-код чека")
                options.setCameraId(0)
                options.setBeepEnabled(true)
                options.setBarcodeImageEnabled(true)
                barcodeLauncher.launch(options)
            }
        }
    }
}