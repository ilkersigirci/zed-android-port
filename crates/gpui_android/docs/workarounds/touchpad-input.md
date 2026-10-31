# Trackpads and mice go through Android's input stack

**Status:** Active
**Files:** `crates/gpui_android/src/events.rs`, `crates/gpui_android/src/events/mouse.rs`, `crates/gpui_android/src/touch.rs`, `MotionEventExt.kt` (`markTrackpadScrollAsTouch`)

## What Android does

The Book Cover trackpad (`sec_touchpad_pogo`, `Classes: TOUCH | TOUCH_MT | TOUCHPAD`) goes through AOSP's `TouchpadInputMapper` and `GestureConverter`, which run the Gestures library. `dumpsys input` shows both, with their properties. Android draws the pointer, applies its pointer speed, runs the library's palm handling (its `Palm` and `Keyboard Palm Prevent Timeout` properties), and reports this to the window under the pointer (Android 16, One UI 8, tablet mode, 2026-11-01, logged by a throwaway app):

| Gesture | Events |
|---|---|
| Move | `HOVER_ENTER`, `HOVER_MOVE`, `HOVER_EXIT`, source `MOUSE`, tool `FINGER` |
| Tap | `DOWN` with `BUTTON_PRIMARY`, `BUTTON_PRESS`, `BUTTON_RELEASE`, `UP` |
| Two-finger tap, or a press with two fingers | The same with `BUTTON_SECONDARY` |
| Press until it clicks, and slide | `DOWN`, `BUTTON_PRESS`, `MOVE` with the button held, `BUTTON_RELEASE`, `UP` |
| Tap, touch again and slide, with tap dragging on | The same, as one left-button drag |
| Two-finger swipe | `DOWN`, `MOVE`, `UP` of one fake finger with `CLASSIFICATION_TWO_FINGER_SWIPE`, whose `x` and `y` move with the scroll |
| Finger landing during a fling | That fake finger's `DOWN` and `CANCEL` |

So gpui_android has two jobs. A pointer's events are a mouse's, in `events/mouse.rs`. The fake finger is a touch pan: gpui's recognizer pans and flings it as it does a finger on the screen, which is why Android leaves the fling to the app (`handleFling` in `GestureConverter.cpp`: consistency between touchscreen and touchpad flings). It passes the touch slop 10 to 42 ms after its `DOWN`, well before a long press.

## One UI's differences

- A press with the pointer at the screen's edge reports `DOWN` with no button and private flags (`0x204000`), and one mid-screen press and slide did too. `events/mouse.rs` reads a `DOWN` with no button as the left one.
- One logged run reported three one-finger presses and slides as `BUTTON_SECONDARY`, and the pad's kernel device also advertises `BTN_RIGHT`, so its firmware may have a right-click area. Raw recordings of one-finger presses at the middle, far left and far right of the pad only showed `BTN_MOUSE`.

## Settings

These are Android's, not Zdroid's: pointer speed, tap to click, and scrolling direction (`touchpad_scrolling_direction` on One UI). Tap dragging is off by default, and `adb shell settings put system touchpad_tap_dragging 1` turns it on. An app can't change it.

## Why not pointer capture

Zdroid used to capture the pointer, recognize trackpad gestures itself and draw its own cursor. Capture came from a May 2026 reading that the trackpad "reports as a single-finger `SOURCE_MOUSE` pointer with no multi-finger gesture recognition". That single finger is the fake finger above, whose classification nothing read. Android's docs describe capture for apps such as games and remote desktops, and on the tablet it also kept the pointer from a window's title bar.
