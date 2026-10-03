package com.voicerewriter

import com.voicerewriter.textproc.AppContext
import com.voicerewriter.textproc.FillerWordRemover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFlowFeaturesTest {

    // ---- dictionary voice samples ----

    @Test
    fun `a mishearing of the word becomes a lowercase alias without punctuation`() {
        assertEquals("gots", VoiceSamples.aliasFrom("Gots.", "Gotz"))
        assertEquals("row hit", VoiceSamples.aliasFrom("  Row hit!  ", "Rohit"))
    }

    @Test
    fun `nothing is learned when the recognizer already gets the word right`() {
        assertNull(VoiceSamples.aliasFrom("gotz.", "Gotz"))
        // Accents and case don't count as a mishearing.
        assertNull(VoiceSamples.aliasFrom("Garcia", "García"))
    }

    @Test
    fun `blank or rambling transcripts are not learned`() {
        assertNull(VoiceSamples.aliasFrom("   ", "Gotz"))
        assertNull(VoiceSamples.aliasFrom("okay so I think the word is probably this one", "Gotz"))
    }

    @Test
    fun `sample file names are safe slugs`() {
        val name = VoiceSamples.newName("José Pérez!")
        assertTrue(name, name.startsWith("jose_perez_"))
        assertTrue(name, name.endsWith(".wav"))
    }

    // ---- tone by app ----

    @Test
    fun `an app pinned to a tone uses that tone over the built-in guess`() {
        // WhatsApp would be detected as chat; the user pinned it to email.
        val assigned = mapOf("com.whatsapp" to "email")
        assertEquals(AppContext.Category.EMAIL, AppContext.categoryFor("com.whatsapp", "hola", assigned))
        assertEquals(AppContext.Category.CHAT, AppContext.categoryFor("com.whatsapp", "hola"))
    }

    @Test
    fun `an unknown pinned key falls back to detection`() {
        val assigned = mapOf("com.whatsapp" to "nope")
        assertEquals(AppContext.Category.CHAT, AppContext.categoryFor("com.whatsapp", "hola", assigned))
    }

    @Test
    fun `default tones exist in both languages`() {
        val es = AppContext.defaultTone(AppContext.Category.EMAIL, Lang.ES)
        val en = AppContext.defaultTone(AppContext.Category.EMAIL, Lang.EN)
        assertTrue(es.isNotBlank() && en.isNotBlank() && es != en)
    }

    // ---- Spanish fillers ----

    @Test
    fun `spanish hesitations are removed`() {
        val out = FillerWordRemover.removeFillers("eh hola equipo, ehm mañana revisamos", emptyList())
        val words = out.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        assertEquals(listOf("hola", "equipo", "mañana", "revisamos"), words)
    }

    @Test
    fun `the em of 'em is not a filler`() {
        assertEquals("let 'em go", FillerWordRemover.removeFillers("let 'em go", emptyList()))
    }
}
