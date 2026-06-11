//! Sync engine (D-EPIC-3). D301: deterministic state machine driving the
//! sync pill UX — every (state, event) pair maps to exactly one next state.

pub mod backoff;
pub mod drain;
pub mod error;
pub mod machine;

pub use backoff::Backoff;
pub use drain::{ChangePusher, DrainOutcome, HttpChangePusher, drain};
pub use error::SyncErrorCategory;
pub use machine::{SyncEvent, SyncState};
