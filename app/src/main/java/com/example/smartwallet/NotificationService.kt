package com.example.smartwallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray

class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        showToast("✅ Служба SmartWallet подключена!")
        createNotificationChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification?) {
        try {
            val packageName = sbn?.packageName ?: return

            // КРИТИЧЕСКИ ВАЖНО: Игнорируем уведомления от самого SmartWallet
            if (packageName == applicationContext.packageName) return

            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            val fullText = "$title $text $bigText"
            val pkg = packageName.lowercase()
            val textLower = fullText.lowercase()

            // Игнорируем Т-Инвестиции и инфо посты
            if (textLower.contains("инвестиции") || textLower.contains("invest") || textLower.contains("t-investments") || textLower.contains("брокер")) return

            // Ловим ТОЛЬКО нужные банки и платежные системы (Т-Банк и Mir Pay)
            if (pkg.contains("mirpay") || pkg.contains("tinkoff") || pkg.contains("tcsbank") || pkg.contains("t-bank")) {

                // Сохраняем пуш в базу
                val prefs = applicationContext.getSharedPreferences("SmartWalletPrefs", Context.MODE_PRIVATE)
                val savedPushes = prefs.getString("pending_pushes", "[]")
                val jsonArray = JSONArray(savedPushes)
                jsonArray.put(fullText)
                prefs.edit().putString("pending_pushes", jsonArray.toString()).apply()

                // Транслируем внутрь приложения
                val intent = Intent("com.example.smartwallet.PUSH_RECEIVED")
                intent.setPackage(applicationContext.packageName)
                intent.putExtra("pushText", fullText)
                sendBroadcast(intent)

                // Ждем 1 секунду и отправляем своё уведомление пользователю
                Handler(Looper.getMainLooper()).postDelayed({
                    sendSmartWalletNotification()
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e("SmartWallet", "Ошибка чтения пуша: ${e.message}")
        }
    }

    // Создаем системный канал для Android 8+
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "smartwallet_channel",
                "Напоминания о расходах",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // Формируем и показываем наше уведомление
    private fun sendSmartWalletNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "smartwallet_channel")
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setContentTitle("Новая операция 💰")
            .setContentText("Желаете внести расходы?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }
}