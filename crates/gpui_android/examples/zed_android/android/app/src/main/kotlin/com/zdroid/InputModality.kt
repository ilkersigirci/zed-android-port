package com.zdroid

import java.util.concurrent.atomic.AtomicBoolean

/// Process-wide single source of truth for which input modality
/// the user was most recently using: hardware pointer (mouse /
/// trackpad / virtual-trackpad-mode touch) or non-pointer
/// (direct touch on the touchscreen, hardware keyboard).
///
/// Shared by every window's [WindowPointer], so a window opened with the
/// trackpad shows its cursor before it has seen a pointer event of its own.
object InputModality {
    private val pointerActive = AtomicBoolean(false)

    /// True when the most recent input across the whole process
    /// was a hardware pointer (or virtual-trackpad-mode touch).
    fun isPointer(): Boolean = pointerActive.get()

    fun setPointer() {
        pointerActive.set(true)
    }

    fun setNonPointer() {
        pointerActive.set(false)
    }
}
