//! Pointer-capture event synthesis.
//!
//! When `WindowPointer.kt` has pointer capture, the trackpad delivers raw
//! `MotionEvent`s instead of being filtered through Android's gesture-detector
//! layer. Captured events arrive tagged
//! `SOURCE_TOUCHPAD` (not `SOURCE_MOUSE`) with full multi-touch state,
//! per-pointer `AXIS_RELATIVE_X/Y` deltas, and clean
//! `ACTION_BUTTON_PRESS` / `ACTION_BUTTON_RELEASE` framing.
//!
//! This module turns that raw stream into the gpui `PlatformInput`
//! vocabulary so the editor sees normal `MouseMove` / `MouseDown` /
//! `MouseUp` / `Scroll` events. We keep our own cursor position
//! (the system cursor is hidden while capture is active) and update it
//! by accumulating per-event relative deltas. Single-finger motion
//! moves the cursor; two-finger motion synthesizes a scroll from the
//! centroid delta and pins the cursor in place.
//!
//! ## Event flow
//!
//! Kotlin builds a `CapturedEvent` from each captured `MotionEvent` and
//! calls `nativeOnCapturedPointer` (JNI). The Rust handler pushes the
//! event onto a static unbounded channel; the platform's main loop
//! drains the channel each iteration and feeds the synthesized
//! `PlatformInput`s into the primary window's `handle_input`. Cross-
//! thread because Kotlin's listener fires on the UI thread but
//! `handle_input` must run on the game thread.
//!
//! ## Gestures (matching desktop trackpad standards)
//!
//! Single-finger:
//! - Plain tap (DOWN → UP within `TAP_WINDOW`, motion < `TAP_MOTION_PX`):
//!   synthesize `MouseDown(Left)` + `MouseUp(Left)` at cursor — left
//!   click.
//! - Tap-tap-drag (per libinput/Windows Precision Touchpad standard):
//!   plain tap, then a second `ACTION_DOWN` within `TAP_DRAG_WINDOW`
//!   of the first tap-up near the same position. On the first MOVE
//!   past `TAP_DRAG_SLOP`, emit `MouseDown(Left)` at the tap anchor
//!   and treat subsequent motion as click-and-drag. ACTION_UP ends
//!   the drag. This is how text selection / rubber-band / drag-and-
//!   drop is invoked on every major desktop trackpad spec.
//! - `ACTION_BUTTON_PRESS` (pressing a clickpad until it clicks, or a
//!   mouse button): emit `MouseDown(Left)`, follow with MouseMove(Left
//!   held), release on `ACTION_BUTTON_RELEASE`. Coexists with
//!   tap-tap-drag for trackpads without a physical click.
//!
//! Two-finger:
//! - Quick tap-tap (both fingers down + up within `TWO_FINGER_TAP_WINDOW`
//!   with low motion): right-click at cursor.
//! - Hold-and-drag: finger 1 is on the pad for at least
//!   `HOLD_DRAG_THRESHOLD` before finger 2 lands. Synthesize
//!   `MouseDown(Left)` at finger 1's anchor on `ACTION_POINTER_DOWN`,
//!   accumulate finger 2's relative deltas into an internal drag
//!   cursor, emit `MouseMove(Left held)` at the drag cursor on each
//!   MOVE, and release on either pointer lifting. This is how
//!   Samsung's stock trackpad driver recognizes click-and-drag, and
//!   matches what users observe on Android trackpads when selecting
//!   text. Discriminates from scroll because scroll lands both fingers
//!   near-simultaneously (hold-elapsed < threshold).
//! - Move (both fingers landed near-simultaneously): scroll. The
//!   centroid delta drives `ScrollDelta::Pixels`.

use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};
use std::time::{Duration, Instant};

use android_activity::input::MetaState;
use futures::channel::mpsc;
use gpui::{
    GestureTuning, MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent, Pixels,
    PlatformInput, Point, PointerFling, ScrollDelta, ScrollEvent, ScrollSource, TouchPhase, point,
    px,
};
use jni::objects::{JFloatArray, JObject};

use crate::events::click_track::ClickTrackState;
use crate::events::mouse::{self, button_from_state};
use crate::events::{
    JAVA_ACTION_BUTTON_PRESS, JAVA_ACTION_BUTTON_RELEASE, JAVA_ACTION_CANCEL, JAVA_ACTION_DOWN,
    JAVA_ACTION_MOVE, JAVA_ACTION_POINTER_DOWN, JAVA_ACTION_POINTER_UP, JAVA_ACTION_SCROLL,
    JAVA_ACTION_UP,
};

/// `InputDevice.SOURCE_TOUCHPAD` = SOURCE_CLASS_POSITION (0x8) plus
/// the touchpad bit (0x100000).
const ANDROID_SOURCE_TOUCHPAD: i32 = 0x00100008;

/// Tap detection: a single-finger `ACTION_DOWN` → `ACTION_UP` within
/// this time, with less than `TAP_MOTION_PX` of accumulated motion,
/// counts as a click and synthesizes `MouseDown` + `MouseUp(Left)`.
const TAP_WINDOW: Duration = Duration::from_millis(200);
const TAP_MOTION_PX: f32 = 6.0;

/// Two-finger tap → right-click detection. The second finger must
/// land and lift within this window of the first finger's down, and the
/// gesture must not drift past `TWO_FINGER_TAP_MOTION_PX`. Arms the right-
/// click on `ACTION_POINTER_DOWN`; the click only fires on
/// `ACTION_POINTER_UP` so a two-finger SCROLL (which moves before
/// lifting) disarms via the motion-accum path and doesn't accidentally
/// pop a context menu after a slow scroll on Samsung's keyboard-cover trackpad.
const TWO_FINGER_TAP_WINDOW: Duration = Duration::from_millis(300);
const TWO_FINGER_TAP_MOTION_PX: f32 = 6.0;

/// Tap-tap-drag window: time from the first tap's UP to the second
/// tap's DOWN. libinput's value; Android's touchpad library uses 150ms.
/// If the second touch lands within this window AND near the first
/// tap's position, it qualifies as the start of a tap-tap-drag rather
/// than an unrelated second tap, such as moving the pointer on after a
/// tap, which would otherwise pick up what was tapped.
const TAP_DRAG_WINDOW: Duration = Duration::from_millis(180);

/// "Hold finger 1, drag with finger 2" threshold. When the
/// second finger lands and the first finger has already been on the
/// pad for at least this long, interpret the gesture as a deliberate
/// hold-and-drag (Samsung's trackpad recognizes this as
/// click-and-drag; users widely report this gesture is what they
/// expect for text selection on Android trackpads). Below this
/// threshold (both fingers land near-simultaneously) the gesture is
/// scroll territory. 100ms is short enough that intentional holds
/// always register while preventing accidental engagement on
/// fast scroll gestures.
const HOLD_DRAG_THRESHOLD: Duration = Duration::from_millis(100);

/// Stationary requirement for the hold-finger-drag-finger gesture.
/// Finger 1 must have been still (no `AXIS_RELATIVE_*` motion) for at
/// least this long before finger 2 lands; otherwise the user is
/// actively driving the cursor with finger 1, and the second finger
/// landing means "scroll" not "start selection." Without this guard,
/// any time the user has finger 1 on the pad (even just resting it
/// while reading) and places finger 2 to scroll, hold-drag erroneously
/// engages and starts selecting text on the scroll gesture.
const HOLD_DRAG_STATIONARY: Duration = Duration::from_millis(80);

/// Tap-tap-drag position slop: the second touch must land within
/// this many logical pixels of the first tap's position to count as
/// part of the same gesture. Matches the click-position slop used by
/// our existing multi-click detection so feel is consistent.
const TAP_DRAG_POSITION_SLOP_PX: f32 = 6.0;

/// Tap-tap-drag motion-to-engage: how far the finger must move after
/// the second-tap DOWN before we commit to drag mode (and emit
/// `MouseDown(Left)`). Below this the system can still resolve the
/// gesture as a regular double-tap if the user lifts the finger
/// without dragging.
const TAP_DRAG_SLOP_PX: f32 = 2.0;

/// `window_id` sentinel for the primary `MainActivity` window. Extra
/// windows use their gpui-assigned non-zero `WindowId` so per-window
/// state never collides with the primary.
pub(crate) const PRIMARY_WINDOW_ID: u64 = 0;

/// Captured `MotionEvent` shape marshaled across JNI. One per event.
/// Pointer fields are indexed 0..pointer_count. `cursor_physical_*`
/// is Kotlin's authoritative cursor position in physical pixels
/// (decorView coordinate space) — Kotlin maintains it because the
/// software cursor View has to be positioned in the same space.
/// `window_id` is `PRIMARY_WINDOW_ID` for MainActivity events and
/// the extra window's id (matching `multi_window::ExtraWindowEvent`)
/// for spawned windows. State machines + hold-drag atomics are
/// per-window so a gesture in window A can't pollute window B's
/// drag or cursor-follow state.
#[derive(Debug)]
pub(crate) struct CapturedEvent {
    pub window_id: u64,
    pub action_masked: i32,
    pub source: i32,
    pub button_state: i32,
    /// `MotionEvent.getMetaState()`: the modifier keys held on any keyboard.
    pub meta_state: i32,
    pub pointer_count: usize,
    pub rxs: Vec<f32>,
    pub rys: Vec<f32>,
    pub vscroll: f32,
    pub hscroll: f32,
    pub cursor_physical_x: f32,
    pub cursor_physical_y: f32,
}

static EVENT_TX: Mutex<Option<mpsc::UnboundedSender<CapturedEvent>>> = Mutex::new(None);

/// Per-window hold-drag flag visible to Kotlin via
/// `Java_com_zdroid_NativeBridge_isHoldDragActive`. Kotlin reads on
/// every multi-touch MOVE to decide whether to update its on-screen
/// cursor sprite. During hold-and-drag the cursor follows the moving
/// finger (so the user sees the selection growing); during plain
/// two-finger scroll the cursor stays pinned. Rust flips per-window
/// on hold-drag entry and clears on teardown.
static HOLD_DRAG_ACTIVE_PER_WINDOW: Mutex<Option<HashMap<u64, bool>>> = Mutex::new(None);

fn set_hold_drag_active(window_id: u64, active: bool) {
    let mut guard = HOLD_DRAG_ACTIVE_PER_WINDOW
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    let map = guard.get_or_insert_with(HashMap::new);
    if active {
        map.insert(window_id, true);
    } else {
        map.remove(&window_id);
    }
}

fn is_hold_drag_active(window_id: u64) -> bool {
    HOLD_DRAG_ACTIVE_PER_WINDOW
        .lock()
        .unwrap()
        .as_ref()
        .and_then(|m| m.get(&window_id).copied())
        .unwrap_or(false)
}

/// Sets up the event channel and returns the receiver the platform's run
/// loop drains; the sender lives in `EVENT_TX` for the JNI thread.
pub(crate) fn init_event_channel() -> mpsc::UnboundedReceiver<CapturedEvent> {
    let (tx, rx) = mpsc::unbounded();
    *EVENT_TX.lock().unwrap_or_else(PoisonError::into_inner) = Some(tx);
    rx
}

/// Captured-pointer gesture state. Lives as a field on
/// `AndroidWindowState` so each window's gesture (hold-drag cursors,
/// tap-pending flags) is fully isolated from
/// gestures in other windows. Cursor position is owned by Kotlin and
/// threaded through every event so the on-screen cursor View and the
/// gpui-side cursor never drift.
#[derive(Default)]
pub(crate) struct CapturedPointerState {
    /// Set when `ACTION_BUTTON_PRESS` fired; we hold the button until
    /// `ACTION_BUTTON_RELEASE`. Used to populate `pressed_button` on
    /// subsequent move events.
    button_held: Option<MouseButton>,
    /// Latched on `ACTION_DOWN` (first finger). Cleared on Up, Cancel,
    /// or when motion exceeds `TAP_MOTION_PX`. Used to synthesize a
    /// click on a clean Down→Up with minimal motion.
    primary_down: Option<Instant>,
    /// When the first finger of the current gesture landed. Distinct
    /// from `primary_down` because the latter clears on small cursor
    /// motion (to prevent tap-on-up after a drag) but this stays
    /// stable through the whole gesture so hold-and-drag detection can
    /// measure how long the first finger has been on the pad before
    /// the second one landed. Cleared only on UP / CANCEL.
    first_finger_at: Option<Instant>,
    /// When the most recent non-zero motion delta arrived (any
    /// pointer count, any finger). Used to gate hold-and-drag entry:
    /// finger 2 only triggers the gesture if finger 1 has been still
    /// for `HOLD_DRAG_STATIONARY`. Cleared on DOWN / UP / CANCEL.
    last_motion_at: Option<Instant>,
    /// Accumulated cursor motion since the last tap anchor was set.
    /// Compared to `TAP_MOTION_PX` to decide whether a Down→Up
    /// sequence is a tap or a drag.
    motion_accum: f32,
    /// True when more than one finger is currently on the pad, so
    /// motion events synthesize scroll instead of cursor movement.
    in_multi_touch: bool,
    /// Set when `ACTION_POINTER_DOWN` (second finger) landed within
    /// the two-finger-tap window without significant motion. The
    /// actual right-click fires on `ACTION_POINTER_UP` so an in-
    /// flight two-finger SCROLL (which exceeds the motion threshold
    /// before lifting) clears this and doesn't synthesize a click.
    right_click_armed: bool,
    /// Tap-tap-drag tracking: when the most recent single-finger tap
    /// ended (ACTION_UP that synthesized a click). The next
    /// ACTION_DOWN within `TAP_DRAG_WINDOW` of this point near
    /// `last_tap_position` arms the drag.
    last_tap_at: Option<Instant>,
    /// Position of the most recent tap (in logical pixels). The
    /// second-tap ACTION_DOWN must land within `TAP_DRAG_POSITION_SLOP_PX`
    /// of this to qualify as part of a tap-tap-drag gesture.
    last_tap_position: Point<Pixels>,
    /// True between the second-tap ACTION_DOWN and the first MOVE
    /// past `TAP_DRAG_SLOP_PX`. If the user lifts the finger without
    /// moving (a plain double-tap), this clears and the second tap
    /// just becomes a second click. If they move past the slop, we
    /// commit to drag mode.
    tap_drag_pending: bool,
    /// True after either of the two drag-entry paths fires:
    ///   - tap-tap-drag's `tap_drag_pending` resolved past the motion
    ///     slop (single finger, second tap-and-drag), OR
    ///   - hold-and-drag fired on `ACTION_POINTER_DOWN` (finger 1 held
    ///     past `HOLD_DRAG_THRESHOLD` before finger 2 landed).
    /// Either way `MouseDown(Left)` is already emitted and we are in
    /// drag mode; subsequent MouseMove fires with Left held until UP /
    /// POINTER_UP, which emits the matching MouseUp.
    drag_active: bool,
    /// Set to `Some` while in hold-and-drag mode (drag_active &&
    /// engaged via two-finger entry), with where the drag is now.
    /// `None` for tap-tap-drag drags.
    hold_drag_cursor: Option<Point<Pixels>>,
    /// Last cursor position seen, in logical pixels. Kept so we can
    /// emit `MouseUp` / synthetic-tap events at the correct location
    /// even when the triggering event itself doesn't carry an updated
    /// cursor (e.g. `ACTION_BUTTON_RELEASE` may arrive without a
    /// fresh relative delta).
    last_cursor: Point<Pixels>,
    /// The run of clicks, for double and triple clicks: taps on the trackpad and
    /// a mouse's buttons, which all arrive here under capture.
    clicks: ClickTrackState,
    /// The two-finger scroll's glide after the fingers lift, which Android
    /// leaves to the app. Created on the first scroll, with the platform's
    /// gesture tuning; the run loop advances it once per frame.
    pub(crate) fling: Option<PointerFling>,
}

/// Push a captured event from the JVM listener thread onto the channel.
/// The game thread drains and synthesizes.
fn dispatch(event: CapturedEvent) {
    let guard = EVENT_TX.lock().unwrap_or_else(PoisonError::into_inner);
    let Some(tx) = guard.as_ref() else {
        log::warn!("captured_pointer: event arrived before init_event_channel");
        return;
    };
    if let Err(err) = tx.unbounded_send(event) {
        log::warn!("captured_pointer: dispatch failed: {err:#}");
    }
    crate::platform::wake_run_loop();
}

/// Translate one captured event into zero or more `PlatformInput`s,
/// updating internal state. Called on the game thread from the
/// platform's main loop drain. `scale_factor` is the surface's
/// physical-to-logical multiplier; we divide Kotlin's physical-pixel
/// cursor position by it to get logical pixels for gpui events.
pub(crate) fn translate(
    state: &mut CapturedPointerState,
    event: CapturedEvent,
    scale_factor: f32,
    tuning: GestureTuning,
) -> Vec<PlatformInput> {
    let modifiers =
        crate::events::keyboard::modifiers_from_meta(MetaState(event.meta_state as u32));
    let pressed_mouse_button = button_from_state(event.button_state);
    let scale = if scale_factor > 0.0 {
        scale_factor
    } else {
        1.0
    };
    let cursor = point(
        px(event.cursor_physical_x / scale),
        px(event.cursor_physical_y / scale),
    );

    let window_id = event.window_id;
    state.last_cursor = cursor;
    let mut out = Vec::new();

    match event.action_masked {
        JAVA_ACTION_DOWN => {
            // A finger on the pad stops a glide, as a touch stops a fling.
            if let Some(fling) = &mut state.fling {
                fling.stop();
            }
            // First finger landed. Three roles in priority order:
            //
            // 1. Defensive cleanup: if the previous gesture left
            //    `drag_active` or `button_held` set, this is leaked
            //    state — emit MouseUp and clear.
            // 2. Tap-tap-drag detection: if this DOWN follows a
            //    recent tap near the same position, arm
            //    `tap_drag_pending` so the next MOVE past slop
            //    commits to drag mode.
            // 3. Single-tap detection: latch the anchor so the
            //    matching UP can synthesize a click.
            if state.drag_active || state.button_held.is_some() {
                log::debug!(
                    "captured_pointer: stale drag state cleared on DOWN \
                         (drag_active={} button_held={:?} hold_drag_cursor={})",
                    state.drag_active,
                    state.button_held,
                    state.hold_drag_cursor.is_some(),
                );
                let release_pos = state.hold_drag_cursor.take().unwrap_or(state.last_cursor);
                if let Some(button) = state.button_held.take() {
                    out.push(PlatformInput::MouseUp(MouseUpEvent {
                        button,
                        position: release_pos,
                        modifiers,
                        click_count: 1,
                    }));
                }
                state.drag_active = false;
                set_hold_drag_active(window_id, false);
            }
            let now = Instant::now();
            let qualifies_as_tap_drag = state
                .last_tap_at
                .map(|t| now.duration_since(t) < TAP_DRAG_WINDOW)
                .unwrap_or(false)
                && (cursor - state.last_tap_position).magnitude()
                    <= TAP_DRAG_POSITION_SLOP_PX as f64;
            state.tap_drag_pending = qualifies_as_tap_drag;
            state.last_tap_at = None;
            state.primary_down = Some(now);
            state.first_finger_at = Some(now);
            state.last_motion_at = None;
            state.motion_accum = 0.0;
            state.in_multi_touch = false;
            state.right_click_armed = false;
        }
        JAVA_ACTION_POINTER_DOWN => {
            // Second-or-later finger landed. Three sub-cases:
            //
            // 1. We are ALREADY in hold-and-drag mode
            //    (drag_active && hold_drag_cursor.is_some()):
            //    this is finger 2 re-landing after the trackpad
            //    momentarily dropped its contact. Do NOT
            //    re-engage. Don't fire another MouseDown. Just
            //    keep multi-touch state set so subsequent MOVE
            //    events continue to grow the existing drag.
            // 2. Conditions for hold-and-drag entry met: enter
            //    drag mode at the current cursor.
            // 3. Neither: scroll territory. Arm right-click.
            let now = Instant::now();
            let hold_elapsed = state
                .first_finger_at
                .map(|t| now.duration_since(t))
                .unwrap_or_default();
            let stationary_for = state
                .last_motion_at
                .map(|t| now.duration_since(t))
                .unwrap_or(Duration::MAX);
            state.primary_down = None;
            state.in_multi_touch = true;
            state.tap_drag_pending = false;
            if state.drag_active && state.hold_drag_cursor.is_some() {
                // Already in hold-drag — finger 2 contact wavered
                // and re-landed. Keep going, don't re-emit.
                log::debug!(
                    "hold_drag: CONTINUE (finger re-landed during drag, \
                         drag_cursor=({:.0},{:.0}))",
                    f32::from(state.hold_drag_cursor.map(|c| c.x).unwrap_or(Pixels::ZERO),),
                    f32::from(state.hold_drag_cursor.map(|c| c.y).unwrap_or(Pixels::ZERO),),
                );
            } else if hold_elapsed >= HOLD_DRAG_THRESHOLD
                && stationary_for >= HOLD_DRAG_STATIONARY
                && !state.drag_active
            {
                log::debug!(
                    "hold_drag: ENGAGED anchor=({:.0},{:.0}) hold_ms={} stationary_ms={}",
                    f32::from(cursor.x),
                    f32::from(cursor.y),
                    hold_elapsed.as_millis(),
                    stationary_for.as_millis().min(99999),
                );
                state.drag_active = true;
                state.hold_drag_cursor = Some(cursor);
                state.right_click_armed = false;
                set_hold_drag_active(window_id, true);
                // Finger 1 may already be pressing the pad, which has sent the
                // mouse down.
                if state.button_held.is_none() {
                    state.button_held = Some(MouseButton::Left);
                    out.push(PlatformInput::MouseDown(MouseDownEvent {
                        button: MouseButton::Left,
                        position: cursor,
                        modifiers,
                        click_count: 1,
                        first_mouse: false,
                    }));
                }
            } else {
                log::debug!(
                    "hold_drag: SKIPPED hold_ms={} stationary_ms={} \
                         drag_active={} button_held={:?} (scroll path)",
                    hold_elapsed.as_millis(),
                    stationary_for.as_millis().min(99999),
                    state.drag_active,
                    state.button_held,
                );
                state.right_click_armed = event.pointer_count == 2
                    && hold_elapsed <= TWO_FINGER_TAP_WINDOW
                    && state.motion_accum <= TWO_FINGER_TAP_MOTION_PX;
                // A press still held would make the editor see
                // scroll-while-clicked, which it reads as drag-select.
                // Release it so the scroll stands alone.
                if let Some(button) = state.button_held.take() {
                    out.push(PlatformInput::MouseUp(MouseUpEvent {
                        button,
                        position: cursor,
                        modifiers,
                        click_count: 1,
                    }));
                }
            }
        }
        JAVA_ACTION_MOVE => {
            if event.pointer_count >= 2 || state.in_multi_touch {
                if state.hold_drag_cursor.is_some() {
                    // Hold-and-drag in progress: route motion as
                    // drag-MouseMove, NOT scroll. Kotlin moves the
                    // cursor by both fingers' motion while this lasts,
                    // and the drag follows it so it can't part from
                    // the sprite.
                    state.hold_drag_cursor = Some(cursor);
                    let moved = event
                        .rxs
                        .iter()
                        .chain(&event.rys)
                        .any(|delta| *delta != 0.0);
                    if moved {
                        state.last_motion_at = Some(Instant::now());
                        out.push(PlatformInput::MouseMove(MouseMoveEvent {
                            position: cursor,
                            pressed_button: state.button_held,
                            modifiers,
                        }));
                    }
                } else {
                    // Plain two-finger scroll (no hold-drag in
                    // flight). Centroid delta drives
                    // ScrollDelta::Pixels, and `fling` follows it for
                    // the glide after the fingers lift.
                    let n = event.pointer_count.max(1) as f32;
                    let sum_rx: f32 = event.rxs.iter().sum();
                    let sum_ry: f32 = event.rys.iter().sum();
                    let dx = sum_rx / n / scale;
                    let dy = sum_ry / n / scale;
                    // Captured trackpad motion arrives in small steps. Test
                    // the whole gesture so a slow scroll cannot end as a tap.
                    state.motion_accum += (dx * dx + dy * dy).sqrt();
                    if state.motion_accum > TWO_FINGER_TAP_MOTION_PX {
                        state.right_click_armed = false;
                    }
                    if dx != 0.0 || dy != 0.0 {
                        state.last_motion_at = Some(Instant::now());
                        let delta = crate::ime::invert_scroll_delta(ScrollDelta::Pixels(point(
                            px(dx),
                            px(dy),
                        )));
                        if let ScrollDelta::Pixels(pixels) = delta {
                            state
                                .fling
                                .get_or_insert_with(|| PointerFling::new(tuning))
                                .scrolled(pixels);
                        }
                        out.push(PlatformInput::Scroll(ScrollEvent {
                            position: cursor,
                            delta,
                            modifiers,
                            touch_phase: TouchPhase::Moved,
                            source: ScrollSource::Pointer,
                        }));
                    }
                }
            } else if event.pointer_count == 1 {
                // Single-finger move. Cursor is already at the
                // post-delta position (Kotlin updated it before
                // forwarding). Track motion accumulator so tap
                // synthesis gets cancelled if the finger drifts
                // past the tap threshold.
                let dx = *event.rxs.first().unwrap_or(&0.0);
                let dy = *event.rys.first().unwrap_or(&0.0);
                let mag = (dx * dx + dy * dy).sqrt();
                state.motion_accum += mag;
                if mag > 0.0 {
                    state.last_motion_at = Some(Instant::now());
                }
                if state.motion_accum > TAP_MOTION_PX {
                    state.primary_down = None;
                }

                // Tap-tap-drag commit: if a previous tap armed
                // `tap_drag_pending` on this DOWN, the first motion
                // past slop transitions us into drag mode. Emit
                // MouseDown(Left) at the original tap position
                // (the anchor, where the user expected the drag
                // to start from) and subsequent MouseMove events
                // grow the drag.
                if state.tap_drag_pending
                    && state.motion_accum > TAP_DRAG_SLOP_PX
                    && state.button_held.is_none()
                {
                    log::debug!(
                        "tap_drag: ENGAGED anchor=({:.0},{:.0}) cursor=({:.0},{:.0})",
                        f32::from(state.last_tap_position.x),
                        f32::from(state.last_tap_position.y),
                        f32::from(cursor.x),
                        f32::from(cursor.y),
                    );
                    state.tap_drag_pending = false;
                    state.drag_active = true;
                    state.hold_drag_cursor = None;
                    state.button_held = Some(MouseButton::Left);
                    out.push(PlatformInput::MouseDown(MouseDownEvent {
                        button: MouseButton::Left,
                        position: state.last_tap_position,
                        modifiers,
                        click_count: 1,
                        first_mouse: false,
                    }));
                }

                out.push(PlatformInput::MouseMove(MouseMoveEvent {
                    position: cursor,
                    pressed_button: state.button_held,
                    modifiers,
                }));
            }
        }
        JAVA_ACTION_UP => {
            // Last finger lifted, which ends a press or a drag at once,
            // as Android's own touchpad gestures and libinput do by
            // default. Otherwise it may finish a plain tap: fresh
            // `primary_down` anchor and low motion synthesize a click.
            state.in_multi_touch = false;
            set_hold_drag_active(window_id, false);
            if let Some(button) = state.button_held.take() {
                let release_pos = state.hold_drag_cursor.take().unwrap_or(cursor);
                out.push(PlatformInput::MouseUp(MouseUpEvent {
                    button,
                    position: release_pos,
                    modifiers,
                    click_count: 1,
                }));
                state.drag_active = false;
            } else if let Some(anchor) = state.primary_down.take()
                && anchor.elapsed() < TAP_WINDOW
                && state.motion_accum <= TAP_MOTION_PX
            {
                let click_count = state.clicks.next_click_count(MouseButton::Left, cursor);
                out.push(PlatformInput::MouseDown(MouseDownEvent {
                    button: MouseButton::Left,
                    position: cursor,
                    modifiers,
                    click_count,
                    first_mouse: false,
                }));
                out.push(PlatformInput::MouseUp(MouseUpEvent {
                    button: MouseButton::Left,
                    position: cursor,
                    modifiers,
                    click_count,
                }));
                // Record this tap so a subsequent ACTION_DOWN
                // within `TAP_DRAG_WINDOW` can arm tap-tap-drag
                // for text selection.
                state.last_tap_at = Some(Instant::now());
                state.last_tap_position = cursor;
            }
            state.motion_accum = 0.0;
            state.right_click_armed = false;
            state.first_finger_at = None;
            state.last_motion_at = None;
            state.tap_drag_pending = false;
        }
        JAVA_ACTION_POINTER_UP => {
            // A non-primary finger lifted. CRITICAL: do not tear
            // down hold-and-drag here. The cover trackpad's
            // finger-2 contact wavers during sustained gestures
            // (verified empirically: same-anchor hold_drag ENGAGED
            // fires repeatedly through POINTER_UP/POINTER_DOWN
            // cycles during one continuous user drag). If we tear
            // down on POINTER_UP, the next POINTER_DOWN re-engages
            // hold-drag and re-fires MouseDown, producing the
            // click-storm. Only ACTION_UP (last finger lifted)
            // ends the gesture.
            //
            // Two things still need to happen here:
            //   - Fire right-click if armed and motion stayed
            //     under the tap threshold (genuine two-finger tap)
            //   - When pointer_count drops to 1, clear
            //     in_multi_touch so subsequent single-finger MOVE
            //     events route through the cursor path
            if state.right_click_armed
                && !state.drag_active
                && state
                    .first_finger_at
                    .is_some_and(|started| started.elapsed() <= TWO_FINGER_TAP_WINDOW)
            {
                out.push(PlatformInput::MouseDown(MouseDownEvent {
                    button: MouseButton::Right,
                    position: cursor,
                    modifiers,
                    click_count: 1,
                    first_mouse: false,
                }));
                out.push(PlatformInput::MouseUp(MouseUpEvent {
                    button: MouseButton::Right,
                    position: cursor,
                    modifiers,
                    click_count: 1,
                }));
            }
            state.right_click_armed = false;
            // Keep in_multi_touch true while hold-drag is active
            // (finger 2 wavering doesn't end the gesture; only
            // ACTION_UP does). Clear it otherwise so single-finger
            // motion routes through the cursor path, not scroll.
            if event.pointer_count <= 2 && state.hold_drag_cursor.is_none() {
                state.in_multi_touch = false;
                // The two-finger scroll ends as the second finger lifts:
                // the last one usually follows too late to still count as
                // moving.
                if let Some(fling) = &mut state.fling
                    && !state.drag_active
                {
                    fling.release(cursor);
                }
            }
        }
        JAVA_ACTION_BUTTON_PRESS => {
            // Samsung's keyboard-cover trackpad reports a press only when the pad
            // clicks, not when a finger rests on it. A press with two fingers
            // stays the two-finger gesture, and one during a tap or hold drag
            // belongs to the button that drag already holds.
            let is_touchpad = event.source & ANDROID_SOURCE_TOUCHPAD == ANDROID_SOURCE_TOUCHPAD;
            if !is_touchpad || (event.pointer_count == 1 && state.button_held.is_none()) {
                let button = pressed_mouse_button.unwrap_or(MouseButton::Left);
                state.button_held = Some(button);
                state.primary_down = None;
                let click_count = state.clicks.next_click_count(button, cursor);
                out.push(PlatformInput::MouseDown(MouseDownEvent {
                    button,
                    position: cursor,
                    modifiers,
                    click_count,
                    first_mouse: false,
                }));
            }
        }
        JAVA_ACTION_BUTTON_RELEASE => {
            if let Some(button) = state.button_held.take() {
                out.push(PlatformInput::MouseUp(MouseUpEvent {
                    button,
                    position: state.hold_drag_cursor.take().unwrap_or(cursor),
                    modifiers,
                    click_count: state.clicks.click_count(),
                }));
                state.drag_active = false;
                set_hold_drag_active(window_id, false);
            }
        }
        JAVA_ACTION_SCROLL => {
            // Mouse wheel. Android carries detents in AXIS_VSCROLL /
            // AXIS_HSCROLL; Kotlin forwards them as `vscroll` /
            // `hscroll`, but no arm consumed them before, so the wheel
            // did nothing. Emit a Lines-based Scroll event so the editor
            // scales by line height like the desktop discrete-wheel
            // path. Signs as in `events::mouse::wheel_scroll`.
            out.extend(mouse::wheel_scroll(
                event.vscroll,
                event.hscroll,
                cursor,
                modifiers,
            ));
        }
        JAVA_ACTION_CANCEL => {
            if let Some(fling) = &mut state.fling {
                fling.stop();
            }
            if let Some(button) = state.button_held.take() {
                let release_pos = state.hold_drag_cursor.take().unwrap_or(cursor);
                out.push(PlatformInput::MouseUp(MouseUpEvent {
                    button,
                    position: release_pos,
                    modifiers,
                    click_count: 0,
                }));
            }
            state.primary_down = None;
            state.first_finger_at = None;
            state.last_motion_at = None;
            state.motion_accum = 0.0;
            state.in_multi_touch = false;
            state.right_click_armed = false;
            state.tap_drag_pending = false;
            state.drag_active = false;
            state.hold_drag_cursor = None;
            set_hold_drag_active(window_id, false);
        }
        _ => {}
    }
    out
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_zdroid_NativeBridge_isHoldDragActive<'local>(
    _env: jni::JNIEnv<'local>,
    _bridge: JObject<'local>,
    window_id: jni::sys::jlong,
) -> bool {
    is_hold_drag_active(window_id as u64)
}

/// Sink for `NativeBridge.nativeOnCapturedPointer`, which `WindowPointer.kt`
/// calls for the main window (`PRIMARY_WINDOW_ID`) and extra windows.
/// Builds a `CapturedEvent` and queues it for the game thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_zdroid_NativeBridge_nativeOnCapturedPointer<'local>(
    env: jni::JNIEnv<'local>,
    _bridge: JObject<'local>,
    window_id: jni::sys::jlong,
    action_masked: i32,
    source: i32,
    button_state: i32,
    meta_state: i32,
    pointer_count: i32,
    rxs: JFloatArray<'local>,
    rys: JFloatArray<'local>,
    vscroll: f32,
    hscroll: f32,
    cursor_physical_x: f32,
    cursor_physical_y: f32,
) {
    let n = pointer_count.max(0) as usize;
    let rxs = read_floats(&env, &rxs, n);
    let rys = read_floats(&env, &rys, n);
    dispatch(CapturedEvent {
        window_id: window_id as u64,
        action_masked,
        source,
        button_state,
        meta_state,
        pointer_count: n,
        rxs,
        rys,
        vscroll,
        hscroll,
        cursor_physical_x,
        cursor_physical_y,
    });
}

fn read_floats<'local>(
    env: &jni::JNIEnv<'local>,
    array: &JFloatArray<'local>,
    expected_len: usize,
) -> Vec<f32> {
    let mut buf = vec![0.0f32; expected_len];
    if expected_len == 0 {
        return buf;
    }
    if let Err(err) = env.get_float_array_region(array, 0, buf.as_mut_slice()) {
        log::warn!("captured_pointer: get_float_array_region: {err:#}");
        buf.clear();
    }
    buf
}

#[cfg(test)]
mod tests {
    use super::*;

    const SOURCE_MOUSE: i32 = 0x2002;

    fn feed(
        state: &mut CapturedPointerState,
        action_masked: i32,
        source: i32,
        pointer_count: usize,
        delta_x: f32,
        cursor_x: f32,
    ) -> Vec<PlatformInput> {
        let event = CapturedEvent {
            window_id: PRIMARY_WINDOW_ID + 1000,
            action_masked,
            source,
            button_state: 0,
            meta_state: 0,
            pointer_count,
            rxs: vec![delta_x; pointer_count],
            rys: vec![0.0; pointer_count],
            vscroll: 0.0,
            hscroll: 0.0,
            cursor_physical_x: cursor_x,
            cursor_physical_y: 0.0,
        };
        translate(state, event, 1.0, GestureTuning::default())
    }

    fn pad(
        state: &mut CapturedPointerState,
        action_masked: i32,
        pointer_count: usize,
        delta_x: f32,
        cursor_x: f32,
    ) -> Vec<PlatformInput> {
        feed(
            state,
            action_masked,
            ANDROID_SOURCE_TOUCHPAD,
            pointer_count,
            delta_x,
            cursor_x,
        )
    }

    #[test]
    fn pressing_the_pad_drags_until_it_is_released() {
        let mut state = CapturedPointerState::default();
        assert!(pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.).is_empty());
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_BUTTON_PRESS, 1, 0., 10.).as_slice(),
            [PlatformInput::MouseDown(down)] if down.button == MouseButton::Left
        ));
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_MOVE, 1, 40., 50.).as_slice(),
            [PlatformInput::MouseMove(moved)] if moved.pressed_button == Some(MouseButton::Left)
        ));
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_BUTTON_RELEASE, 1, 0., 50.).as_slice(),
            [PlatformInput::MouseUp(up)] if up.position.x == px(50.)
        ));
        assert!(pad(&mut state, JAVA_ACTION_UP, 1, 0., 50.).is_empty());
    }

    #[test]
    fn lifting_the_finger_ends_a_press_the_pad_never_released() {
        let mut state = CapturedPointerState::default();
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        pad(&mut state, JAVA_ACTION_BUTTON_PRESS, 1, 0., 10.);
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_UP, 1, 0., 10.).as_slice(),
            [PlatformInput::MouseUp(_)]
        ));
        assert!(pad(&mut state, JAVA_ACTION_BUTTON_RELEASE, 1, 0., 10.).is_empty());
    }

    #[test]
    fn a_finger_without_a_press_only_moves_the_pointer() {
        let mut state = CapturedPointerState::default();
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_MOVE, 1, 40., 50.).as_slice(),
            [PlatformInput::MouseMove(moved)] if moved.pressed_button.is_none()
        ));
        assert!(pad(&mut state, JAVA_ACTION_UP, 1, 0., 50.).is_empty());
    }

    #[test]
    fn a_press_with_two_fingers_stays_a_right_click() {
        let mut state = CapturedPointerState::default();
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        pad(&mut state, JAVA_ACTION_POINTER_DOWN, 2, 0., 10.);
        assert!(pad(&mut state, JAVA_ACTION_BUTTON_PRESS, 2, 0., 10.).is_empty());
        assert!(pad(&mut state, JAVA_ACTION_BUTTON_RELEASE, 2, 0., 10.).is_empty());
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_POINTER_UP, 2, 0., 10.).as_slice(),
            [PlatformInput::MouseDown(down), PlatformInput::MouseUp(_)]
                if down.button == MouseButton::Right
        ));
    }

    #[test]
    fn a_second_finger_during_a_press_continues_the_same_drag() {
        let mut state = CapturedPointerState::default();
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        state.first_finger_at = Some(Instant::now() - HOLD_DRAG_THRESHOLD);
        pad(&mut state, JAVA_ACTION_BUTTON_PRESS, 1, 0., 10.);
        assert!(pad(&mut state, JAVA_ACTION_POINTER_DOWN, 2, 0., 10.).is_empty());
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_MOVE, 2, 20., 90.).as_slice(),
            [PlatformInput::MouseMove(moved)]
                if moved.pressed_button == Some(MouseButton::Left) && moved.position.x == px(90.)
        ));
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_BUTTON_RELEASE, 2, 0., 90.).as_slice(),
            [PlatformInput::MouseUp(up)] if up.position.x == px(90.)
        ));
        assert!(!state.drag_active);
    }

    #[test]
    fn lifting_the_finger_ends_a_tap_drag_at_once() {
        let mut state = CapturedPointerState::default();
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        pad(&mut state, JAVA_ACTION_UP, 1, 0., 10.);
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_MOVE, 1, 40., 50.).as_slice(),
            [PlatformInput::MouseDown(_), PlatformInput::MouseMove(_)]
        ));
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_UP, 1, 0., 50.).as_slice(),
            [PlatformInput::MouseUp(up)] if up.position.x == px(50.)
        ));
        assert!(!state.drag_active);
        assert!(pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 50.).is_empty());
    }

    #[test]
    fn a_press_during_a_tap_drag_sends_no_second_mouse_down() {
        let mut state = CapturedPointerState::default();
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        pad(&mut state, JAVA_ACTION_UP, 1, 0., 10.);
        pad(&mut state, JAVA_ACTION_DOWN, 1, 0., 10.);
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_MOVE, 1, 10., 20.).as_slice(),
            [PlatformInput::MouseDown(_), PlatformInput::MouseMove(_)]
        ));
        assert!(pad(&mut state, JAVA_ACTION_BUTTON_PRESS, 1, 0., 20.).is_empty());
        assert!(matches!(
            pad(&mut state, JAVA_ACTION_BUTTON_RELEASE, 1, 0., 20.).as_slice(),
            [PlatformInput::MouseUp(_)]
        ));
    }

    #[test]
    fn a_mouse_button_still_clicks() {
        let mut state = CapturedPointerState::default();
        assert!(matches!(
            feed(
                &mut state,
                JAVA_ACTION_BUTTON_PRESS,
                SOURCE_MOUSE,
                1,
                0.,
                10.
            )
            .as_slice(),
            [PlatformInput::MouseDown(_)]
        ));
        assert!(matches!(
            feed(
                &mut state,
                JAVA_ACTION_BUTTON_RELEASE,
                SOURCE_MOUSE,
                1,
                0.,
                10.
            )
            .as_slice(),
            [PlatformInput::MouseUp(_)]
        ));
    }
}
