package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Makes "insert the rewrite into the field I was typing in" possible. An overlay
 * cannot write into other apps — only an IME, the owning app, or an accessibility
 * service can.
 *
 * Insertion is event-driven: RewriteActivity hands us the text and finishes; once
 * the *host* app regains window focus (we ignore our own windows), we paste the
 * clipboard into its focused editable field. Timed retries back this up in case
 * no focus event fires. This avoids the trap of inserting while our own activity
 * is still the active window.
 */
class OpenWisprAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpenWisprA11y"

        @Volatile
        private var instance: OpenWisprAccessibilityService? = null

        /**
         * Package of the last non-self app to take window focus. The dictation
         * pipeline reads this to decide whether the target is a code/terminal field
         * (where spoken "dot"/"slash"/"dash" should pass through as words).
         */
        @Volatile
        var lastHostPackage: String? = null
            private set

        /**
         * System surfaces whose window-state changes must NOT be mistaken for the
         * dictation target. The status bar / notification shade / nav bar all report
         * as "com.android.systemui", and the keyboard reports as an input-method
         * package — neither is the app the user is dictating into, so letting them
         * overwrite [lastHostPackage] is what made history entries read "System UI".
         */
        private val NON_HOST_PACKAGES = setOf(
            "com.android.systemui", "android", "com.android.launcher", "com.sec.android.app.launcher",
        )

        private fun isHostPackage(pkg: String, self: String): Boolean =
            pkg != self && pkg !in NON_HOST_PACKAGES &&
                !pkg.contains("inputmethod") && !pkg.contains("honeyboard")

        /** True when the user has enabled the service in Accessibility settings. */
        val isEnabled: Boolean get() = instance != null

        /**
         * Stage [text] on the clipboard and insert it into the host app's focused
         * field as soon as that app is back in front. Returns true if the service
         * is running (so the caller knows auto-insert will be attempted).
         * Call from the main thread.
         */
        fun enqueueInsert(text: String): Boolean {
            val svc = instance ?: return false
            svc.startInsert(text)
            return true
        }

        /** Re-check focus now (e.g. when the bubble (re)starts) so it shows if a field is already focused. */
        fun reevaluate() {
            val svc = instance ?: return
            svc.fieldHandler.post { svc.evaluateFieldFocus() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    // Field checks get their own handler: the insert path clears `main` wholesale
    // (removeCallbacksAndMessages(null)), which used to drop a pending focus check too.
    private val fieldHandler = Handler(Looper.getMainLooper())
    private var fieldCheckPending = false
    @Volatile private var pendingText: String? = null
    private val retryDelays = longArrayOf(250, 500, 900, 1400, 2000)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
        // Now that focus detection is available, let the bubble switch to its
        // "only show on text fields" behavior (it starts always-visible without us).
        BubbleService.instance?.refreshGating()
        fieldHandler.post { evaluateFieldFocus() }
    }

    /** Throttled re-check of whether the user can type (drives the bubble). */
    private val fieldCheck = Runnable { fieldCheckPending = false; evaluateFieldFocus() }

    /**
     * Throttle, not debounce. A debounce re-armed on every TYPE_WINDOW_CONTENT_CHANGED never
     * fires in apps that redraw constantly (chats with typing indicators, video, live feeds),
     * so the bubble never appeared there. A throttle guarantees a check every ~120 ms.
     */
    private fun scheduleFieldCheck() {
        if (fieldCheckPending) return
        fieldCheckPending = true
        fieldHandler.postDelayed(fieldCheck, 120)
    }

    /**
     * Tell the bubble whether the user can type right now, and where the keyboard is.
     *
     * Two signals, either is enough:
     *  - a keyboard (IME) window is on screen. This is the one that works everywhere: Flutter,
     *    React Native, games, WebViews and many chat apps never expose an `isEditable` node with
     *    input focus, but the keyboard window itself is reported by the system for every IME
     *    (Gboard, Samsung, SwiftKey, ...). Needs flagRetrieveInteractiveWindows (set in config).
     *  - a focused editable node in a host window (the original check; covers hardware keyboards
     *    and the moment before the IME finishes animating in).
     *
     * Skips our own windows so the recording sheet doesn't flap the bubble.
     */
    private fun evaluateFieldFocus() {
        // Scan all interactive windows, not just rootInActiveWindow — across an app
        // switch the "active window" can be null/transient, which left the bubble
        // stuck. The focused editable lives in whichever window holds input focus.
        val wins = try { windows } catch (_: Exception) { null }
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return
            if (root.packageName == packageName) return
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editable = f != null && f.isEditable
            @Suppress("DEPRECATION") f?.recycle()
            Log.d(TAG, "fieldFocus(fallback) editable=$editable")
            BubbleService.instance?.setFieldFocused(editable, null)
            return
        }
        var editable = false
        var ourModalActive = false
        var imeBounds: Rect? = null
        for (w in wins) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val r = Rect()
                w.getBoundsInScreen(r)
                // A collapsed/hidden IME can linger as a zero-height window.
                if (r.height() > 0) imeBounds = r
                continue
            }
            if (editable) continue
            val root = w.root ?: continue
            if (root.packageName == packageName) {
                // Our recording/transform sheet (an activity) — don't flap the bubble.
                // The bubble's own overlay window is harmless; only the modal counts.
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) ourModalActive = true
                continue
            }
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                if (f.isEditable) editable = true
                @Suppress("DEPRECATION") f.recycle()
            }
        }
        if (ourModalActive) return // leave the bubble as-is while our sheet is up
        val canType = editable || imeBounds != null
        Log.d(TAG, "fieldFocus editable=$editable ime=$imeBounds host=$lastHostPackage")
        BubbleService.instance?.setFieldFocused(canType, imeBounds)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track the foreground host app (cheap: window changes are infrequent) so the
        // dictation pipeline can adapt normalization to code/terminal fields.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && isHostPackage(pkg, packageName)) lastHostPackage = pkg
        }
        // Drive the field-gated bubble: re-check focus on any event that can change it
        // (coalesced — content-changed can fire in bursts).
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleFieldCheck()
            }
        }
        if (pendingText == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName
                if (pkg != null && pkg != packageName) attemptInsert()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        fieldHandler.removeCallbacksAndMessages(null)
        fieldCheckPending = false
        if (instance === this) instance = null
        // Service gone — focus detection is impossible, so let the bubble show always.
        BubbleService.instance?.refreshGating()
    }

    // ---------------- insertion ----------------

    private fun startInsert(text: String) {
        // Note: we deliberately do NOT stage the clipboard here. The common insert path
        // writes the text straight into the field (ACTION_SET_TEXT) without touching the
        // clipboard, so a successful dictation no longer clobbers what the user had copied.
        // The clipboard is only used as a fallback (see attemptInsert / the give-up branch).
        pendingText = text
        main.removeCallbacksAndMessages(null)
        // Backstop retries in case the focus event doesn't arrive.
        for (delay in retryDelays) main.postDelayed({ attemptInsert() }, delay)
        // Give up after the last retry: no field ever focused — fall back to a clipboard copy.
        main.postDelayed({
            val t = pendingText
            if (t != null) {
                pendingText = null
                setClipboard(t)
            }
        }, retryDelays.last() + 300)
    }

    /** Try once to insert into the host app's focused editable field. */
    private fun attemptInsert() {
        val text = pendingText ?: return
        val node = findHostFocusedEditable() ?: return
        var spliced = false
        val ok = try {
            // Prefer a clipboard-free splice at the cursor; fall back to paste only when
            // we can't determine the cursor (e.g. some WebView fields).
            spliced = insertAtCursor(node, text)
            spliced || pasteViaClipboard(node, text)
        } catch (e: Exception) {
            Log.e(TAG, "insert action failed", e); false
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        if (ok) {
            Log.i(TAG, "inserted into host field (spliced=$spliced)")
            pendingText = null
            main.removeCallbacksAndMessages(null)
            // Some apps accept ACTION_SET_TEXT and then quietly put their own text back (fields
            // whose content is owned by app code, common in React Native / Compose apps). Check
            // a moment later; if the words are not there, paste them instead.
            if (spliced) main.postDelayed({ verifySplice(text) }, 350)
            // The haptic tick is the confirmation. A toast on top of text visibly appearing in
            // the field is telling the user something they can already see.
            vibrateTick()
        }
    }

    private fun verifySplice(text: String) {
        val node = findHostFocusedEditable()
        if (node == null) {
            // The field went away (app switched screens). Leave the words where they can be reached.
            setClipboard(text)
            return
        }
        try {
            val current = if (showingHint(node)) "" else node.text?.toString().orEmpty()
            if (!normalized(current).contains(normalized(text).take(40))) {
                Log.i(TAG, "splice was reverted by the app; pasting instead")
                if (!pasteViaClipboard(node, text)) setClipboard(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "verify failed", e)
            setClipboard(text)
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
    }

    private fun normalized(s: String) = s.lowercase().filter { !it.isWhitespace() }

    /** An empty field can report its placeholder as its text; never splice into a hint. */
    private fun showingHint(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText

    /**
     * Clipboard-free insert: splice [insert] in at the cursor (replacing any active
     * selection) via ACTION_SET_TEXT, then place the cursor after it. Returns false when
     * the cursor can't be determined in a non-empty field, so the caller can fall back to
     * paste. This is the path that keeps the clipboard untouched on a normal dictation.
     */
    private fun insertAtCursor(node: AccessibilityNodeInfo, insert: String): Boolean {
        val current = if (showingHint(node)) "" else node.text?.toString() ?: ""
        val selA = node.textSelectionStart
        val selB = node.textSelectionEnd
        val (start, end) = when {
            selA in 0..current.length && selB in 0..current.length ->
                minOf(selA, selB) to maxOf(selA, selB)
            current.isEmpty() -> 0 to 0
            else -> return false // unknown cursor in a non-empty field — let paste handle it
        }
        // Join naturally with what's already there: "hola" + "qué tal" → "hola qué tal".
        val needsSpace = start > 0 && !current[start - 1].isWhitespace() && insert.firstOrNull()?.isWhitespace() == false
        val piece = if (needsSpace) " $insert" else insert
        val newText = current.substring(0, start) + piece + current.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val cursor = start + piece.length
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    /** Fallback insert: stage on the clipboard and paste. Honors cursor position, but the
     *  text is necessarily left on the clipboard (only used when [insertAtCursor] can't). */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        setClipboard(text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /**
     * The host app's field to type into, never one of ours. Looks in the active window first,
     * then in every other window: across an app switch, or with a dialog/bottom sheet up, the
     * "active" window is often not the one that owns the input focus. Accepts a focused node
     * that can take text even when it doesn't flag itself editable (some WebView and Flutter
     * fields), as long as it supports SET_TEXT or PASTE.
     */
    private fun findHostFocusedEditable(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        val wins = try { windows } catch (_: Exception) { null }
        wins?.forEach { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            w.root?.let { roots.add(it) }
        }
        for (root in roots) {
            if (root.packageName == packageName) continue // our own window
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (focused.packageName == packageName) {
                @Suppress("DEPRECATION") focused.recycle(); continue
            }
            if (focused.isEditable || acceptsText(focused)) return focused
            @Suppress("DEPRECATION") focused.recycle()
        }
        return null
    }

    private fun acceptsText(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE
        }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    /** Light confirmation buzz when text lands in the field. */
    private fun vibrateTick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(20)
            }
        } catch (_: Exception) {}
    }
}
