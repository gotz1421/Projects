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

    // ---- "Glossary" prompt leaks and long takes ----

    @Test
    fun `an echoed Glossary label is removed mid-sentence`() {
        val out = com.voicerewriter.textproc.VocabCorrector.scrubPromptLeak(
            "mañana revisamos el reporte Glossary OptiPluss y luego lo mandamos")
        assertEquals("mañana revisamos el reporte OptiPluss y luego lo mandamos", out)
    }

    @Test
    fun `a trailing Glossary is dropped`() {
        val out = com.voicerewriter.textproc.VocabCorrector.scrubPromptLeak("y eso fue todo lo que pasó. Glossary:")
        assertEquals("y eso fue todo lo que pasó.", out)
    }

    @Test
    fun `a whole echoed hint is removed but the word stays when really spoken`() {
        val hint = "OptiPluss, Lauro."
        val out = com.voicerewriter.textproc.VocabCorrector.scrubPromptLeak(
            "hola equipo OptiPluss, Lauro. el lanzamiento de OptiPluss es mañana", hint)
        assertEquals("hola equipo el lanzamiento de OptiPluss es mañana", out)
    }

    @Test
    fun `the hint carries no label`() {
        val vocab = listOf(com.voicerewriter.textproc.VocabEntry("OptiPluss"))
        assertEquals("OptiPluss.", com.voicerewriter.textproc.VocabCorrector.whisperHint(vocab))
    }

    @Test
    fun `short takes are sent whole`() {
        val rate = 16000
        assertEquals(1, AudioChunker.split(ShortArray(rate * 20), rate).size)
    }

    @Test
    fun `long takes are cut at the quietest moment and cover every sample`() {
        val rate = 16000
        // 70 s of "speech" with silent gaps at 22 s and 47 s.
        val s = ShortArray(rate * 70) { 3000 }
        for (i in rate * 22 until rate * 22 + rate / 2) s[i] = 0
        for (i in rate * 47 until rate * 47 + rate / 2) s[i] = 0
        val parts = AudioChunker.split(s, rate)
        assertTrue(parts.size >= 3)
        assertEquals(0, parts.first().first)
        assertEquals(s.size - 1, parts.last().last)
        for (k in 1 until parts.size) assertEquals(parts[k - 1].last + 1, parts[k].first)
        // The first cut lands inside the first silent gap.
        assertTrue(parts[0].last + 1 in rate * 22..rate * 22 + rate / 2)
        for (p in parts) assertTrue(p.count() <= rate * 29)
    }
}
