package com.example.smartwallet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Фоновая проверка обновлений раз в 6 часов, даже если приложение не открывали.
 * Новый интерфейс ставится тихо. Новый APK скачивается и ставится через PackageInstaller:
 * без вопросов, если Android это разрешает, иначе приходит уведомление «Нажмите, чтобы установить».
 */
class UpdateJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                runCheck(applicationContext)
            } catch (e: Exception) {
                // Нет сети или релиза — попробуем в следующий раз
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4200
        private const val PREFS = "SmartWalletUpdates"
        private const val KEY_NOTIFIED_VERSION = "notified_version_code"

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(6))
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        fun runCheck(context: Context) {
            val updates = UpdateManager(context, WebBundleManager(context))
            val manifest = updates.check(force = true) ?: return
            val versionCode = manifest.optInt("versionCode")
            val versionName = manifest.optString("versionName")

            if (SelfInstaller.canInstall(context)) {
                val apk = updates.downloadApk(manifest) { }
                SelfInstaller.install(context, apk, versionName, fromUi = false)
                return
            }

            // Установка из SmartWallet ещё не разрешена — напоминаем один раз на версию
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_NOTIFIED_VERSION, 0) == versionCode) return
            prefs.edit().putInt(KEY_NOTIFIED_VERSION, versionCode).apply()
            SelfInstaller.notify(
                context,
                "Доступно обновление SmartWallet $versionName",
                manifest.optString("changelog").ifBlank { "Откройте приложение, чтобы обновить." }
            )
        }
    }
}
