//! D305 — pull deltas from `GET /api/v1/sync/changes?since=…`.
//!
//! Called on reconnect, on a WS change notification, and from a 30-second
//! safety poll (scheduling lives in the app loop / D402 wiring); this module
//! owns the fetch itself. Pulled change ids are recorded in the entity cache
//! metadata so the caller can refetch the affected entities.

use crate::sync::SyncErrorCategory;
use serde::Deserialize;

/// One change event from the server feed (matches `ChangeEventResponse`).
#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PulledChange {
    pub id: String,
    pub plugin_id: Option<String>,
    pub entity_type: String,
    pub entity_id: String,
    pub version: Option<i64>,
    pub op: String,
    pub occurred_at: String,
}

#[derive(Debug, Deserialize)]
struct CommonResponse<T> {
    success: bool,
    data: Option<T>,
}

/// Fetches all changes since `since` (RFC 3339). The caller applies them:
/// refetch + write-through for upserts, cache eviction for deletes.
pub async fn pull_changes(
    client: &reqwest::Client,
    base_url: &str,
    access_token: &str,
    since: &str,
) -> Result<Vec<PulledChange>, SyncErrorCategory> {
    let url = format!("{}/api/v1/sync/changes", base_url.trim_end_matches('/'));
    let response = client
        .get(url)
        .query(&[("since", since)])
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|_| SyncErrorCategory::Network)?;

    let status = response.status().as_u16();
    if !(200..300).contains(&status) {
        return Err(SyncErrorCategory::from_status(status));
    }
    let body: CommonResponse<Vec<PulledChange>> =
        response.json().await.map_err(|_| SyncErrorCategory::Malformed)?;
    if !body.success {
        return Err(SyncErrorCategory::Malformed);
    }
    Ok(body.data.unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pulls_ordered_changes_since_cursor() {
        let mut server = mockito::Server::new_async().await;
        let mock = server
            .mock("GET", "/api/v1/sync/changes")
            .match_query(mockito::Matcher::UrlEncoded(
                "since".into(),
                "2026-06-11T10:00:00Z".into(),
            ))
            .match_header("authorization", "Bearer test-token")
            .with_status(200)
            .with_header("content-type", "application/json")
            .with_body(
                r#"{"success":true,"code":"SYNC_CHANGES_OK","message":"ok","data":[
                    {"id":"c-1","pluginId":"core","entityType":"User","entityId":"u-1","version":2,"op":"UPDATE","occurredAt":"2026-06-11T10:01:00Z"},
                    {"id":"c-2","pluginId":"core","entityType":"User","entityId":"u-2","version":1,"op":"CREATE","occurredAt":"2026-06-11T10:02:00Z"}
                ]}"#,
            )
            .create_async()
            .await;

        let changes = pull_changes(
            &reqwest::Client::new(),
            &server.url(),
            "test-token",
            "2026-06-11T10:00:00Z",
        )
        .await
        .unwrap();

        mock.assert_async().await;
        assert_eq!(changes.len(), 2);
        assert_eq!(changes[0].entity_id, "u-1");
        assert_eq!(changes[0].op, "UPDATE");
        assert_eq!(changes[1].entity_id, "u-2");
    }

    #[tokio::test]
    async fn expired_token_maps_to_auth_expired() {
        let mut server = mockito::Server::new_async().await;
        server
            .mock("GET", "/api/v1/sync/changes")
            .match_query(mockito::Matcher::Any)
            .with_status(401)
            .create_async()
            .await;

        let result = pull_changes(
            &reqwest::Client::new(),
            &server.url(),
            "stale",
            "2026-06-11T10:00:00Z",
        )
        .await;

        assert_eq!(result, Err(SyncErrorCategory::AuthExpired));
    }

    #[tokio::test]
    async fn unreachable_server_maps_to_network() {
        let result = pull_changes(
            &reqwest::Client::new(),
            "http://127.0.0.1:1", // nothing listens here
            "token",
            "2026-06-11T10:00:00Z",
        )
        .await;

        assert_eq!(result, Err(SyncErrorCategory::Network));
    }
}
