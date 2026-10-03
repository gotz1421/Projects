package com.voicerewriter

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App language: Latin-American Spanish by default, English on request (Settings → Idioma).
 *
 * Deliberately independent of the phone's system language — the person asked for the app to
 * open in Spanish regardless — so this is an app setting, not Android resources. The current
 * language is Compose state: every `tr(...)` read during composition recomposes when it changes,
 * so switching language updates the open screen immediately.
 *
 * Strings live next to their use as `tr("español", "English")`. With two languages that keeps
 * each pair reviewable in one place, and a missing translation cannot slip through as a key.
 */
object Lang {
    const val ES = "es"
    const val EN = "en"

    private const val PREFS = "lang"
    private const val KEY = "code"

    var code by mutableStateOf(ES)
        private set

    private var loaded = false

    /** Read the saved choice. Cheap and idempotent; called from [VoiceFlowApp.onCreate]. */
    fun init(ctx: Context) {
        if (loaded) return
        code = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ES) ?: ES
        loaded = true
    }

    fun set(ctx: Context, newCode: String) {
        val c = if (newCode == EN) EN else ES
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, c).apply()
        code = c
        loaded = true
    }

    val isEnglish: Boolean get() = code == EN
}

/** Pick the string for the current app language. */
fun tr(es: String, en: String): String = if (Lang.isEnglish) en else es
