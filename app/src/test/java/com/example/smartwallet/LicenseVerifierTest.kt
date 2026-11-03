package com.example.smartwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Ключи ниже выпущены tools/KeyGen.java для выдуманного устройства ABCD-EFGH-JKLM. */
class LicenseVerifierTest {

    private val device = "ABCD-EFGH-JKLM"
    private val lifetimeKey =
        "SW-AUz__zBGAiEAprfWF69EeuaRVginZPBmAhY1Lxqw6l4EPsqbV5FRkRsCIQDX6rlH_qqabE7907xQ9OKa0JcmRLNXOgj_lxmRzunK9Q"
    private val timedKey =
        "SW-AVRRNjBEAiBd1sZFocLm0CUurQUuW-mAaUcs2k6vcG-Tkjir4AjHxgIgfu3mPBWOvZKRmvV7ADV0khyvBqg0Z6hdMBwIDGxV1yU"

    @Test
    fun lifetimeKeyFromKeyGenIsValid() {
        val license = LicenseVerifier.parse(lifetimeKey, device)!!
        assertEquals(LicenseVerifier.PLAN_LIFETIME, license.plan)
        assertTrue(license.isLifetime)
    }

    @Test
    fun timedKeyCarriesExpiryDate() {
        val license = LicenseVerifier.parse(timedKey, device)!!
        assertEquals(LicenseVerifier.PLAN_TIMED, license.plan)
        assertEquals(LocalDate.of(2026, 12, 3).toEpochDay().toInt(), license.expiryEpochDay)
    }

    @Test
    fun keyCopiedWithSpacesAndLineBreaksStillWorks() {
        val messy = "  " + lifetimeKey.chunked(20).joinToString("\n ") + "\n"
        assertTrue(LicenseVerifier.parse(messy, device) != null)
    }

    @Test
    fun keyDoesNotWorkOnAnotherDevice() {
        assertNull(LicenseVerifier.parse(lifetimeKey, "ZZZZ-EFGH-JKLM"))
    }

    @Test
    fun tamperedKeyIsRejected() {
        // Пытаемся превратить ключ на 30 дней в бессрочный, поменяв байты тарифа
        val forged = timedKey.replaceRange(3, 7, lifetimeKey.substring(3, 7))
        assertNull(LicenseVerifier.parse(forged, device))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(LicenseVerifier.parse("", device))
        assertNull(LicenseVerifier.parse("hello", device))
        assertNull(LicenseVerifier.parse("SW-!!!", device))
        assertNull(LicenseVerifier.parse("SW-AAAA", device))
    }

    @Test
    fun deviceCodeIsStableAndReadable() {
        val code = LicenseVerifier.deviceCodeFrom("9774d56d682e549c")
        assertEquals(code, LicenseVerifier.deviceCodeFrom("9774d56d682e549c"))
        assertTrue(code.matches(Regex("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")))
        assertNotEquals(code, LicenseVerifier.deviceCodeFrom("0123456789abcdef"))
    }
}
