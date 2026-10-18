package com.bidaide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

class BidaideBundleTest {
    private val bundles = listOf("", "_es", "_fr", "_it").associateWith { suffix ->
        Properties().apply {
            File("src/main/resources/messages/BidaideBundle$suffix.properties").reader(Charsets.UTF_8).use { load(it) }
        }
    }
    private val base = bundles.getValue("")

    private fun placeholders(value: String): Set<String> = PLACEHOLDER.findAll(value).map { it.value }.toSet()

    @Test
    fun `every translation has exactly the keys of the English bundle`() {
        for ((suffix, bundle) in bundles) {
            assertEquals("missing in BidaideBundle$suffix", emptySet<String>(), base.stringPropertyNames() - bundle.stringPropertyNames())
            assertEquals("extra in BidaideBundle$suffix", emptySet<String>(), bundle.stringPropertyNames() - base.stringPropertyNames())
        }
    }

    @Test
    fun `every translation has the placeholders of the English text`() {
        for ((suffix, bundle) in bundles) {
            for (key in base.stringPropertyNames()) {
                assertEquals("$key in BidaideBundle$suffix", placeholders(base.getProperty(key)), placeholders(bundle.getProperty(key)))
            }
        }
    }

    @Test
    fun `apostrophes are escaped for MessageFormat only where there are parameters`() {
        for ((suffix, bundle) in bundles) {
            for (key in bundle.stringPropertyNames()) {
                val value = bundle.getProperty(key)
                val where = "$key in BidaideBundle$suffix: $value"
                if (placeholders(value).isEmpty()) {
                    assertTrue(where, "''" !in value)
                } else {
                    assertTrue(where, '\'' !in value.replace("''", "").replace("'{'", "").replace("'}'", ""))
                    MessageFormat(value).format(arrayOf<Any>("a", "b", "c", "d", "e", "f"))
                }
                // A lone & would be taken for a mnemonic marker.
                assertTrue(where, '&' !in value)
            }
        }
    }

    @Test
    fun `a chosen language is used, with English for the languages there is no translation for`() {
        fun text(tag: String) = BidaideBundle.message(Locale.forLanguageTag(tag), "settings.gh.noSession", "/bin/gh")
        assertEquals("/bin/gh found, but not signed in. Run: gh auth login", text("en"))
        assertEquals("/bin/gh encontrado, pero sin sesión. Ejecuta: gh auth login", text("es"))
        // The doubled apostrophe of MessageFormat comes out single.
        assertEquals("/bin/gh trouvé, mais aucune session. Exécutez : gh auth login", text("fr"))
        assertEquals("Impossible d'ouvrir db", BidaideBundle.message(Locale.FRENCH, "adb.db.cannotOpen", "db"))
        assertEquals("/bin/gh trovato, ma senza sessione. Esegui: gh auth login", text("it"))
        assertEquals(text("en"), text("de"))
        // Without an application (and so without settings) texts follow the IDE, which in tests is English.
        assertEquals(text("en"), BidaideBundle.message("settings.gh.noSession", "/bin/gh"))
        assertEquals(Locale.ENGLISH, BidaideBundle.locale())
        // Numbers are not grouped by locale.
        assertEquals("Merging PR #12345", BidaideBundle.message("github.pr.merge.task", 12345))
    }

    @Test
    fun `every key used in the code and in the plugin descriptors exists, and every key is used`() {
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        val used = KEY_IN_CODE.findAll(sources).map { it.groupValues[1] }.toMutableSet()
        // plugin.xml and the optional descriptors it loads, such as bidaide-android.xml.
        val pluginXml = File("src/main/resources/META-INF").listFiles { file -> file.extension == "xml" }!!.joinToString("\n") { it.readText() }
        used += KEY_IN_XML.findAll(pluginXml).map { it.groupValues[1] }
        used += ACTION_IN_XML.findAll(pluginXml).map { "action.${it.groupValues[1]}.text" }
        used += GROUP_IN_XML.findAll(pluginXml).filter { "class=" !in it.value }.map { "group.${it.groupValues[1]}.text" }
        used += TOOL_WINDOW_IN_XML.findAll(pluginXml).map { "toolwindow.stripe.${it.groupValues[1]}" }

        assertEquals("used but missing", emptySet<String>(), used - base.stringPropertyNames())
        // Some keys are chosen in a variable before the message call; any literal naming a key counts as a use.
        val named = LITERAL.findAll(sources).map { it.groupValues[1] }.toSet()
        // Action descriptions are looked up by the platform and by BidaideActionTexts without naming the key.
        val unused = base.stringPropertyNames() - used - named - "bundle.locale"
        assertEquals("never used", emptySet<String>(), unused.filterNot { it.startsWith("action.") && it.endsWith(".description") }.toSet())
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{\d+}""")
        val KEY_IN_CODE = Regex("""(?:message|lazyMessage)\(\s*"([^"]+)"""")
        val LITERAL = Regex(""""([A-Za-z0-9_.]+)"""")
        val KEY_IN_XML =Regex("""\skey="([^"]+)"""")
        val ACTION_IN_XML = Regex("""<action id="([^"]+)"""")
        val GROUP_IN_XML = Regex("""<group id="([^"]+)"[^>]*>""")
        val TOOL_WINDOW_IN_XML = Regex("""<toolWindow id="([^"]+)"""")
    }
}
