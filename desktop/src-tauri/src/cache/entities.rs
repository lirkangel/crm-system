//! Entity cache reads/writes (D202): server reads write through here so
//! offline reads can fall back to the last known server state.

use super::CacheDb;
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedEntity {
    pub entity_type: String,
    pub entity_id: String,
    pub version: i64,
    pub payload_json: String,
    /// RFC 3339 timestamp of when this copy was cached.
    pub cached_at: String,
}

impl CacheDb {
    /// Upserts the entity — last write (freshest server read) wins.
    pub fn put_entity(&self, entity: &CachedEntity) -> rusqlite::Result<()> {
        self.connection().execute(
            "INSERT INTO cache_entities (entity_type, entity_id, version, payload_json, cached_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                 version = excluded.version,
                 payload_json = excluded.payload_json,
                 cached_at = excluded.cached_at",
            params![
                entity.entity_type,
                entity.entity_id,
                entity.version,
                entity.payload_json,
                entity.cached_at
            ],
        )?;
        Ok(())
    }

    pub fn get_entity(
        &self,
        entity_type: &str,
        entity_id: &str,
    ) -> rusqlite::Result<Option<CachedEntity>> {
        self.connection()
            .query_row(
                "SELECT entity_type, entity_id, version, payload_json, cached_at
                 FROM cache_entities WHERE entity_type = ?1 AND entity_id = ?2",
                params![entity_type, entity_id],
                row_to_entity,
            )
            .optional()
    }

    pub fn list_entities(&self, entity_type: &str) -> rusqlite::Result<Vec<CachedEntity>> {
        let mut stmt = self.connection().prepare(
            "SELECT entity_type, entity_id, version, payload_json, cached_at
             FROM cache_entities WHERE entity_type = ?1 ORDER BY entity_id",
        )?;
        let rows = stmt.query_map(params![entity_type], row_to_entity)?;
        rows.collect()
    }
}

fn row_to_entity(row: &rusqlite::Row<'_>) -> rusqlite::Result<CachedEntity> {
    Ok(CachedEntity {
        entity_type: row.get(0)?,
        entity_id: row.get(1)?,
        version: row.get(2)?,
        payload_json: row.get(3)?,
        cached_at: row.get(4)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entity(id: &str, version: i64) -> CachedEntity {
        CachedEntity {
            entity_type: "User".to_string(),
            entity_id: id.to_string(),
            version,
            payload_json: format!("{{\"id\":\"{id}\",\"v\":{version}}}"),
            cached_at: "2026-06-11T10:00:00Z".to_string(),
        }
    }

    #[test]
    fn entity_round_trips_through_sqlite() {
        let db = CacheDb::open_in_memory().unwrap();
        let original = entity("u-1", 1);

        db.put_entity(&original).unwrap();
        let loaded = db.get_entity("User", "u-1").unwrap();

        assert_eq!(loaded, Some(original));
    }

    #[test]
    fn write_through_replaces_stale_copy() {
        let db = CacheDb::open_in_memory().unwrap();
        db.put_entity(&entity("u-1", 1)).unwrap();

        db.put_entity(&entity("u-1", 2)).unwrap();

        let loaded = db.get_entity("User", "u-1").unwrap().unwrap();
        assert_eq!(loaded.version, 2);
        assert_eq!(db.list_entities("User").unwrap().len(), 1);
    }

    #[test]
    fn missing_entity_reads_as_none() {
        let db = CacheDb::open_in_memory().unwrap();

        assert_eq!(db.get_entity("User", "nope").unwrap(), None);
    }

    #[test]
    fn list_filters_by_entity_type() {
        let db = CacheDb::open_in_memory().unwrap();
        db.put_entity(&entity("u-1", 1)).unwrap();
        db.put_entity(&CachedEntity {
            entity_type: "Role".to_string(),
            ..entity("r-1", 1)
        })
        .unwrap();

        let users = db.list_entities("User").unwrap();

        assert_eq!(users.len(), 1);
        assert_eq!(users[0].entity_id, "u-1");
    }
}
