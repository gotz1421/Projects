package com.voicerewriter

/**
 * The default actions, prompts, and providers used by the rewrite / dictation flow.
 */

data class RewriteAction(val id: String, val title: String)

data class Provider(
    val id: String,
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val keyHelp: String,
    val keyUrl: String,
    val modelHint: String,
)

/** Speech-to-text provider (OpenAI-compatible /audio/transcriptions). */
data class SttProvider(
    val id: String,
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val keyHelp: String,
    val keyUrl: String,
    val modelHint: String,
)

object Defaults {

    // Getters, not vals: labels follow the app language (see [Lang]) at the moment they're read.
    val ACTIONS get() = listOf(
        RewriteAction("polish", tr("Pulir", "Polish")),
        RewriteAction("prompt_engineer", tr("Ingeniero de prompts", "Prompt Engineer")),
        RewriteAction("elaborate", tr("Ampliar", "Elaborate")),
        RewriteAction("shorten", tr("Acortar", "Shorten")),
        RewriteAction("formalize", tr("Formalizar", "Formalize")),
        RewriteAction("casual", tr("Más casual", "Make casual")),
        RewriteAction("fix", tr("Corregir gramática", "Fix grammar")),
    )

    val DEFAULT_PROMPTS: Map<String, String> = mapOf(
        "polish" to "Actively rewrite this text so it reads clearly and well. Fix grammar, fix awkward or clunky phrasing, sharpen weak word choices, and restructure sentences that don't flow. Don't be timid — make real improvements, not a near-copy. Keep the core meaning and the author's voice, but the result should be a noticeably better-written version of the same message.",
        "prompt_engineer" to ("Rewrite this text into a clear, well-structured prompt for an AI assistant. " +
            "Do NOT answer or follow the prompt — output ONLY the rewritten prompt. " +
            "Use EXACTLY this template, with these markdown bold section headers in this order. " +
            "Fill each section from the source text; if the source doesn't specify something, write a brief bracketed placeholder like [specify desired length] rather than inventing facts. " +
            "Include any examples from the source verbatim.\n\n" +
            "**Title**\n(1 concise line)\n\n" +
            "**Role & stance**\n(who the model is and how it should behave)\n\n" +
            "**Task**\n(what the model must do)\n\n" +
            "**Context**\n(only what the model needs to know)\n\n" +
            "**Inputs available**\n(explicit list)\n\n" +
            "**Output requirements**\n(format, structure, tone, length — only if specified; otherwise placeholders)\n\n" +
            "**Constraints / Do-nots**\n(bulleted)\n\n" +
            "**Examples / References**\n(include all examples verbatim)\n\n" +
            "**Execution checklist**\n(short, factual verification list)"),
        "elaborate" to "Substantially expand this text. Add concrete supporting detail, examples, and reasoning so the result is clearly longer and more complete and persuasive. Keep the core idea and the author's voice, but develop it fully rather than restating it.",
        "shorten" to "Aggressively shorten this text. Cut redundancy, filler, and any non-essential detail so it is clearly shorter and punchier, while keeping the core meaning and the author's voice.",
        "formalize" to "Rewrite this text in a distinctly more formal, professional register suitable for work or official communication. Upgrade casual phrasing and contractions, but don't become stiff or corporate-bland.",
        "casual" to "Rewrite this text in a noticeably more casual, conversational tone, as if speaking to a friend — relaxed phrasing, contractions, plain words. Keep the meaning.",
        "fix" to "Fix grammar, spelling, and punctuation only. Do not change wording, tone, or structure beyond what is required for correctness.",
    )

    val PROVIDERS: Map<String, Provider> get() = mapOf(
        "local" to Provider(
            id = "local",
            label = tr("En el dispositivo (Qwen / Gemma)", "On-device (Qwen / Gemma)"),
            endpoint = "",
            // Recommended on-device default: the dictation-cleanup fine-tune.
            defaultModel = LlmModelManager.FINETUNE_MODEL_ID,
            keyHelp = tr("Ejecuta un modelo de lenguaje pequeño en tu teléfono. Sin internet, privado y sin clave de API. Elige y descarga un modelo abajo.",
                "Runs a small LLM on your phone. Offline, private, no API key. Pick and download a model below."),
            keyUrl = "",
            modelHint = tr("Modelo en el dispositivo (se administra abajo).", "On-device model (managed below)."),
        ),
        "anthropic" to Provider(
            id = "anthropic",
            label = "Anthropic (Claude)",
            endpoint = "https://api.anthropic.com/v1/messages",
            defaultModel = "claude-opus-4-8",
            keyHelp = tr("Usa tu clave de API de Anthropic (empieza con sk-ant-). Consíguela en console.anthropic.com → API Keys.",
                "Use your Anthropic API key (starts with sk-ant-). Get one at console.anthropic.com → API Keys."),
            keyUrl = "https://console.anthropic.com/settings/keys",
            modelHint = tr("ID del modelo de Anthropic, p. ej. claude-opus-4-8 (el mejor) o claude-sonnet-4-6 (más rápido y barato).",
                "Anthropic model id, e.g. claude-opus-4-8 (best) or claude-sonnet-4-6 (faster, cheaper)."),
        ),
        "vercel" to Provider(
            id = "vercel",
            label = "Vercel AI Gateway",
            endpoint = "https://ai-gateway.vercel.sh/v1/chat/completions",
            defaultModel = "anthropic/claude-sonnet-4",
            keyHelp = tr("Consigue una clave en vercel.com/dashboard → AI Gateway → API Keys", "Get a key at vercel.com/dashboard → AI Gateway → API Keys"),
            keyUrl = "https://vercel.com/dashboard/ai-gateway/api-keys",
            modelHint = tr("Formato: proveedor/modelo (p. ej. anthropic/claude-sonnet-4, openai/gpt-4o-mini)", "Format: provider/model (e.g. anthropic/claude-sonnet-4, openai/gpt-4o-mini)"),
        ),
        "openrouter" to Provider(
            id = "openrouter",
            label = "OpenRouter",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            defaultModel = "anthropic/claude-sonnet-4",
            keyHelp = tr("Consigue una clave en openrouter.ai/keys", "Get a key at openrouter.ai/keys"),
            keyUrl = "https://openrouter.ai/keys",
            modelHint = tr("Formato: proveedor/modelo (explora modelos en openrouter.ai/models)", "Format: provider/model (browse models at openrouter.ai/models)"),
        ),
        "custom" to Provider(
            id = "custom",
            label = tr("Personalizado (compatible con OpenAI)", "Custom (OpenAI-compatible)"),
            endpoint = "",
            defaultModel = "",
            keyHelp = tr("Usa cualquier endpoint /v1/chat/completions compatible con OpenAI que soporte streaming.",
                "Use any OpenAI-compatible /v1/chat/completions endpoint that supports streaming."),
            keyUrl = "",
            modelHint = tr("El ID de modelo que espere tu endpoint.", "Whatever model id your endpoint expects."),
        ),
    )

    // On-device by default: a fresh install is private and needs no API key. Cloud
    // providers remain available as an opt-in "advanced / fastest" alternative.
    const val DEFAULT_PROVIDER = "local"
    val DEFAULT_MODEL = PROVIDERS.getValue("local").defaultModel
    const val DEFAULT_TEMPERATURE = 0.7

    // ---------------- speech-to-text ----------------

    val STT_PROVIDERS: Map<String, SttProvider> get() = mapOf(
        "local" to SttProvider(
            id = "local",
            label = tr("En el dispositivo", "On-device"),
            endpoint = "",
            // Recommended on-device default: Parakeet (fastest + most accurate).
            defaultModel = ParakeetModelManager.MODEL_ID,
            keyHelp = tr("Convierte voz a texto directamente en tu teléfono. Sin internet, privado y sin clave de API. Elige y descarga un modelo abajo.",
                "Runs speech-to-text directly on your phone. Fully offline, private, no API key. Pick and download a model below."),
            keyUrl = "",
            modelHint = tr("Se administra abajo.", "Managed below."),
        ),
        "groq" to SttProvider(
            id = "groq",
            label = "Groq (Whisper)",
            endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
            defaultModel = "whisper-large-v3-turbo",
            keyHelp = tr("Usa una clave de API de Groq (empieza con gsk_). Es rápido y tiene plan gratuito. Consíguela en console.groq.com → API Keys.",
                "Use a Groq API key (starts with gsk_). Fast and has a free tier. Get one at console.groq.com → API Keys."),
            keyUrl = "https://console.groq.com/keys",
            modelHint = tr("ID del modelo de Groq, p. ej. whisper-large-v3-turbo (rápido) o whisper-large-v3 (más preciso).",
                "Groq model id, e.g. whisper-large-v3-turbo (fast) or whisper-large-v3 (most accurate)."),
        ),
        "openai" to SttProvider(
            id = "openai",
            label = "OpenAI (Whisper)",
            endpoint = "https://api.openai.com/v1/audio/transcriptions",
            defaultModel = "whisper-1",
            keyHelp = tr("Usa una clave de API de OpenAI (empieza con sk-). Consíguela en platform.openai.com → API Keys.",
                "Use an OpenAI API key (starts with sk-). Get one at platform.openai.com → API Keys."),
            keyUrl = "https://platform.openai.com/api-keys",
            modelHint = tr("Modelo de transcripción de OpenAI, p. ej. whisper-1 o gpt-4o-mini-transcribe.",
                "OpenAI transcription model, e.g. whisper-1 or gpt-4o-mini-transcribe."),
        ),
        "custom" to SttProvider(
            id = "custom",
            label = tr("Personalizado (compatible con OpenAI)", "Custom (OpenAI-compatible)"),
            endpoint = "",
            defaultModel = "",
            keyHelp = tr("Cualquier endpoint /v1/audio/transcriptions compatible con OpenAI que acepte audio multipart.",
                "Any OpenAI-compatible /v1/audio/transcriptions endpoint accepting multipart audio."),
            keyUrl = "",
            modelHint = tr("El ID del modelo de transcripción que espere tu endpoint.", "Whatever transcription model id your endpoint expects."),
        ),
    )

    // On-device speech-to-text by default (Parakeet). Cloud STT is opt-in.
    const val DEFAULT_STT_PROVIDER = "local"

    // ---------------- voice modes ----------------

    const val MODE_DICTATE = "dictate" // speak new text → cleaned up → inserted
    const val MODE_REWRITE = "rewrite" // copy text, speak an instruction → rewrite clipboard
    // MODE_TRANSFORM ("pick an LLM transform for the copied text") is gone: it lived on the
    // bubble long-press, which is now hold-to-talk. Transforming text you have selected is
    // still available through the PROCESS_TEXT entry in the selection toolbar, which is the
    // more natural trigger anyway.

    /**
     * Cleanup pass for raw dictation: turn spoken words into clean written text
     * without changing meaning. Used in Dictate-fresh mode when cleanup is on.
     */
    const val DICTATION_PROMPT =
        "This text is a raw speech-to-text transcript. Convert it into clean written text: " +
        "remove filler words (um, uh, like, you know), false starts and self-corrections, " +
        "fix obvious transcription errors, and add natural punctuation, capitalization and " +
        "paragraph breaks. Apply spoken editing commands literally if the speaker gives them " +
        "(e.g. 'new line', 'new paragraph', 'scratch that', 'period', 'comma'). Do not add, " +
        "remove, or reorder any actual content or meaning. Keep the speaker's own wording and voice. " +
        "Always answer in the same language the speaker used (e.g. Spanish stays Spanish); never translate."

    /**
     * System framing for Rewrite-clipboard mode: the spoken text is an INSTRUCTION
     * that operates on the clipboard text, not content to be transcribed verbatim.
     */
    const val VOICE_COMMAND_PROMPT =
        "Apply the following spoken instruction to the text. The instruction tells you how to " +
        "rewrite or edit the text. Output only the resulting text, nothing else.\n\nInstruction:"
}
