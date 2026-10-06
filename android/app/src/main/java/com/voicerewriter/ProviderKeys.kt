package com.voicerewriter

import android.content.Context

/**
 * One API key per cloud provider, so switching Groq → OpenAI → back doesn't lose the key you
 * already entered. [Settings] keeps the key of the *active* provider (that's what the engines
 * read); this keeps the rest. On-device only, same storage class as the rest of the settings.
 */
object ProviderKeys {
    const val STT = "stt"
    const val LLM = "llm"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("provider_keys", Context.MODE_PRIVATE)

    fun get(ctx: Context, kind: String, provider: String): String =
        prefs(ctx).getString("$kind:$provider", "").orEmpty()

    fun set(ctx: Context, kind: String, provider: String, key: String) {
        prefs(ctx).edit().putString("$kind:$provider", key.trim()).apply()
    }

    /** "gsk_…a1b2": enough to recognize which key it is, not enough to read it. */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.length <= 8) return "•".repeat(k.length.coerceAtLeast(4))
        return k.take(4) + "…" + k.takeLast(4)
    }
}
