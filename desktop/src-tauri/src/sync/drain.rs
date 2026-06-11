//! D302/D303 — drain the pending-changes queue in order with optimistic
//! locking (`If-Match: <base_version>`), classifying each response:
//! 2xx → synced (dequeue, continue) · 409 → conflict (stop, user resolves) ·
//! 5xx/timeout → transient (stop, caller retries with backoff) ·
//! other 4xx → fatal (stop, surfaced; never retried).

use crate::cache::CacheDb;
use crate::cache::queue::PendingChange;
use crate::sync::SyncErrorCategory;

/// How a single push (or the whole drain) ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DrainOutcome {
    /// Queue fully drained.
    Drained { synced: usize },
    /// 409 on this change — sync pauses until the user resolves it.
    Conflict { change_id: i64, synced: usize },
    /// Transient failure — retry the remaining queue after backoff.
    Retry { category: SyncErrorCategory, synced: usize },
    /// Non-retryable rejection (malformed/auth/plugin) — surfaced to UX.
    Fatal { category: SyncErrorCategory, change_id: i64, synced: usize },
}

/// Transport abstraction so the drain loop is testable; the real
/// implementation is [`HttpChangePusher`].
pub trait ChangePusher {
    /// Pushes one change; returns the HTTP status, or `Err(())` for
    /// transport-level failures (timeout, refused) — always `Network`.
    fn push(&self, change: &PendingChange) -> impl Future<Output = Result<u16, ()>>;
}

/// Drains queued changes oldest-first, dequeuing each as the server accepts it.
pub async fn drain<P: ChangePusher>(db: &CacheDb, pusher: &P) -> DrainOutcome {
    let mut synced = 0;
    let queued = match db.pending_changes() {
        Ok(queued) => queued,
        Err(_) => {
            return DrainOutcome::Retry { category: SyncErrorCategory::Malformed, synced };
        }
    };
    for change in queued {
        match pusher.push(&change).await {
            Ok(status) if (200..300).contains(&status) => {
                let _ = db.remove_change(change.id);
                synced += 1;
            }
            Ok(409) => {
                return DrainOutcome::Conflict { change_id: change.id, synced };
            }
            Ok(status) => {
                let category = SyncErrorCategory::from_status(status);
                if category.is_transient() {
                    return DrainOutcome::Retry { category, synced };
                }
                return DrainOutcome::Fatal { category, change_id: change.id, synced };
            }
            Err(()) => {
                return DrainOutcome::Retry { category: SyncErrorCategory::Network, synced };
            }
        }
    }
    DrainOutcome::Drained { synced }
}

/// Real pusher: `PUT {base}/api/v1/entities/{type}/{id}` with
/// `If-Match: <base_version>` (creates use op POST semantics server-side).
pub struct HttpChangePusher {
    client: reqwest::Client,
    base_url: String,
    access_token: String,
}

impl HttpChangePusher {
    pub fn new(base_url: impl Into<String>, access_token: impl Into<String>) -> Self {
        Self {
            client: reqwest::Client::new(),
            base_url: base_url.into(),
            access_token: access_token.into(),
        }
    }

    fn url_for(&self, change: &PendingChange) -> String {
        format!(
            "{}/api/v1/entities/{}/{}",
            self.base_url.trim_end_matches('/'),
            change.entity_type,
            change.entity_id
        )
    }
}

impl ChangePusher for HttpChangePusher {
    async fn push(&self, change: &PendingChange) -> Result<u16, ()> {
        let mut request = self
            .client
            .put(self.url_for(change))
            .bearer_auth(&self.access_token)
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(change.payload_json.clone());
        if let Some(version) = change.base_version {
            request = request.header(reqwest::header::IF_MATCH, version.to_string());
        }
        match request.send().await {
            Ok(response) => Ok(response.status().as_u16()),
            Err(_) => Err(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cache::queue::NewPendingChange;
    use std::cell::RefCell;

    fn queued_change(db: &CacheDb, entity_id: &str, queued_at: &str) {
        db.enqueue_change(&NewPendingChange {
            op: "UPDATE".to_string(),
            entity_type: "User".to_string(),
            entity_id: entity_id.to_string(),
            payload_json: format!("{{\"id\":\"{entity_id}\"}}"),
            base_version: Some(7),
            queued_at: queued_at.to_string(),
        })
        .unwrap();
    }

    /// Scripted pusher: pops the next status (or transport error) per call.
    struct ScriptedPusher {
        script: RefCell<Vec<Result<u16, ()>>>,
        pushed: RefCell<Vec<String>>,
    }

    impl ScriptedPusher {
        fn new(script: Vec<Result<u16, ()>>) -> Self {
            Self { script: RefCell::new(script), pushed: RefCell::new(Vec::new()) }
        }
    }

    impl ChangePusher for ScriptedPusher {
        async fn push(&self, change: &PendingChange) -> Result<u16, ()> {
            self.pushed.borrow_mut().push(change.entity_id.clone());
            self.script.borrow_mut().remove(0)
        }
    }

    #[tokio::test]
    async fn drains_queue_in_order_and_dequeues_synced_changes() {
        let db = CacheDb::open_in_memory().unwrap();
        queued_change(&db, "u-1", "2026-06-11T10:00:00Z");
        queued_change(&db, "u-2", "2026-06-11T10:01:00Z");
        let pusher = ScriptedPusher::new(vec![Ok(200), Ok(200)]);

        let outcome = drain(&db, &pusher).await;

        assert_eq!(outcome, DrainOutcome::Drained { synced: 2 });
        assert_eq!(*pusher.pushed.borrow(), vec!["u-1", "u-2"]);
        assert!(db.pending_changes().unwrap().is_empty());
    }

    #[tokio::test]
    async fn conflict_stops_drain_and_keeps_change_queued() {
        let db = CacheDb::open_in_memory().unwrap();
        queued_change(&db, "u-1", "2026-06-11T10:00:00Z");
        queued_change(&db, "u-2", "2026-06-11T10:01:00Z");
        let pusher = ScriptedPusher::new(vec![Ok(409)]);

        let outcome = drain(&db, &pusher).await;

        let queued = db.pending_changes().unwrap();
        assert_eq!(queued.len(), 2, "nothing dequeued on conflict");
        assert_eq!(outcome, DrainOutcome::Conflict { change_id: queued[0].id, synced: 0 });
    }

    #[tokio::test]
    async fn server_error_stops_drain_for_backoff_retry() {
        let db = CacheDb::open_in_memory().unwrap();
        queued_change(&db, "u-1", "2026-06-11T10:00:00Z");
        queued_change(&db, "u-2", "2026-06-11T10:01:00Z");
        let pusher = ScriptedPusher::new(vec![Ok(200), Ok(503)]);

        let outcome = drain(&db, &pusher).await;

        assert_eq!(
            outcome,
            DrainOutcome::Retry { category: SyncErrorCategory::ServerError, synced: 1 }
        );
        assert_eq!(db.pending_changes().unwrap().len(), 1, "failed change stays queued");
    }

    #[tokio::test]
    async fn transport_failure_is_network_retry() {
        let db = CacheDb::open_in_memory().unwrap();
        queued_change(&db, "u-1", "2026-06-11T10:00:00Z");
        let pusher = ScriptedPusher::new(vec![Err(())]);

        let outcome = drain(&db, &pusher).await;

        assert_eq!(
            outcome,
            DrainOutcome::Retry { category: SyncErrorCategory::Network, synced: 0 }
        );
    }

    #[tokio::test]
    async fn malformed_change_is_fatal_not_retried() {
        let db = CacheDb::open_in_memory().unwrap();
        queued_change(&db, "u-1", "2026-06-11T10:00:00Z");
        let pusher = ScriptedPusher::new(vec![Ok(422)]);

        let outcome = drain(&db, &pusher).await;

        let queued = db.pending_changes().unwrap();
        assert_eq!(
            outcome,
            DrainOutcome::Fatal {
                category: SyncErrorCategory::Malformed,
                change_id: queued[0].id,
                synced: 0
            }
        );
    }

    #[tokio::test]
    async fn http_pusher_sends_if_match_and_bearer_to_entity_url() {
        let mut server = mockito::Server::new_async().await;
        let mock = server
            .mock("PUT", "/api/v1/entities/User/u-1")
            .match_header("if-match", "7")
            .match_header("authorization", "Bearer test-token")
            .match_body("{\"id\":\"u-1\"}")
            .with_status(200)
            .create_async()
            .await;
        let db = CacheDb::open_in_memory().unwrap();
        queued_change(&db, "u-1", "2026-06-11T10:00:00Z");
        let pusher = HttpChangePusher::new(server.url(), "test-token");

        let outcome = drain(&db, &pusher).await;

        mock.assert_async().await;
        assert_eq!(outcome, DrainOutcome::Drained { synced: 1 });
    }
}
