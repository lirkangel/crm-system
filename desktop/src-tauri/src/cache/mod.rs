//! Local SQLite cache (D-EPIC-2): offline reads come from here, offline
//! writes queue in `pending_changes` until the sync engine drains them.

pub mod entities;

use rusqlite::Connection;
use std::path::Path;

/// Owns the SQLite connection; schema is created/upgraded on open (D201).
pub struct CacheDb {
    conn: Connection,
}

impl CacheDb {
    /// Opens (creating if needed) the cache database at `path` and ensures the schema exists.
    pub fn open(path: &Path) -> rusqlite::Result<Self> {
        let conn = Connection::open(path)?;
        Self::with_connection(conn)
    }

    /// In-memory database — tests and ephemeral fallback.
    pub fn open_in_memory() -> rusqlite::Result<Self> {
        Self::with_connection(Connection::open_in_memory()?)
    }

    fn with_connection(conn: Connection) -> rusqlite::Result<Self> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.execute_batch(SCHEMA)?;
        Ok(Self { conn })
    }

    pub fn connection(&self) -> &Connection {
        &self.conn
    }
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS cache_entities (
    entity_type  TEXT    NOT NULL,
    entity_id    TEXT    NOT NULL,
    version      INTEGER NOT NULL,
    payload_json TEXT    NOT NULL,
    cached_at    TEXT    NOT NULL,
    PRIMARY KEY (entity_type, entity_id)
);

CREATE TABLE IF NOT EXISTS pending_changes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    op           TEXT    NOT NULL,
    entity_type  TEXT    NOT NULL,
    entity_id    TEXT    NOT NULL,
    payload_json TEXT    NOT NULL,
    base_version INTEGER,
    queued_at    TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_changes_queued_at
    ON pending_changes (queued_at, id);
";

#[cfg(test)]
mod tests {
    use super::*;

    fn table_names(db: &CacheDb) -> Vec<String> {
        let mut stmt = db
            .connection()
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .unwrap();
        stmt.query_map([], |row| row.get::<_, String>(0))
            .unwrap()
            .map(Result::unwrap)
            .collect()
    }

    #[test]
    fn open_creates_db_file_and_schema_on_first_run() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("cache.db");

        let db = CacheDb::open(&path).unwrap();

        assert!(path.exists(), "db file should be created");
        let tables = table_names(&db);
        assert!(tables.contains(&"cache_entities".to_string()), "tables: {tables:?}");
        assert!(tables.contains(&"pending_changes".to_string()), "tables: {tables:?}");
    }

    #[test]
    fn reopening_existing_db_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("cache.db");

        drop(CacheDb::open(&path).unwrap());
        let reopened = CacheDb::open(&path).unwrap();

        assert!(table_names(&reopened).contains(&"pending_changes".to_string()));
    }

    #[test]
    fn pending_changes_has_required_columns() {
        let db = CacheDb::open_in_memory().unwrap();

        let mut stmt = db
            .connection()
            .prepare("SELECT op, entity_type, entity_id, payload_json, base_version, queued_at FROM pending_changes")
            .unwrap();
        assert_eq!(stmt.column_count(), 6);
    }
}
