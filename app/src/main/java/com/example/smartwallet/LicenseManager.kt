package com.example.smartwallet

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Состояние лицензии: 7 дней бесплатно с первого запуска, дальше нужен ключ из Telegram.
 */
class LicenseManager(context: Context) {

    sealed class Status {
        data class Licensed(val license: LicenseVerifier.License) : Status()
        data class Trial(val daysLeft: Int) : Status()
        object TrialExpired : Status()
        object LicenseExpired : Status()
    }

    private val prefs = context.getSharedPreferences("SmartWalletLicense", Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    val deviceCode: String = LicenseVerifier.deviceCodeFrom(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    )

    /** Текущее время, которое не уходит назад при переводе часов на устройстве. */
    private fun trustedNow(): Long {
        val now = System.currentTimeMillis()
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        val trusted = maxOf(now, lastSeen)
        if (trusted != lastSeen) prefs.edit().putLong(KEY_LAST_SEEN, trusted).apply()
        return trusted
    }

    private fun trustedToday(): Long = TimeUnit.MILLISECONDS.toDays(trustedNow())

    fun status(): Status {
        val savedKey = prefs.getString(KEY_LICENSE, null)
        if (savedKey != null) {
            val license = LicenseVerifier.parse(savedKey, deviceCode)
            if (license != null) {
                return if (license.isLifetime || trustedToday() <= license.expiryEpochDay) {
                    Status.Licensed(license)
                } else {
                    Status.LicenseExpired
                }
            }
        }

        val now = trustedNow()
        var firstRun = prefs.getLong(KEY_FIRST_RUN, 0L)
        if (firstRun == 0L) {
            firstRun = now
            prefs.edit().putLong(KEY_FIRST_RUN, firstRun).apply()
        }
        val daysUsed = TimeUnit.MILLISECONDS.toDays(now - firstRun).toInt()
        val daysLeft = TRIAL_DAYS - daysUsed
        return if (daysLeft > 0) Status.Trial(daysLeft) else Status.TrialExpired
    }

    /** Проверяет и сохраняет ключ. Возвращает лицензию или null, если ключ не подходит. */
    fun activate(key: String): LicenseVerifier.License? {
        val license = LicenseVerifier.parse(key, deviceCode) ?: return null
        if (!license.isLifetime && trustedToday() > license.expiryEpochDay) return null
        prefs.edit().putString(KEY_LICENSE, key.filterNot { it.isWhitespace() }).apply()
        return license
    }

    /** Сохранённый ключ активации (нужен ИИ-прокси, чтобы дать купившим больший лимит). */
    fun savedKey(): String? = prefs.getString(KEY_LICENSE, null)

    /**
     * Экран покупки показываем, если пользоваться нельзя,
     * а во время пробного периода — один раз в день, чтобы напоминать о ключе.
     */
    fun shouldShowActivation(): Boolean = when (status()) {
        is Status.Licensed -> false
        is Status.Trial -> prefs.getLong(KEY_TRIAL_PROMPT_DAY, -1L) != trustedToday()
        else -> true
    }

    fun markTrialPromptShown() {
        prefs.edit().putLong(KEY_TRIAL_PROMPT_DAY, trustedToday()).apply()
    }

    fun expiryText(license: LicenseVerifier.License): String =
        if (license.isLifetime) "навсегда"
        else "до " + LocalDate.ofEpochDay(license.expiryEpochDay.toLong()).let {
            "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year)
        }

    companion object {
        const val TRIAL_DAYS = 7
        const val BUY_URL = "https://t.me/+vVnx3FwEgZM0MTYy"

        private const val KEY_LICENSE = "license_key"
        private const val KEY_FIRST_RUN = "first_run_ms"
        private const val KEY_LAST_SEEN = "last_seen_ms"
        private const val KEY_TRIAL_PROMPT_DAY = "trial_prompt_day"
    }
}
