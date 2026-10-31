package com.zdroid

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/// Android reports a trackpad's two-finger scroll as one fake finger that moves
/// with the scroll, and marks it with this classification. gpui's touch recognizer
/// then pans and flings it as it does a finger on the screen, which is what Android
/// intends: it leaves the fling to the app so that the two feel the same. Rust
/// reads the source because GameActivity's events don't carry the classification.
internal fun MotionEvent.markTrackpadScrollAsTouch() {
    if (classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }
}

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
