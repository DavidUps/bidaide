package com.bidaide.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbShellParsingTest {
    @Test
    fun `launcher activity is the last component line`() {
        assertEquals("com.app/.MainActivity", AdbShell.parseLauncherActivity("priority=0 preferredOrder=0 match=0x108000\ncom.app/.MainActivity\n"))
        assertNull(AdbShell.parseLauncherActivity("No activity found"))
    }

    @Test
    fun `requested permissions section is extracted`() {
        val dumpsys = """
            Packages:
              Package [com.app] (abc):
                declared permissions:
                  com.app.DYNAMIC_RECEIVER: prot=signature
                requested permissions:
                  android.permission.INTERNET
                  android.permission.CAMERA
                  android.permission.POST_NOTIFICATIONS: restricted=true
                install permissions:
                  android.permission.INTERNET: granted=true
        """.trimIndent()
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS"),
            AdbShell.parseRequestedPermissions(dumpsys),
        )
    }
}
