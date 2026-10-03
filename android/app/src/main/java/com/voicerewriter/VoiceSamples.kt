package com.voicerewriter

import android.content.Context
import java.io.File
import java.text.Normalizer

/**
 * Voice samples for the personal dictionary: the user records how they say a word, the
 * recording runs through the same speech-to-text they dictate with, and what it hears
 * becomes an alias of the word. Next time the recognizer produces that same mishearing
 * mid-dictation, [com.voicerewriter.textproc.VocabCorrector] snaps it to the right spelling.
 *
 * The recognizer is deliberately run *without* the vocabulary bias prompt here: the goal is
 * to capture how it hears the word on its own, which is the mishearing worth teaching.
 */
object VoiceSamples {

    /** Longest sample worth keeping: a word or short phrase. */
    const val MAX_MS = 4_000L

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "vocab_voice").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun newName(canonical: String): String {
        val slug = stripAccents(canonical).lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
        return "${slug.ifEmpty { "word" }}_${System.currentTimeMillis()}.wav"
    }

    fun delete(ctx: Context, names: List<String>) {
        for (n in names) runCatching { file(ctx, n).delete() }
    }

    /** Save [samples] as [name] and transcribe them with the user's current speech settings. */
    suspend fun transcribe(ctx: Context, samples: ShortArray, name: String): String {
        val wav = file(ctx, name)
        WavIo.write(wav, samples)
        val s = SettingsRepository(ctx.applicationContext).get()
        return if (s.sttProvider == "local") {
            OnDeviceStt.transcribe(ctx, s, WavIo.toFloats(samples), null)
        } else {
            SttEngine.transcribe(s, wav, null)
        }
    }

    /**
     * What to store as an alias of [canonical], given that the recognizer heard [heard].
     * Null when there's nothing to teach: it heard nothing, it already gets the word right
     * (ignoring case, accents and punctuation), or it heard something too long to be a
     * mishearing of this word.
     */
    fun aliasFrom(heard: String, canonical: String): String? {
        val h = heard.trim().trim { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        if (h.isEmpty()) return null
        if (comparable(h) == comparable(canonical)) return null
        val words = h.split(' ').size
        val maxWords = canonical.trim().split(Regex("\\s+")).size + 3
        if (words > maxWords) return null
        return h.lowercase()
    }

    private fun comparable(s: String): String =
        stripAccents(s).lowercase().filter { it.isLetterOrDigit() }

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
