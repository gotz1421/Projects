package com.voicerewriter

import android.content.Context

/**
 * Persisted state for the floating bubble: whether the user wants it, and where they put it.
 *
 * Both used to live only in memory — `BubbleService.isRunning` (a `@Volatile` static cleared in
 * `onDestroy`) and the x/y inside `WindowManager.LayoutParams`. Neither survives the process, so
 * a reboot or a background kill silently turned the bubble off and moved it back to the top-left
 * default, and the Settings toggle rendered "off" because it was reading the dead service flag.
 * Reported from the Play listing; see [BootReceiver] for the other half of the fix.
 *
 * Deliberately SharedPreferences rather than the DataStore in [Settings]:
 *  - [BootReceiver] has to decide whether to start the service inside a broadcast receiver, where
 *    a synchronous read is the difference between working and needing a `goAsync()` dance.
 *  - The drag handler writes the position on every touch-up, which wants a cheap write, not a
 *    read-modify-write of the whole settings object.
 */
object BubblePrefs {

    private const val FILE = "bubble"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val KEY_SNOOZE_MINUTES = "snooze_minutes"

    /** Nap lengths offered in Settings for dropping the bubble on the "Zzz" target. */
    val SNOOZE_CHOICES = listOf(3, 5, 10)
    const val DEFAULT_SNOOZE_MINUTES = 3

    /** Idle opacity of the bubble. Semi-transparent so it doesn't hide what's underneath. */
    const val DEFAULT_OPACITY = 0.55f
    const val MIN_OPACITY = 0.2f

    /** Sentinel for "never positioned", so a genuine 0 coordinate is not mistaken for unset. */
    const val UNSET = Int.MIN_VALUE

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Whether the user has the bubble switched on. Written at the points that represent an actual
     * user decision ([SetupUtils.startBubble] / [SetupUtils.stopBubble] and drag-to-dismiss) rather
     * than in `onDestroy` — the system can destroy a foreground service under memory pressure, and
     * treating that as "the user turned it off" would reintroduce the bug from the other side.
     */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true) // VoiceFlow: the bubble is the app, on by default

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Last position the user dragged the bubble to, or [UNSET] before they ever moved it. */
    fun x(ctx: Context): Int = prefs(ctx).getInt(KEY_X, UNSET)

    fun y(ctx: Context): Int = prefs(ctx).getInt(KEY_Y, UNSET)

    fun setPosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /**
     * How opaque the bubble is while idle (0.2–1.0). It goes fully opaque while touched or
     * recording, so the transparency never makes the live state hard to see.
     */
    fun opacity(ctx: Context): Float = clampOpacity(prefs(ctx).getFloat(KEY_OPACITY, DEFAULT_OPACITY))

    fun setOpacity(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat(KEY_OPACITY, clampOpacity(value)).apply()
    }

    fun clampOpacity(value: Float): Float = value.coerceIn(MIN_OPACITY, 1f)

    /** How long a nap lasts (minutes). */
    fun snoozeMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES).takeIf { it in SNOOZE_CHOICES }
            ?: DEFAULT_SNOOZE_MINUTES

    fun setSnoozeMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_SNOOZE_MINUTES, minutes).apply()
    }

    /**
     * Until when (epoch ms) the bubble is napping, or 0. Persisted so a nap survives the service
     * being restarted mid-nap instead of the bubble popping straight back.
     */
    fun snoozeUntil(ctx: Context): Long = prefs(ctx).getLong(KEY_SNOOZE_UNTIL, 0L)

    fun setSnoozeUntil(ctx: Context, until: Long) {
        prefs(ctx).edit().putLong(KEY_SNOOZE_UNTIL, until).apply()
    }

    /**
     * Where the bubble should sit while the keyboard is up, given where it normally lives.
     *
     * The bubble is a TYPE_APPLICATION_OVERLAY window, which the system always draws *below*
     * the keyboard. So a bubble the user parked in the bottom half of the screen was simply
     * covered whenever they started typing. If it would overlap the keyboard, lift it to sit
     * just above it; otherwise leave it where it is. Returns the y to use. Pure, for tests.
     */
    fun yAboveKeyboard(bubbleY: Int, size: Int, imeTop: Int, margin: Int): Int {
        if (bubbleY + size <= imeTop - margin) return bubbleY
        return (imeTop - size - margin).coerceAtLeast(0)
    }

    /**
     * Where to place the bubble on start: the saved point if there is one and it fits, otherwise
     * clamped into view, otherwise the default corner.
     *
     * Pulled out as a pure function so the clamping is testable without a device. The clamp is the
     * part that matters — a point saved in landscape, on a foldable's outer display, or in a
     * larger window can land outside the current screen, and a bubble drawn off-viewport can't be
     * dragged back. [screenW]/[screenH] are the current display size, [size] the bubble's width.
     */
    fun resolvePosition(
        savedX: Int,
        savedY: Int,
        defaultX: Int,
        defaultY: Int,
        screenW: Int,
        screenH: Int,
        size: Int,
    ): Pair<Int, Int> {
        if (savedX == UNSET || savedY == UNSET) return defaultX to defaultY
        // coerceAtLeast(0) guards the degenerate case where the bubble is wider than the screen,
        // which would otherwise make coerceIn throw on an inverted range.
        val maxX = (screenW - size).coerceAtLeast(0)
        val maxY = (screenH - size).coerceAtLeast(0)
        return savedX.coerceIn(0, maxX) to savedY.coerceIn(0, maxY)
    }
}
