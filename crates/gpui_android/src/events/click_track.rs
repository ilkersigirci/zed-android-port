//! Click + button-state tracking shared by mouse and touch dispatchers.
//!
//! Three pieces of state:
//! - **Primary anchor.** Position + time the primary button (mouse Left,
//!   first finger) went down. Drives `finalize_up` so a release knows
//!   which button to emit.
//! - **Held non-primary.** Set when the dispatcher emits a non-primary
//!   button-down (right / middle / nav). The matching Up resolves to
//!   the same button rather than defaulting to Left.
//! - **Last click run.** Time + position + button + run length of the
//!   most recent Down. A new Down close enough in time and place bumps
//!   the run length; the editor's word- / line-select keys off the
//!   resulting `click_count`.
//!
//! Per-window owned (lives as a field on `AndroidWindowState`). Each
//! window has its own click state so a tap in window A doesn't bleed
//! into window B's double-click run.

use std::time::{Duration, Instant};

use gpui::{MouseButton, Pixels, Point};

/// As Zed's Linux backends do (`DOUBLE_CLICK_INTERVAL` and
/// `DOUBLE_CLICK_DISTANCE` in `gpui_linux`).
const MULTI_CLICK_INTERVAL: Duration = Duration::from_millis(400);
const MULTI_CLICK_DISTANCE: f64 = 5.0;

/// Per-window click + button-state tracker. Default = all `None`.
#[derive(Default)]
pub(crate) struct ClickTrackState {
    /// Time + position of the most recent primary Down (mouse Left, or
    /// first finger). Cleared by `finalize_up` on the matching Up.
    primary_down: Option<(Instant, Point<Pixels>)>,
    /// Currently-held non-primary mouse button (right / middle / nav).
    /// Set when the dispatcher emits its Down so the matching Up
    /// resolves to the same button.
    held_non_primary: Option<MouseButton>,
    /// Last Down's timestamp + position + button + run length.
    last_click: Option<(Instant, Point<Pixels>, MouseButton, usize)>,
}

/// Outcome of the final `Up` (last pointer lifted or mouse-button released).
pub(crate) enum UpOutcome {
    /// Emit `MouseUp(button)` to close the gesture.
    Emit(MouseButton),
    /// Nothing to emit (e.g. gesture resolved internally).
    None,
}

impl ClickTrackState {
    /// Latch the primary Down position + time. Caller emits the
    /// `MouseDown(Left)` separately. Also clears any non-primary hold.
    pub(crate) fn record_primary_down(&mut self, position: Point<Pixels>) {
        self.primary_down = Some((Instant::now(), position));
        self.held_non_primary = None;
    }

    /// Caller tells us they emitted a `MouseDown(button)` on a non-
    /// primary button so the corresponding Up resolves to `Up(button)`
    /// instead of `Up(Left)`.
    pub(crate) fn mark_non_primary_down(&mut self, button: MouseButton) {
        self.held_non_primary = Some(button);
    }

    /// Currently-held non-primary button (if any). Move handlers use
    /// this to populate `MouseMoveEvent::pressed_button`.
    pub(crate) fn current_non_primary(&self) -> Option<MouseButton> {
        self.held_non_primary
    }

    /// The `click_count` for a new Down of `button` at `position`, which the
    /// caller puts on the `MouseDownEvent`: the same button, close enough in
    /// time and place to the last Down, continues its run.
    pub(crate) fn next_click_count(
        &mut self,
        button: MouseButton,
        position: Point<Pixels>,
    ) -> usize {
        let now = Instant::now();
        let count = match self.last_click {
            Some((last_time, last_position, last_button, last_count))
                if last_button == button
                    && now.duration_since(last_time) < MULTI_CLICK_INTERVAL
                    && (position - last_position).magnitude() <= MULTI_CLICK_DISTANCE =>
            {
                last_count + 1
            }
            _ => 1,
        };
        self.last_click = Some((now, position, button, count));
        count
    }

    /// The run length of the last Down. gpui reads a click's count from its
    /// `MouseUp`, so the Up has to carry it too.
    pub(crate) fn click_count(&self) -> usize {
        self.last_click.map_or(1, |(_, _, _, count)| count)
    }

    /// Resolve the gesture-closing Up. Held non-primary wins (so e.g. a
    /// right-drag emits `MouseUp(Right)`), else primary Down resolves
    /// to `Up(Left)`, else nothing to emit.
    pub(crate) fn finalize_up(&mut self) -> UpOutcome {
        let held = self.held_non_primary.take();
        let had_primary = self.primary_down.take().is_some();
        if let Some(button) = held {
            UpOutcome::Emit(button)
        } else if had_primary {
            UpOutcome::Emit(MouseButton::Left)
        } else {
            UpOutcome::None
        }
    }

    /// Clear primary + non-primary latches. Called on `Cancel`.
    pub(crate) fn reset_all(&mut self) {
        self.primary_down = None;
        self.held_non_primary = None;
    }
}
