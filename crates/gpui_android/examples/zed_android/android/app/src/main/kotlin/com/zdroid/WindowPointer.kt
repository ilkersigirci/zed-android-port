package com.zdroid

import android.app.Activity
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceView

/// One window's mouse and trackpad input: pointer capture, the cursor sprite,
/// and the virtual trackpad's cursor.
///
/// Capture is on while the window has focus and a mouse or touchpad is
/// connected. Samsung's keyboard-cover trackpad otherwise goes through a gesture
/// filter that turns its gestures into single-finger drags. Captured events reach
/// the Activity's `onTrackballEvent` (relative mice, which carry the trackball
/// source class) and `onGenericMotionEvent`, not a view's captured-pointer
/// listener, which some of Samsung's DeX builds bypass.
class WindowPointer(
    private val activity: Activity,
    private val windowId: Long,
    private val surfaceView: () -> SurfaceView?,
    private val obscuredBottom: () -> Int,
) {
    /// In physical pixels. Kotlin owns the position because it also moves the
    /// sprite; each event carries it so Rust can't drift from the sprite.
    private var cursorX: Float = 0f
    private var cursorY: Float = 0f
    private var cursorOverlay: CursorSurfaceControl? = null
    private var cursorStyle: Int? = null
    private var trackpadModeActive: Boolean = false

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateCapture(activity.hasWindowFocus())
        override fun onInputDeviceRemoved(deviceId: Int) = updateCapture(activity.hasWindowFocus())
        override fun onInputDeviceChanged(deviceId: Int) = updateCapture(activity.hasWindowFocus())
    }

    fun install() {
        activity.getSystemService(InputManager::class.java)
            ?.registerInputDeviceListener(inputDeviceListener, null)
    }

    fun release() {
        activity.getSystemService(InputManager::class.java)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        cursorOverlay?.release()
        cursorOverlay = null
    }

    private fun updateCapture(hasFocus: Boolean) {
        val decorView = activity.window.decorView
        if (hasFocus && hasIndirectPointer()) {
            decorView.requestPointerCapture()
        } else {
            decorView.releasePointerCapture()
        }
    }

    private fun hasIndirectPointer(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        device.supportsSource(InputDevice.SOURCE_TOUCHPAD) ||
            device.supportsSource(InputDevice.SOURCE_MOUSE) ||
            device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        updateCapture(hasFocus)
        if (hasFocus && trackpadModeActive) {
            ensureCursorOverlay()
            cursorOverlay?.move(cursorX, cursorY)
        }
        applyCursorVisibility()
    }

    fun onPointerCaptureChanged(hasCapture: Boolean) {
        Log.i(TAG, "w=$windowId pointer capture=$hasCapture")
        if (hasCapture) {
            centerCursor()
            // Android can replace the SurfaceView's surface while another
            // Activity has focus, which orphans a sprite attached to the old
            // one, so the sprite is rebuilt on every regain.
            cursorOverlay?.release()
            cursorOverlay = null
            ensureCursorOverlay()
            cursorOverlay?.move(cursorX, cursorY)
        }
        applyCursorVisibility()
    }

    /// The sprite shows while the app's last input was a pointer, on the
    /// focused window only.
    private fun applyCursorVisibility() {
        cursorOverlay?.setVisible(InputModality.isPointer() && activity.hasWindowFocus())
    }

    private fun ensureCursorOverlay() {
        if (cursorOverlay != null) return
        val surface = surfaceView() ?: return
        val size = (CURSOR_SIZE_DP * activity.resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(16)
        cursorOverlay = CursorSurfaceControl(activity, surface, size).also { overlay ->
            cursorStyle?.let(overlay::setStyle)
        }
    }

    private fun centerCursor() {
        val (width, height) = visibleBounds()
        cursorX = width / 2f
        cursorY = height / 2f
    }

    /// The surface above the on-screen keyboard, where gpui lays out.
    private fun visibleBounds(): Pair<Float, Float> {
        val surface = surfaceView()
        val decorView = activity.window.decorView
        val width = (surface?.width ?: decorView.width).toFloat().coerceAtLeast(1f)
        val height = ((surface?.height ?: decorView.height) - obscuredBottom())
            .toFloat()
            .coerceAtLeast(1f)
        return width to height
    }

    fun setCursorStyle(style: Int) {
        activity.runOnUiThread {
            cursorStyle = style
            activity.window.decorView.pointerIcon = PointerIcon.getSystemIcon(activity, style)
            cursorOverlay?.setStyle(style)
        }
    }

    fun setTrackpadModeActive(active: Boolean) {
        activity.runOnUiThread {
            trackpadModeActive = active
            if (active) {
                showTrackpadCursor()
            } else {
                InputModality.setNonPointer()
                applyCursorVisibility()
            }
        }
    }

    /// An extra window can learn of trackpad mode before its surface exists.
    fun onSurfaceCreated() {
        if (trackpadModeActive && activity.hasWindowFocus()) showTrackpadCursor()
    }

    private fun showTrackpadCursor() {
        ensureCursorOverlay()
        if (cursorOverlay == null) return
        if (cursorX == 0f && cursorY == 0f) centerCursor()
        cursorOverlay?.move(cursorX, cursorY)
        InputModality.setPointer()
        applyCursorVisibility()
    }

    fun setTrackpadCursorPosition(x: Float, y: Float) {
        activity.runOnUiThread {
            val (width, height) = visibleBounds()
            cursorX = x.coerceIn(0f, width - 1f)
            cursorY = y.coerceIn(0f, height - 1f)
            cursorOverlay?.move(cursorX, cursorY)
        }
    }

    /// Returns whether the event was a captured one, now handled.
    fun onTrackballEvent(event: MotionEvent): Boolean {
        if (!activity.window.decorView.hasPointerCapture()) return false
        handleCapturedEvent(event)
        return true
    }

    /// Returns whether the event was a captured one, now handled.
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val fromCapturedSource = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE)
        if (!fromCapturedSource || !activity.window.decorView.hasPointerCapture()) return false
        handleCapturedEvent(event)
        return true
    }

    private fun handleCapturedEvent(event: MotionEvent) {
        if (!InputModality.isPointer()) {
            InputModality.setPointer()
            applyCursorVisibility()
        }
        val deltas = CapturedDeltas(event, activity.displayRotation())
        if (event.actionMasked == MotionEvent.ACTION_MOVE && movesCursor(event)) {
            val (maxX, maxY) = visibleBounds()
            // Under capture Android applies no acceleration. Skipped while a
            // button is held, so that click-drag selection stays precise.
            val dragging = event.buttonState != 0
            val moveX = if (dragging) deltas.sumX else accelerateMouse(deltas.sumX)
            val moveY = if (dragging) deltas.sumY else accelerateMouse(deltas.sumY)
            cursorX = (cursorX + moveX).coerceIn(0f, maxX - 1f)
            cursorY = (cursorY + moveY).coerceIn(0f, maxY - 1f)
            cursorOverlay?.move(cursorX, cursorY)
        }
        NativeBridge.nativeOnCapturedPointer(
            windowId,
            event.actionMasked,
            event.source,
            event.buttonState,
            // gpui reads Ctrl-click, Shift-click and Alt-click from the event.
            event.metaState,
            event.pointerCount,
            deltas.xs,
            deltas.ys,
            event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            event.getAxisValue(MotionEvent.AXIS_HSCROLL),
            cursorX,
            cursorY,
        )
    }

    /// One finger moves the cursor. So does a second one while Rust's
    /// hold-and-drag holds the first, so the selection follows it; a plain
    /// two-finger scroll leaves the cursor where it is.
    private fun movesCursor(event: MotionEvent): Boolean =
        event.pointerCount == 1 || NativeBridge.isHoldDragActive(windowId)

    /// Direct touch hides the sprite, unless touch is what moves the cursor.
    fun onTouchDispatched(event: MotionEvent) {
        if (!trackpadModeActive &&
            InputModality.isPointer() &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        ) {
            InputModality.setNonPointer()
            applyCursorVisibility()
        }
    }

    /// Typing hides the sprite until the pointer moves.
    fun onKeyDispatched(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && InputModality.isPointer()) {
            InputModality.setNonPointer()
            applyCursorVisibility()
        }
    }

    private companion object {
        const val TAG = "zed_android_capture"
        const val CURSOR_SIZE_DP = 24
    }
}
