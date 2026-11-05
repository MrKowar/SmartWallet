package com.example.smartwallet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Установка обновления через PackageInstaller.
 *
 * С Android 12 приложение, которое само себя установило (installer of record), может ставить
 * свои обновления без подтверждения пользователя (USER_ACTION_NOT_REQUIRED). Первую установку
 * через SmartWallet Android всё равно попросит подтвердить — дальше обновления ставятся тихо.
 * Если подтверждение нужно, а приложение в фоне — показываем уведомление «Нажмите, чтобы установить».
 */
object SelfInstaller {

    const val CHANNEL_ID = "smartwallet_updates"
    private const val NOTIFICATION_ID = 4201
    private const val EXTRA_VERSION = "versionName"
    private const val EXTRA_FROM_UI = "fromUi"

    /** Можно ли сейчас ставить обновления (пользователь разрешил «установку из этого источника»). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, apk: File, versionName: String, fromUi: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                // SmartWallet становится владельцем обновлений — следующие версии ставятся без вопросов
                setRequestUpdateOwnership(true)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("SmartWallet.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_VERSION, versionName)
                .putExtra(EXTRA_FROM_UI, fromUi)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Обновления SmartWallet", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Уведомление об обновлении. [action] — что откроется по нажатию (по умолчанию само приложение). */
    fun notify(context: Context, title: String, text: String, action: Intent? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val intent = action ?: Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val version = intent.getStringExtra(EXTRA_VERSION) ?: ""
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.getBooleanExtra(EXTRA_FROM_UI, false)) {
                        // Пользователь сам нажал «Обновить» — сразу показываем системное окно
                        context.startActivity(confirm)
                    } else {
                        notify(context, "Обновление SmartWallet $version готово", "Нажмите, чтобы установить.", confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    notify(context, "SmartWallet обновлён", "Установлена версия $version.")
                }
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                    if (intent.getBooleanExtra(EXTRA_FROM_UI, false)) {
                        notify(context, "Не удалось установить обновление", message)
                    }
                }
            }
        }
    }
}
