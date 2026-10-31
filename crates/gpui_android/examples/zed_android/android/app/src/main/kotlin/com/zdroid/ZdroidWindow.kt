package com.zdroid

import android.app.Activity
import android.view.WindowManager

/// What `gpui_android` calls over JNI on the Activity hosting a window, the
/// main one or an extra one. Both have the same keyboard and pointer behavior
/// through [WindowIme] and [WindowPointer].
interface ZdroidWindow {
    val ime: WindowIme
    val pointer: WindowPointer

    @Suppress("unused")
    fun requestIme(show: Boolean) = ime.request(show)

    @Suppress("unused")
    fun restartImeForTarget(modeId: Int) = ime.restartForTarget(modeId)

    @Suppress("unused")
    fun updateImeTextState(
        text: String,
        windowStart: Int,
        selectionStart: Int,
        selectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ) = ime.updateTextState(
        ImeTextState(text, windowStart, selectionStart, selectionEnd, composingStart, composingEnd),
    )

    @Suppress("unused")
    fun setSoftKeyboardEnabled(enabled: Boolean) = ime.setSoftKeyboardEnabled(enabled)

    @Suppress("unused")
    fun setCursorStyle(style: Int) = pointer.setCursorStyle(style)

    @Suppress("unused")
    fun setTrackpadModeActive(active: Boolean) = pointer.setTrackpadModeActive(active)

    @Suppress("unused")
    fun setTrackpadCursorPosition(x: Float, y: Float) = pointer.setTrackpadCursorPosition(x, y)

    /// Zed's "prevent idle sleep", requested while an agent works.
    @Suppress("unused")
    fun setKeepScreenOn(on: Boolean) {
        val activity = this as Activity
        activity.runOnUiThread {
            if (on) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
