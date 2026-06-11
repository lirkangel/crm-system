//! D301 — sync state machine.
//!
//! ```text
//!          ConnectivityUp                DrainStarted
//! Offline ───────────────▶ Online ───────────────────▶ Syncing
//!    ▲                       ▲   ◀─────────────────────   │
//!    │                       │     DrainCompleted /        │ ConflictDetected
//!    │                       │     TransientFailure        ▼
//!    └── ConnectivityDown ───┴──────────────────────── Conflict
//!        (from any state)          ConflictResolved ──▶ Syncing
//! ```
//!
//! `TransientFailure` returns to `Online` — the backoff retry schedule is
//! D303's concern; the machine only tracks what the UI needs to show.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SyncState {
    Offline,
    Online,
    Syncing,
    Conflict,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SyncEvent {
    ConnectivityUp,
    ConnectivityDown,
    DrainStarted,
    DrainCompleted,
    ConflictDetected,
    ConflictResolved,
    TransientFailure,
}

impl SyncState {
    /// Deterministic transition: unexpected events leave the state unchanged.
    pub fn next(self, event: SyncEvent) -> SyncState {
        use SyncEvent::*;
        use SyncState::*;
        match (self, event) {
            (_, ConnectivityDown) => Offline,
            (Offline, ConnectivityUp) => Online,
            (Online, DrainStarted) => Syncing,
            (Syncing, DrainCompleted) => Online,
            (Syncing, TransientFailure) => Online,
            (Syncing, ConflictDetected) => Conflict,
            (Conflict, ConflictResolved) => Syncing,
            (unchanged, _) => unchanged,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::SyncEvent::*;
    use super::SyncState::*;
    use super::*;

    #[test]
    fn happy_path_drains_queue_and_returns_online() {
        let state = Offline
            .next(ConnectivityUp)
            .next(DrainStarted)
            .next(DrainCompleted);

        assert_eq!(state, Online);
    }

    #[test]
    fn conflict_pauses_sync_until_resolved() {
        let conflicted = Online.next(DrainStarted).next(ConflictDetected);
        assert_eq!(conflicted, Conflict);

        assert_eq!(conflicted.next(ConflictResolved), Syncing);
    }

    #[test]
    fn connectivity_loss_wins_from_every_state() {
        for state in [Offline, Online, Syncing, Conflict] {
            assert_eq!(state.next(ConnectivityDown), Offline);
        }
    }

    #[test]
    fn transient_failure_returns_online_for_backoff_retry() {
        assert_eq!(Syncing.next(TransientFailure), Online);
    }

    #[test]
    fn unexpected_events_leave_state_unchanged() {
        assert_eq!(Offline.next(DrainStarted), Offline);
        assert_eq!(Offline.next(DrainCompleted), Offline);
        assert_eq!(Online.next(ConflictResolved), Online);
        assert_eq!(Online.next(ConnectivityUp), Online);
        assert_eq!(Conflict.next(DrainStarted), Conflict);
        assert_eq!(Syncing.next(ConnectivityUp), Syncing);
    }

    #[test]
    fn transitions_are_deterministic() {
        for state in [Offline, Online, Syncing, Conflict] {
            for event in [
                ConnectivityUp,
                ConnectivityDown,
                DrainStarted,
                DrainCompleted,
                ConflictDetected,
                ConflictResolved,
                TransientFailure,
            ] {
                assert_eq!(state.next(event), state.next(event));
            }
        }
    }
}
