package com.example.smartwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ключи, которые выдаёт tools/license-server/worker.js (WebCrypto), должны приниматься приложением.
 * Пара ключей здесь одноразовая, только для теста — с боевым ключом она не связана.
 */
class LicenseServerCompatTest {

    private val testPublicKey = LicenseVerifier.loadPublicKey(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqajum0QYl5eJe3kKIeHwaljyKtrAWRwPyoWGrvRsXXRXQnIkIIiAleYfvtU0wE5yeicLxa40m49YHH8Tn3UtBA=="
    )

    // Выдан сервером на код SWT-AABR-PQ23-9ZCG (партия A, 30 дней) для устройства ABCD-EFGH-JKLM
    private val serverKey =
        "SW-AVRROTBGAiEA1umnqac7I9hW_Ca-89Zy-a-e7mIYCCJODs3H25EKpbECIQCl6i8Z1FJLygPQ2wMnVImap-7sJ3-Fyr8ytyb7XzahbA"

    @Test
    fun serverKeyIsAcceptedForItsDevice() {
        val license = LicenseVerifier.parse(serverKey, "ABCD-EFGH-JKLM", testPublicKey)
        assertNotNull(license)
        assertEquals('T', license!!.plan)
        assertEquals(20793, license.expiryEpochDay)
    }

    @Test
    fun serverKeyIsRejectedOnAnotherDevice() {
        assertNull(LicenseVerifier.parse(serverKey, "ZZZZ-EFGH-JKLM", testPublicKey))
    }
}
