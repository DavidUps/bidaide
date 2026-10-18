package com.bidaide

import com.bidaide.settings.BidaideSettings
import com.intellij.BundleBase
import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.Locale
import java.util.function.Supplier

@NonNls
const val BUNDLE = "messages.BidaideBundle"

/**
 * Every text the user sees. English is the base and the fallback; `_es`, `_fr` and `_it` translate it.
 * The language is the IDE's unless one is chosen in Settings > Tools > Bidaide.
 */
object BidaideBundle : DynamicBundle(BidaideBundle::class.java, BUNDLE) {

    /**
     * Numbers are passed as plain text: MessageFormat would otherwise group digits by locale and turn #1234
     * into #1,234 or #1.234. Null comes out as "null", as in a string template.
     */
    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): String {
        val args = params.map { if (it is Number) it.toString() else it ?: "null" }.toTypedArray()
        val locale = chosenLocale() ?: return getMessage(key, *args)
        return BundleBase.message(DynamicBundle.getResourceBundle(BidaideBundle::class.java.classLoader, BUNDLE, locale), key, *args)
    }

    @JvmStatic
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): Supplier<@Nls String> =
        Supplier { message(key, *params) }

    /** The language texts come out in, for dates and other locale-dependent formatting. */
    fun locale(): Locale = chosenLocale() ?: DynamicBundle.getLocale()

    /** The language chosen in Bidaide's settings; null when it follows the IDE (also in tests, with no application). */
    private fun chosenLocale(): Locale? {
        val application = ApplicationManager.getApplication() ?: return null
        if (application.isDisposed) return null
        return application.getService(BidaideSettings::class.java)?.language?.locale
    }
}
