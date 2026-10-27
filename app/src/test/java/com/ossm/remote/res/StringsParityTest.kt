package com.ossm.remote.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * FR (default, `values/`) and EN (`values-en/`) string resources must stay in sync:
 * same keys, same format placeholders. Parses every real `strings*.xml` (including
 * `strings_ui_polish.xml`), no Android runtime.
 */
class StringsParityTest {
    private data class Entry(val text: String, val translatable: Boolean)

    /** Every `strings*.xml` of the folder (strings.xml, strings_ui_polish.xml, ...), merged. */
    private fun resourceFiles(folder: String): List<File> {
        val dir = listOf(File("src/main/res/$folder"), File("app/src/main/res/$folder"))
            .firstOrNull(File::isDirectory)
            ?: error("res/$folder not found from ${File(".").absoluteFile}")
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .sortedBy(File::getName)
        assertTrue("No strings*.xml in $folder", files.isNotEmpty())
        return files
    }

    private fun load(folder: String): Map<String, Entry> {
        val entries = linkedMapOf<String, Entry>()
        for (file in resourceFiles(folder)) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.documentElement.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                assertTrue("Duplicate string '$name' in $folder (${file.name})", name !in entries)
                entries[name] = Entry(
                    text = element.textContent,
                    translatable = element.getAttribute("translatable") != "false",
                )
            }
        }
        return entries
    }

    private val placeholder = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

    private fun placeholders(text: String): List<String> =
        placeholder.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test
    fun `English declares exactly the translatable French keys`() {
        val french = load("values").filterValues { it.translatable }.keys
        val english = load("values-en").keys

        assertEquals("Missing in values-en", emptySet<String>(), french - english)
        assertEquals("Orphan in values-en", emptySet<String>(), english - french)
    }

    @Test
    fun `French and English use the same format placeholders`() {
        val french = load("values")
        val english = load("values-en")
        val mismatches = english.keys.intersect(french.keys).filter { key ->
            placeholders(french.getValue(key).text) != placeholders(english.getValue(key).text)
        }

        assertEquals("Placeholder mismatch for $mismatches", emptyList<String>(), mismatches)
    }

    @Test
    fun `no translated value is blank`() {
        val blank = load("values-en").filterValues { it.text.isBlank() }.keys

        assertEquals(emptySet<String>(), blank)
    }
}
