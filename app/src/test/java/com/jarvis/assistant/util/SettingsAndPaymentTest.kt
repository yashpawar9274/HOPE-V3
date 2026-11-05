package com.jarvis.assistant.util

import com.jarvis.assistant.util.UpiPaymentLogic.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndPaymentTest {
    @Test
    fun settingsScreensAreFoundByNaturalNames() {
        assertEquals(SystemSettingsLogic.Page.WIFI, SystemSettingsLogic.pageFor("Wi-Fi"))
        assertEquals(SystemSettingsLogic.Page.AIRPLANE, SystemSettingsLogic.pageFor("flight_mode"))
        assertEquals(SystemSettingsLogic.Page.NOTIFICATION_ACCESS, SystemSettingsLogic.pageFor("notification access"))
        assertEquals(SystemSettingsLogic.Page.DEFAULT_APPS, SystemSettingsLogic.pageFor("Default apps"))
        assertNull(SystemSettingsLogic.pageFor("quantum flux"))
    }

    @Test
    fun valuesAreParsedSafely() {
        assertEquals(70, SystemSettingsLogic.parseBrightnessPercent("70 percent"))
        assertEquals(100, SystemSettingsLogic.parseBrightnessPercent("max"))
        assertEquals(1, SystemSettingsLogic.parseBrightnessPercent("0"))
        assertNull(SystemSettingsLogic.parseBrightnessPercent("150"))
        assertEquals(127, SystemSettingsLogic.percentToBrightness(50))
        assertEquals(120, SystemSettingsLogic.parseTimeoutSeconds("2 min"))
        assertEquals(90, SystemSettingsLogic.parseTimeoutSeconds("1.5 minutes"))
        assertNull(SystemSettingsLogic.parseTimeoutSeconds("2"))
        assertNull(SystemSettingsLogic.parseTimeoutSeconds("60 minutes"))
        assertEquals(true, SystemSettingsLogic.parseOnOff("ON"))
        assertEquals(false, SystemSettingsLogic.parseOnOff("band"))
        assertNull(SystemSettingsLogic.parseOnOff("maybe"))
    }

    @Test
    fun upiPaymentIsOnlyPreparedAfterOwnerConfirmation() {
        val ok = UpiPaymentLogic.check("rahul@okaxis", "Rahul Sharma", 250.0, "lunch", true)
        assertTrue(ok is Check.Ok)
        ok as Check.Ok
        assertTrue(ok.uri.startsWith("upi://pay?pa=rahul%40okaxis"))
        assertTrue(ok.uri.contains("am=250.00") && ok.uri.contains("cu=INR"))
        assertEquals("Rs 250.00 to Rahul Sharma (rahul@okaxis)", ok.summary)
        assertTrue(UpiPaymentLogic.check("rahul@okaxis", "R", 10.0, "", false) is Check.Refused)
    }

    @Test
    fun badPaymentsAreRefused() {
        assertTrue(UpiPaymentLogic.check("not a vpa", "R", 10.0, "", true) is Check.Refused)
        assertTrue(UpiPaymentLogic.check("a1@ybl", "R", 0.0, "", true) is Check.Refused)
        assertTrue(UpiPaymentLogic.check("a1@ybl", "R", Double.NaN, "", true) is Check.Refused)
        assertTrue(UpiPaymentLogic.check("a1@ybl", "R", 100001.0, "", true) is Check.Refused)
        assertTrue(UpiPaymentLogic.check("a1@ybl", "R", 100000.0, "", true) is Check.Ok)
        assertFalse(UpiPaymentLogic.isValidUpiId("@okaxis"))
    }

    @Test
    fun payeeAndNoteCannotInjectExtraUpiFields() {
        val check = UpiPaymentLogic.check("a1@ybl", "Ra&hul=1", 5.0, "x&am=999999\nline", true) as Check.Ok
        assertFalse(check.uri.contains("am=999999"))
        assertTrue(check.uri.contains("pn=Ra%26hul%3D1"))
        assertTrue(check.uri.contains("am=5.00"))
    }
}
