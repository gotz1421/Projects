package com.voicerewriter.textproc

import com.voicerewriter.Lang
import com.voicerewriter.tr

/**
 * Classifies the focused app into a context category so dictation can adapt its
 * tone (formal in email/office, casual in chat). Builds on [CodeContext] (which
 * decides code/terminal handling for the deterministic stages); this adds the
 * broader category used to tune the optional LLM-polish prompt.
 *
 * The package map is partial/substring-based for robustness across OEM variants.
 */
object AppContext {

    enum class Category(val key: String, private val labelEs: String, private val labelEn: String) {
        GENERIC("generic", "Otras apps", "Other apps"),
        CODE("code", "Código y terminales", "Code & terminals"),
        EMAIL("email", "Correo y documentos", "Email & docs"),
        CHAT("chat", "Chat y mensajería", "Chat & messaging"),
        SOCIAL("social", "Redes sociales", "Social"),
        NOTES("notes", "Notas", "Notes");

        val label: String get() = tr(labelEs, labelEn)

        companion object {
            fun fromKey(key: String?): Category? = values().firstOrNull { it.key == key }
        }
    }

    /** Default tone fragment per category, in Spanish (es) and English (en). */
    private val DEFAULT_TONE_ES: Map<Category, String> = mapOf(
        Category.EMAIL to "Escríbelo para un contexto profesional y de trabajo: claro y cortés, " +
            "con oraciones completas, sin modismos ni emojis.",
        Category.CHAT to "Mantenlo casual y conversacional, como un mensaje de chat: frases " +
            "relajadas y naturales, breve.",
        Category.SOCIAL to "Mantenlo casual, natural y con un poco de chispa.",
    )
    private val DEFAULT_TONE_EN: Map<Category, String> = mapOf(
        Category.EMAIL to "Write this for a professional, work context: clear and polite, " +
            "complete sentences, no slang or emoji.",
        Category.CHAT to "Keep it casual and conversational, like a chat message: relaxed " +
            "phrasing and contractions, concise.",
        Category.SOCIAL to "Keep it casual, natural and a little punchy.",
    )

    /** Default tone fragment for [category] in [lang] ("es" or "en"); empty when there is none. */
    fun defaultTone(category: Category, lang: String = Lang.code): String =
        (if (lang == Lang.EN) DEFAULT_TONE_EN else DEFAULT_TONE_ES)[category].orEmpty()

    private val emailHints = listOf(
        "gmail", "com.google.android.gm", "outlook", "office.word", "office.outlook",
        "apps.docs", "yahoo.mobile.client.android.mail", "fastmail", "spark", "notion",
        "superhuman", "proton.android.mail", "protonmail",
    )
    private val chatHints = listOf(
        "whatsapp", "telegram", "org.thoughtcrime.securesms", "messaging", "messenger",
        "facebook.orca", "slack", "discord", "com.google.android.apps.messaging",
        "samsung.android.messaging", "wechat", "viber", "skype", "teams", "google.android.talk",
    )
    private val socialHints = listOf(
        "twitter", "com.twitter", "x.android", "reddit", "instagram", "threads", "mastodon",
        "bluesky", "bsky", "linkedin", "snapchat", "tiktok",
    )
    private val notesHints = listOf(
        "keep", "samsung.android.app.notes", "obsidian", "bear", "standardnotes",
        "simplenote", "joplin", "evernote", "onenote",
    )

    /**
     * Category of [text] dictated into [pkg]; code/terminal is decided by content.
     * [assigned] maps package → category key for apps the user pinned to a tone in
     * "Tone by app"; an explicit choice there beats every built-in guess below.
     */
    fun categoryFor(pkg: String?, text: String, assigned: Map<String, String> = emptyMap()): Category {
        if (pkg != null) Category.fromKey(assigned[pkg])?.let { return it }
        if (CodeContext.useCodeMode(pkg, text)) return Category.CODE
        val p = pkg?.lowercase() ?: return Category.GENERIC
        return when {
            emailHints.any { p.contains(it) } -> Category.EMAIL
            chatHints.any { p.contains(it) } -> Category.CHAT
            socialHints.any { p.contains(it) } -> Category.SOCIAL
            notesHints.any { p.contains(it) } -> Category.NOTES
            else -> Category.GENERIC
        }
    }
}
