//! Sync engine (D-EPIC-3). D301: deterministic state machine driving the
//! sync pill UX — every (state, event) pair maps to exactly one next state.

pub mod machine;

pub use machine::{SyncEvent, SyncState};
