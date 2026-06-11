//! Pending-changes queue (D203): offline writes persist here until the sync
//! engine (D-EPIC-3) drains them to the server in order.

use super::CacheDb;
use rusqlite::params;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingChange {
    pub id: i64,
    pub op: String,
    pub entity_type: String,
    pub entity_id: String,
    pub payload_json: String,
    /// Server version this change was based on — sent as `If-Match` (D302).
    pub base_version: Option<i64>,
    /// RFC 3339 timestamp of when the change was queued.
    pub queued_at: String,
}

/// Fields of a change not yet assigned a queue id.
#[derive(Debug, Clone)]
pub struct NewPendingChange {
    pub op: String,
    pub entity_type: String,
    pub entity_id: String,
    pub payload_json: String,
    pub base_version: Option<i64>,
    pub queued_at: String,
}

impl CacheDb {
    /// Appends a change to the queue; returns its queue id.
    pub fn enqueue_change(&self, change: &NewPendingChange) -> rusqlite::Result<i64> {
        self.connection().execute(
            "INSERT INTO pending_changes (op, entity_type, entity_id, payload_json, base_version, queued_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                change.op,
                change.entity_type,
                change.entity_id,
                change.payload_json,
                change.base_version,
                change.queued_at
            ],
        )?;
        Ok(self.connection().last_insert_rowid())
    }

    /// All queued changes in drain order (queued_at, then insertion order).
    pub fn pending_changes(&self) -> rusqlite::Result<Vec<PendingChange>> {
        let mut stmt = self.connection().prepare(
            "SELECT id, op, entity_type, entity_id, payload_json, base_version, queued_at
             FROM pending_changes ORDER BY queued_at, id",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(PendingChange {
                id: row.get(0)?,
                op: row.get(1)?,
                entity_type: row.get(2)?,
                entity_id: row.get(3)?,
                payload_json: row.get(4)?,
                base_version: row.get(5)?,
                queued_at: row.get(6)?,
            })
        })?;
        rows.collect()
    }

    /// Removes a drained (synced) change. Returns false when the id is unknown.
    pub fn remove_change(&self, id: i64) -> rusqlite::Result<bool> {
        let removed = self
            .connection()
            .execute("DELETE FROM pending_changes WHERE id = ?1", params![id])?;
        Ok(removed > 0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn change(entity_id: &str, queued_at: &str) -> NewPendingChange {
        NewPendingChange {
            op: "UPDATE".to_string(),
            entity_type: "User".to_string(),
            entity_id: entity_id.to_string(),
            payload_json: format!("{{\"id\":\"{entity_id}\"}}"),
            base_version: Some(3),
            queued_at: queued_at.to_string(),
        }
    }

    #[test]
    fn queued_changes_list_in_drain_order() {
        let db = CacheDb::open_in_memory().unwrap();
        db.enqueue_change(&change("u-2", "2026-06-11T10:01:00Z")).unwrap();
        db.enqueue_change(&change("u-1", "2026-06-11T10:00:00Z")).unwrap();

        let queued = db.pending_changes().unwrap();

        assert_eq!(queued.len(), 2);
        assert_eq!(queued[0].entity_id, "u-1");
        assert_eq!(queued[1].entity_id, "u-2");
        assert_eq!(queued[0].base_version, Some(3));
    }

    #[test]
    fn offline_write_survives_restart() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("cache.db");
        {
            let db = CacheDb::open(&path).unwrap();
            db.enqueue_change(&change("u-1", "2026-06-11T10:00:00Z")).unwrap();
        }

        let reopened = CacheDb::open(&path).unwrap();

        let queued = reopened.pending_changes().unwrap();
        assert_eq!(queued.len(), 1);
        assert_eq!(queued[0].entity_id, "u-1");
        assert_eq!(queued[0].op, "UPDATE");
    }

    #[test]
    fn drained_change_is_removed() {
        let db = CacheDb::open_in_memory().unwrap();
        let id = db.enqueue_change(&change("u-1", "2026-06-11T10:00:00Z")).unwrap();

        assert!(db.remove_change(id).unwrap());
        assert!(db.pending_changes().unwrap().is_empty());
        assert!(!db.remove_change(id).unwrap(), "second removal reports missing");
    }
}
