package com.vintagelab.camera.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every user-visible string lives in resources and has a French translation (SPEC 7.5: en + fr only). */
class StringResourcesTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }

    private class Entry(val name: String, val translatable: Boolean, val text: String)

    private fun load(dir: String): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.associate {
            it.getAttribute("name") to Entry(it.getAttribute("name"), it.getAttribute("translatable") != "false", it.textContent)
        }
    }

    @Test
    fun everyTranslatableKeyExistsInFrench() {
        val en = load("values")
        val fr = load("values-fr")
        val missing = en.values.filter { it.translatable && it.name !in fr }.map { it.name }
        assertTrue("missing in values-fr: $missing", missing.isEmpty())
        val extra = fr.keys.filter { it !in en }
        assertTrue("extra in values-fr: $extra", extra.isEmpty())
        assertFalse(File(res, "values-ar").exists())
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        val en = load("values")
        val fr = load("values-fr")
        val re = Regex("%\\d\\$[a-z]")
        for (e in en.values.filter { it.translatable }) {
            val a = re.findAll(e.text).map { it.value }.sorted().toList()
            val b = re.findAll(fr.getValue(e.name).text).map { it.value }.sorted().toList()
            assertEquals("placeholders of ${e.name}", a, b)
        }
    }

    @Test
    fun everyLookNameHasAString_andOwnToggleLabelsAreNotSystemOnes() {
        val en = load("values")
        val presets = File(res, "../assets/presets/presets.json").readText()
        val names = Regex("\"displayNameRes\"\\s*:\\s*\"([^\"]+)\"").findAll(presets).map { it.groupValues[1] }.toList()
        assertTrue(names.isNotEmpty())
        for (n in names) assertTrue("no string for $n", n in en)
        // Names that collide with library resources (androidx state_on / state_off) would pick up system translations.
        assertFalse("state_on" in en)
        assertFalse("state_off" in en)
        assertTrue("toggle_on" in en && "toggle_off" in en)
    }

    @Test
    fun localeConfigListsEnglishAndFrenchOnly() {
        val text = File(res, "xml/locales_config.xml").readText()
        val tags = Regex("android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("en", "fr"), tags)
        assertTrue(File(res, "../AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""))
    }

    @Test
    fun importEditorGalleryAndNegativeStringsExistInBothLanguages() {
        val en = load("values")
        val fr = load("values-fr")
        val keys = listOf(
            "menu_import", "menu_gallery", "menu_keep_negatives", "editor_title_import", "editor_title_redevelop", "editor_save",
            "editor_saving", "editor_loading", "editor_exposure", "editor_temperature", "editor_reset", "cd_editor_preview",
            "err_import_failed", "err_negative_missing", "err_delete_failed", "msg_photo_deleted", "gallery_title",
            "gallery_empty_title", "gallery_empty_body", "gallery_open_photos", "viewer_share", "viewer_delete", "viewer_redevelop",
        )
        for (k in keys) {
            assertTrue("en $k", en[k]?.text?.isNotBlank() == true)
            assertTrue("fr $k", fr[k]?.text?.isNotBlank() == true)
            assertTrue("fr $k differs from en", fr.getValue(k).text != en.getValue(k).text || k == "gallery_title")
        }
    }
}
