package com.example.smartwallet

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Проверка ключей активации без интернета.
 *
 * Ключ = "SW-" + base64url( [версия][тариф][срок, 2 байта][подпись ECDSA P-256] ).
 * Подписывается строка "SW1|<код устройства>|<тариф>|<срок>", поэтому ключ
 * работает только на том устройстве, для которого был выпущен.
 * Закрытый ключ есть только у продавца (tools/KeyGen.java), в приложении — открытый.
 */
object LicenseVerifier {

    const val KEY_PREFIX = "SW-"
    private const val FORMAT_VERSION: Byte = 1

    const val PLAN_LIFETIME = 'L'
    const val PLAN_TIMED = 'T'

    /** Значение срока для бессрочных ключей. */
    const val LIFETIME_EXPIRY = 0xFFFF

    // Без похожих символов (0/O, 1/I), чтобы код было легко продиктовать
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Открытый ключ продавца (X.509, base64). Пара создаётся командой `java KeyGen.java init`. */
    const val PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpOI5SupTzBC9lRoucOwYF5yzPqzHqUlLG88CvtL/4gqlweFmGe3bF1AJoPXqN1OU4hR18AxplDeGIrVdgxjTTg=="

    data class License(val plan: Char, val expiryEpochDay: Int) {
        val isLifetime: Boolean get() = expiryEpochDay == LIFETIME_EXPIRY
    }

    /** Короткий код устройства вида XXXX-XXXX-XXXX из ANDROID_ID. */
    fun deviceCodeFrom(androidId: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("SmartWallet:$androidId".toByteArray())
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        var i = 0
        while (sb.length < 12) {
            buffer = (buffer shl 8) or (hash[i++].toInt() and 0xFF)
            bits += 8
            while (bits >= 5 && sb.length < 12) {
                bits -= 5
                sb.append(CODE_ALPHABET[(buffer shr bits) and 31])
            }
        }
        return sb.chunked(4).joinToString("-")
    }

    fun signedMessage(deviceCode: String, plan: Char, expiryEpochDay: Int): ByteArray =
        "SW1|$deviceCode|$plan|$expiryEpochDay".toByteArray(Charsets.UTF_8)

    fun loadPublicKey(base64: String = PUBLIC_KEY_BASE64): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    /** Возвращает лицензию, если ключ подписан продавцом для этого устройства, иначе null. */
    fun parse(key: String, deviceCode: String, publicKey: PublicKey = loadPublicKey()): License? {
        val cleaned = key.filterNot { it.isWhitespace() }
        if (!cleaned.startsWith(KEY_PREFIX, ignoreCase = true)) return null

        val bytes = try {
            Base64.getUrlDecoder().decode(cleaned.substring(KEY_PREFIX.length))
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size < 12 || bytes[0] != FORMAT_VERSION) return null

        val plan = bytes[1].toInt().toChar()
        if (plan != PLAN_LIFETIME && plan != PLAN_TIMED) return null
        val expiry = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val signature = bytes.copyOfRange(4, bytes.size)

        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(signedMessage(deviceCode, plan, expiry))
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
        return if (valid) License(plan, expiry) else null
    }
}
