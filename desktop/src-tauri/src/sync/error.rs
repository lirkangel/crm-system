//! D304 — typed sync error categories. Each category routes to one UX path;
//! only transient categories are retried (with D303's backoff).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SyncErrorCategory {
    /// Connection refused / DNS / timeout — retry with backoff.
    Network,
    /// 409 optimistic-lock rejection — user resolves via conflict dialog.
    Conflict,
    /// 5xx — server-side trouble, retry with backoff.
    ServerError,
    /// 401 — refresh token flow, then retry once re-authenticated.
    AuthExpired,
    /// Server doesn't know the plugin that owns this entity (e.g. disabled).
    PluginUnknown,
    /// 4xx payload rejection — never retried, surfaced to the user.
    Malformed,
}

impl SyncErrorCategory {
    /// Retry (with backoff) only where a later attempt can succeed unchanged.
    pub fn is_transient(self) -> bool {
        matches!(self, SyncErrorCategory::Network | SyncErrorCategory::ServerError)
    }

    /// Maps an HTTP response status to a category. Timeouts and transport
    /// errors never reach here — they are `Network` at the call site.
    pub fn from_status(status: u16) -> SyncErrorCategory {
        match status {
            401 => SyncErrorCategory::AuthExpired,
            409 => SyncErrorCategory::Conflict,
            404 | 410 => SyncErrorCategory::PluginUnknown,
            400..=499 => SyncErrorCategory::Malformed,
            _ => SyncErrorCategory::ServerError,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::SyncErrorCategory::*;
    use super::*;

    #[test]
    fn statuses_map_to_documented_categories() {
        assert_eq!(SyncErrorCategory::from_status(401), AuthExpired);
        assert_eq!(SyncErrorCategory::from_status(409), Conflict);
        assert_eq!(SyncErrorCategory::from_status(404), PluginUnknown);
        assert_eq!(SyncErrorCategory::from_status(410), PluginUnknown);
        assert_eq!(SyncErrorCategory::from_status(400), Malformed);
        assert_eq!(SyncErrorCategory::from_status(422), Malformed);
        assert_eq!(SyncErrorCategory::from_status(500), ServerError);
        assert_eq!(SyncErrorCategory::from_status(503), ServerError);
    }

    #[test]
    fn only_network_and_server_errors_are_transient() {
        assert!(Network.is_transient());
        assert!(ServerError.is_transient());
        assert!(!Conflict.is_transient());
        assert!(!AuthExpired.is_transient());
        assert!(!PluginUnknown.is_transient());
        assert!(!Malformed.is_transient());
    }
}
