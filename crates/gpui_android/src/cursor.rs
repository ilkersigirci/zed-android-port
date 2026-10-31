//! Bridge between gpui's `CursorStyle` and Android's `PointerIcon`. Each
//! window's `WindowPointer.setCursorStyle` sets it on its decor view and on its
//! cursor sprite.

use std::sync::atomic::{AtomicI32, Ordering};

use android_activity::AndroidApp;
use gpui::CursorStyle;
use jni::sys::jint;

/// The last `PointerIcon.TYPE_*` pushed, 0 for none: a window opened later gets
/// it, and an unchanged style skips JNI. gpui resets the cursor style on every
/// input event.
static LAST_STYLE_ICON_TYPE: AtomicI32 = AtomicI32::new(0);

pub(crate) fn last_pushed_icon_type() -> Option<jint> {
    let value = LAST_STYLE_ICON_TYPE.load(Ordering::Acquire);
    if value == 0 { None } else { Some(value) }
}

/// Map a gpui `CursorStyle` to one of Android's `PointerIcon.TYPE_*`
/// constants. Values come from
/// `frameworks/base/core/java/android/view/PointerIcon.java`.
fn pointer_icon_type(style: CursorStyle) -> jint {
    match style {
        CursorStyle::Arrow => 1000,                        // TYPE_DEFAULT
        CursorStyle::IBeam => 1008,                        // TYPE_TEXT
        CursorStyle::IBeamCursorForVerticalLayout => 1009, // TYPE_VERTICAL_TEXT
        CursorStyle::Crosshair => 1007,                    // TYPE_CROSSHAIR
        CursorStyle::ClosedHand => 1021,                   // TYPE_GRABBING
        CursorStyle::OpenHand => 1020,                     // TYPE_GRAB
        CursorStyle::PointingHand => 1002,                 // TYPE_HAND
        CursorStyle::ResizeLeft
        | CursorStyle::ResizeRight
        | CursorStyle::ResizeLeftRight
        | CursorStyle::ResizeColumn => 1014, // TYPE_HORIZONTAL_DOUBLE_ARROW
        CursorStyle::ResizeUp
        | CursorStyle::ResizeDown
        | CursorStyle::ResizeUpDown
        | CursorStyle::ResizeRow => 1015, // TYPE_VERTICAL_DOUBLE_ARROW
        CursorStyle::ResizeUpRightDownLeft => 1017,        // TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
        CursorStyle::ResizeUpLeftDownRight => 1016,        // TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
        CursorStyle::OperationNotAllowed => 1012,          // TYPE_NO_DROP
        CursorStyle::DragLink => 1010,                     // TYPE_ALIAS
        CursorStyle::DragCopy => 1011,                     // TYPE_COPY
        CursorStyle::ContextualMenu => 1001,               // TYPE_CONTEXT_MENU
    }
}

pub(crate) fn set_pointer_icon(android_app: &AndroidApp, style: CursorStyle) {
    let icon_type = pointer_icon_type(style);
    if LAST_STYLE_ICON_TYPE.swap(icon_type, Ordering::AcqRel) != icon_type {
        crate::jni_env::call_all_windows(
            android_app,
            "setCursorStyle",
            "(I)V",
            &[icon_type.into()],
        );
    }
}

/// Positions the window's cursor sprite for the virtual trackpad, in physical
/// pixels.
pub(crate) fn move_trackpad_cursor(
    android_app: &AndroidApp,
    extra_window_id: Option<u64>,
    x: f32,
    y: f32,
) {
    let result = crate::jni_env::call_window_method(
        android_app,
        extra_window_id,
        "setTrackpadCursorPosition",
        "(FF)V",
        &[x.into(), y.into()],
    );
    if let Err(err) = result {
        log::warn!("move_trackpad_cursor failed: {err:#}");
    }
}
