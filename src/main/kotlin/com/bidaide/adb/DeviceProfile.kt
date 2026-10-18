package com.bidaide.adb

import com.android.ddmlib.IDevice
import com.bidaide.BidaideBundle
import com.bidaide.settings.BidaideSettings

/**
 * A named set of device settings applied in one go, defined in Settings as `Name = key=value, key=value`.
 * Unlike the toggles, a profile always leaves the device in a known state.
 */
class DeviceProfile(val name: String, val settings: Map<String, String>) {
    /** Per-app language is the only setting that concerns the app rather than the device. */
    val needsPackage: Boolean get() = LOCALE in settings

    /** First problem found in the definition, or null when every key and value is understood. */
    fun problem(): String? {
        for ((key, value) in settings) {
            val ok = when (key) {
                in ON_OFF_KEYS -> value in ON_OFF
                FONT -> value.toFloatOrNull()?.let { it in 0.5f..3f } == true
                ROTATION -> value in ROTATIONS
                LOCALE -> value.isNotBlank()
                else -> return BidaideBundle.message("adb.profile.unknownKey", key)
            }
            if (!ok) return BidaideBundle.message("adb.profile.badValue", value, key)
        }
        return if (settings.isEmpty()) BidaideBundle.message("adb.profile.empty") else null
    }

    /** Blocking. Returns what was applied, for the notification. */
    fun apply(device: IDevice, packageName: String?): String {
        problem()?.let { throw AdbException(BidaideBundle.message("adb.profile.invalid", name, it)) }
        for ((key, value) in settings) {
            val on = value == "on"
            when (key) {
                DARK -> AdbShell.setNightMode(device, on)
                ANIMATIONS -> AdbShell.setAnimations(device, on)
                TOUCHES -> AdbShell.putSetting(device, "system", "show_touches", if (on) "1" else "0")
                DONT_KEEP -> AdbShell.putSetting(device, "global", "always_finish_activities", if (on) "1" else "0")
                WIFI -> AdbShell.setService(device, "wifi", on)
                DATA -> AdbShell.setService(device, "data", on)
                FONT -> AdbShell.putSetting(device, "system", "font_scale", value)
                ROTATION -> AdbShell.setRotation(device, if (value == "auto") null else value == "landscape")
                LOCALE -> AdbShell.setAppLocale(device, packageName.orEmpty(), if (value == "system") "" else value)
            }
        }
        return BidaideBundle.message("adb.profile.applied", name, settings.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    companion object {
        private const val DARK = "dark"
        private const val ANIMATIONS = "animations"
        private const val TOUCHES = "touches"
        private const val DONT_KEEP = "dontkeep"
        private const val WIFI = "wifi"
        private const val DATA = "data"
        private const val FONT = "font"
        private const val ROTATION = "rotation"
        private const val LOCALE = "locale"

        private val ON_OFF_KEYS = setOf(DARK, ANIMATIONS, TOUCHES, DONT_KEEP, WIFI, DATA)
        private val ON_OFF = setOf("on", "off")
        private val ROTATIONS = setOf("portrait", "landscape", "auto")

        fun parse(text: String): List<DeviceProfile> = BidaideSettings.namedLines(text).map { (name, definition) ->
            val settings = LinkedHashMap<String, String>()
            for (pair in definition.split(',')) {
                if (pair.isBlank()) continue
                // Keys and on/off values are case-insensitive; a locale tag such as es-ES keeps its case.
                val key = pair.substringBefore('=').trim().lowercase()
                val value = pair.substringAfter('=', "").trim()
                settings[key] = if (key == LOCALE && value.lowercase() != "system") value else value.lowercase()
            }
            DeviceProfile(name, settings)
        }
    }
}
