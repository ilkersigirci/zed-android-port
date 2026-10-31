package com.zdroid

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/// Sum all historical + current samples of a relative MotionEvent axis.
/// `AXIS_RELATIVE_X` / `AXIS_RELATIVE_Y` are NOT accumulated across
/// batched samples by the framework; `getAxisValue` returns only the
/// most recent. Without summing the historical samples fast finger
/// motion loses ~80% of its travel (Samsung's cover trackpad batches
/// ~6-10 samples per event).
internal fun sumRelativeAxis(event: MotionEvent, axis: Int, pointerIndex: Int): Float {
    var sum = 0f
    val historySize = event.historySize
    for (h in 0 until historySize) {
        sum += event.getHistoricalAxisValue(axis, pointerIndex, h)
    }
    sum += event.getAxisValue(axis, pointerIndex)
    return sum
}

/// A captured event's relative motion for each pointer, in screen orientation.
/// Pointer capture hands over the kernel's axes without applying the display
/// rotation. That suits a mouse, which the user holds in screen orientation,
/// but Samsung's keyboard-cover trackpad reports in the panel's natural portrait
/// frame, so in landscape moving right sent the cursor down. Finger-driven
/// deltas therefore get the display rotation applied.
internal class CapturedDeltas(event: MotionEvent, displayRotation: Int) {
    val xs = FloatArray(event.pointerCount)
    val ys = FloatArray(event.pointerCount)
    val sumX: Float get() = xs.sum()
    val sumY: Float get() = ys.sum()

    init {
        // The cover trackpad sometimes reports SOURCE_MOUSE, but its tool type
        // stays FINGER, unlike a real mouse.
        val fromTouchpad = event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        for (i in xs.indices) {
            val dx = sumRelativeAxis(event, MotionEvent.AXIS_RELATIVE_X, i)
            val dy = sumRelativeAxis(event, MotionEvent.AXIS_RELATIVE_Y, i)
            val rotate = fromTouchpad || event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER
            when (if (rotate) displayRotation else Surface.ROTATION_0) {
                Surface.ROTATION_90 -> { xs[i] = dy; ys[i] = -dx }
                Surface.ROTATION_180 -> { xs[i] = -dx; ys[i] = -dy }
                Surface.ROTATION_270 -> { xs[i] = -dy; ys[i] = dx }
                else -> { xs[i] = dx; ys[i] = dy }
            }
        }
    }
}

internal fun Activity.displayRotation(): Int = display?.rotation ?: Surface.ROTATION_0

/// Mouse pointer-acceleration curve: base sensitivity multiplier plus a
/// gentle quadratic boost capped well below the touch trackpad's 4x.
/// Under pointer capture Android bypasses its own acceleration and hands
/// us raw device counts, so without this the cursor crawls on a high-res
/// panel.
///   |d|=1  -> ~1.6   (precision when slow)
///   |d|=10 -> ~22
///   |d|=30 -> ~96    (boost capped at 2x)
internal fun accelerateMouse(delta: Float): Float {
    val magnitude = kotlin.math.abs(delta)
    val direction = kotlin.math.sign(delta)
    val boost = kotlin.math.min(1f + magnitude * magnitude * MOUSE_ACCEL_COEF, MOUSE_ACCEL_CAP)
    return direction * magnitude * MOUSE_SENSITIVITY * boost
}

private const val MOUSE_SENSITIVITY = 1.6f
private const val MOUSE_ACCEL_COEF = 0.004f
private const val MOUSE_ACCEL_CAP = 2.0f

/// Draws behind the system bars and the cutout, so gpui's surface gets the
/// whole window, and hides the bars until a swipe brings them back, as an editor
/// on a desktop does.
internal fun Activity.enterEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
