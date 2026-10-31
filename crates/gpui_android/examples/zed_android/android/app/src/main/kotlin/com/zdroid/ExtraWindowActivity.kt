package com.zdroid

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity

/// Hosts one secondary gpui window. `multi_window` starts it with gpui's
/// `WindowId` in the [EXTRA_WINDOW_ID] extra. On freeform-windowing devices (DeX,
/// Android 16 Desktop Mode, ChromeOS) the OS provides native chrome: close X,
/// drag bar, resize handles. Elsewhere each one lives in its own Recents task.
///
/// `onCreate` registers the instance with Rust, which keeps a `GlobalRef` keyed
/// by the window id to close it later. `onDestroy` reports back: when the user
/// closed it through the OS chrome, that drives gpui's `remove_window`.
///
/// `configChanges` in the manifest keeps this Activity alive through resizes,
/// rotation and density changes. A config left out of it would recreate the
/// Activity, and `onDestroy` would then close the window, so drag-resize hard
/// after any manifest change.
class ExtraWindowActivity : AppCompatActivity(), ZdroidWindow {
    private val windowId: Long by lazy { intent.getLongExtra(EXTRA_WINDOW_ID, -1L) }
    private var surfaceView: SurfaceView? = null

    // No extras row here: its code-navigation keys are for the main editor.
    override val ime: WindowIme by lazy { WindowIme(this, windowId) }

    override val pointer: WindowPointer by lazy {
        WindowPointer(
            this,
            windowId,
            surfaceView = { surfaceView },
            obscuredBottom = { ime.insetsReporter.obscuredBottom },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // On freeform-windowing devices the OS-managed chrome (close X, drag
        // bar) renders on its own decoration layer above this Activity, so
        // hiding system bars here doesn't strip it.
        enterEdgeToEdge()

        if (windowId < 0L) {
            Log.e(TAG, "onCreate: missing or invalid $EXTRA_WINDOW_ID extra; finishing")
            finishAndRemoveTask()
            return
        }
        Log.i(TAG, "onCreate windowId=$windowId")

        // Android brought this Activity back from Recents after killing the
        // process, so the new process has no window for it. Going on would leave
        // a window where touches do nothing and nothing draws.
        if (!NativeBridge.nativeIsExtraWindowKnown(windowId)) {
            Log.w(TAG, "onCreate windowId=$windowId not known to Rust runtime (resurrection?); finishing")
            finishAndRemoveTask()
            return
        }

        NativeBridge.nativeOnExtraActivityCreated(windowId, this)

        val id = windowId
        val surface = ScrollableSurfaceView(this).apply {
            // Opaque, so the compositor ignores the alpha in wgpu's output.
            // With RGBA_8888, gpui's anti-aliased text edges and transparent
            // regions let the window background through as a whitish tint.
            holder.setFormat(android.graphics.PixelFormat.OPAQUE)
            holder.addCallback(SurfaceCallback(id))
            // Returning true keeps the gesture from the OS chrome's drag handle.
            setOnTouchListener { _, event ->
                forwardTouchEvent(id, event)
                true
            }
            // Hover (a mouse moving with no button pressed) arrives here, not
            // through the touch listener.
            setOnHoverListener { _, event ->
                forwardTouchEvent(id, event)
                true
            }
            // Wheel and trackpad scrolls. Captured events return false, to
            // reach the Activity's `onGenericMotionEvent`.
            setOnGenericMotionListener { _, event ->
                if (pointer.isCaptured(event)) {
                    false
                } else {
                    forwardTouchEvent(id, event)
                    true
                }
            }
            isFocusable = true
            isFocusableInTouchMode = true
            // After keyboard, mouse or trackpad input, Android draws a
            // translucent highlight over the focused view, and this one fills
            // the window.
            defaultFocusHighlightEnabled = false
        }
        surfaceView = surface
        setContentView(surface)
        ime.install()
        pointer.install()
    }

    /// Called by Rust (`multi_window::set_extra_activity_title`) from the
    /// gpui thread; `setTitle` must run on the UI thread.
    @Suppress("unused")
    fun setWindowTitle(windowTitle: String) {
        runOnUiThread { title = windowTitle }
    }

    /// Brings this window's task forward, for gpui's `activate_window`. An app
    /// may move its own tasks without the `REORDER_TASKS` permission.
    @Suppress("unused")
    fun moveOwnTaskToFront() {
        runOnUiThread {
            getSystemService(ActivityManager::class.java)
                ?.appTasks
                ?.firstOrNull { it.taskInfo.taskId == taskId }
                ?.moveToFront()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy windowId=$windowId")
        if (windowId >= 0L && surfaceView != null) {
            pointer.release()
        }
        if (windowId >= 0L) {
            NativeBridge.nativeOnExtraActivityDestroyed(windowId)
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (surfaceView == null) return
        NativeBridge.nativeOnExtraFocusChanged(windowId, hasFocus)
        pointer.onWindowFocusChanged(hasFocus)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        if (surfaceView != null) pointer.onPointerCaptureChanged(hasCapture)
    }

    override fun onTrackballEvent(event: MotionEvent): Boolean =
        pointer.onTrackballEvent(event) || super.onTrackballEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        pointer.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let(pointer::onTouchDispatched)
        return super.dispatchTouchEvent(event)
    }

    /// Key events go to gpui here: this Activity has no GameActivity input
    /// queue, and without this an editor in the window never gets a keystroke.
    /// They're claimed so that Android's fallback routing doesn't also act on
    /// them.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        pointer.onKeyDispatched(event)
        if (windowId >= 0L && !isSystemKey(event.keyCode)) {
            NativeBridge.nativeOnExtraKeyEvent(
                windowId,
                event.action,
                event.keyCode,
                event.metaState,
                event.repeatCount,
            )
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // The keys GameActivity's default key filter leaves to the system in the
    // main window, so volume and the like work in every window.
    private fun isSystemKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_ZOOM_IN,
        KeyEvent.KEYCODE_ZOOM_OUT -> true
        else -> false
    }

    private inner class SurfaceCallback(private val id: Long) : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated windowId=$id")
            NativeBridge.nativeOnExtraSurfaceCreated(id, holder.surface)
            pointer.onSurfaceCreated()
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            Log.i(TAG, "surfaceChanged windowId=$id ${width}x$height fmt=$format")
            NativeBridge.nativeOnExtraSurfaceChanged(id, holder.surface, format, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceDestroyed windowId=$id")
            NativeBridge.nativeOnExtraSurfaceDestroyed(id)
        }
    }

    private fun forwardTouchEvent(id: Long, event: MotionEvent) {
        val pointerCount = event.pointerCount
        if (pointerCount <= 0) return
        val xs = FloatArray(pointerCount)
        val ys = FloatArray(pointerCount)
        val ids = IntArray(pointerCount)
        val toolTypes = IntArray(pointerCount)
        for (i in 0 until pointerCount) {
            xs[i] = event.getX(i)
            ys[i] = event.getY(i)
            ids[i] = event.getPointerId(i)
            toolTypes[i] = event.getToolType(i)
        }
        // ACTION_SCROLL (mouse wheel + trackpad two-finger scroll) carries
        // its delta on the AXIS_VSCROLL / AXIS_HSCROLL axes — getX/Y return
        // the pointer position, not the scroll amount. Read both axes
        // unconditionally; they're zero on non-scroll events and the Rust
        // translator only consumes them under the Scroll action arm.
        val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        NativeBridge.nativeOnExtraTouchEvent(
            id,
            event.actionMasked,
            event.actionIndex,
            event.metaState,
            event.buttonState,
            event.flags,
            vscroll,
            hscroll,
            xs,
            ys,
            ids,
            toolTypes,
        )
    }

    companion object {
        private const val TAG = "zed_android_extra"
        const val EXTRA_WINDOW_ID = "com.zdroid.window_id"
    }
}

/// Without capture, Android asks the view under the pointer whether it can
/// scroll before turning a trackpad's two-finger swipe into `ACTION_SCROLL`. A
/// bare `SurfaceView` says no, and the swipe arrives as a click and drag.
private class ScrollableSurfaceView(context: Context) : SurfaceView(context) {
    override fun canScrollVertically(direction: Int): Boolean = true
    override fun canScrollHorizontally(direction: Int): Boolean = true
}
