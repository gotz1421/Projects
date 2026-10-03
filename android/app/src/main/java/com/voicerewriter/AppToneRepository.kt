package com.voicerewriter

import android.content.Context
import android.util.Log
import com.voicerewriter.textproc.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * "Tone by app", stored on-device in filesDir/app_tone.json:
 *
 *  - tone overrides per category, kept separately for each app language, so a tone written
 *    while the app is in Spanish stays Spanish and the English one stays English. A category
 *    without an override uses [AppContext.defaultTone] for the current language.
 *  - app assignments: package → category key. Pinning an app to a tone makes that tone apply
 *    there for certain, instead of relying on the built-in guess from the package name.
 *
 * Format: {"tones": {"es": {key: text}, "en": {key: text}}, "apps": {package: key}}.
 * The original flat format ({key: text}, English only) is read as the "en" tones.
 */
class AppToneRepository(context: Context) {

    data class Data(
        val tones: Map<String, Map<String, String>>, // lang → (category key → tone)
        val apps: Map<String, String>,               // package → category key
    )

    private val file = File(context.applicationContext.filesDir, "app_tone.json")

    suspend fun load(): Data = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext Data(emptyMap(), emptyMap())
        try {
            val o = JSONObject(file.readText())
            if (!o.has("tones") && !o.has("apps")) {
                // Legacy flat file: every key is a category, written in English.
                return@withContext Data(mapOf(Lang.EN to o.toStringMap()), emptyMap())
            }
            val tonesObj = o.optJSONObject("tones") ?: JSONObject()
            val tones = tonesObj.keys().asSequence().associateWith { lang ->
                // Blank tones are kept: a cleared tone means "no tone change here".
                tonesObj.optJSONObject(lang)?.toStringMap(keepBlank = true).orEmpty()
            }
            val apps = o.optJSONObject("apps")?.toStringMap().orEmpty()
            Data(tones, apps)
        } catch (e: Exception) {
            Log.e("AppToneRepository", "read failed", e); Data(emptyMap(), emptyMap())
        }
    }

    suspend fun save(data: Data) = withContext(Dispatchers.IO) {
        val tones = JSONObject()
        for ((lang, map) in data.tones) {
            val m = JSONObject()
            for ((k, v) in map) m.put(k, v.trim())
            tones.put(lang, m)
        }
        val apps = JSONObject()
        for ((pkg, key) in data.apps) apps.put(pkg, key)
        file.writeText(JSONObject().put("tones", tones).put("apps", apps).toString())
    }

    /** Packages pinned to a tone (package → category key). */
    suspend fun appAssignments(): Map<String, String> = load().apps

    /**
     * The effective tone fragment for [category] in the current app language: the user's
     * override for that language, else the built-in default.
     */
    suspend fun toneFor(category: AppContext.Category): String {
        val ov = load().tones[Lang.code]?.get(category.key)
        return ov ?: AppContext.defaultTone(category)
    }

    /**
     * The user's own tone for [category] in the current language, or null when they kept the
     * built-in default. Lets the on-device fine-tune keep its trained tone unless the user
     * actually wrote a different one.
     */
    suspend fun customToneFor(category: AppContext.Category): String? {
        val ov = load().tones[Lang.code]?.get(category.key) ?: return null
        return ov.takeIf { it.trim() != AppContext.defaultTone(category).trim() }
    }

    private fun JSONObject.toStringMap(keepBlank: Boolean = false): Map<String, String> =
        keys().asSequence().associateWith { optString(it).trim() }.filterValues { keepBlank || it.isNotEmpty() }
}
