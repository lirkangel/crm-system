//! D401 — IPC bridge exposing the SQLite cache to the React frontend.
//! Pure functions carry the logic (unit-tested); `#[tauri::command]` wrappers
//! stay thin, mirroring `commands.rs`.

use crate::cache::CacheDb;
use crate::cache::entities::CachedEntity;
use crate::cache::queue::{NewPendingChange, PendingChange};
use std::sync::Mutex;
use tauri::State;

/// Managed wrapper: rusqlite connections aren't `Sync`, so IPC serializes
/// cache access behind a mutex (fine for a single-user desktop shell).
pub struct CacheState {
    db: Mutex<CacheDb>,
}

impl CacheState {
    pub fn new(db: CacheDb) -> Self {
        Self { db: Mutex::new(db) }
    }
}

pub fn get_entity(
    state: &CacheState,
    entity_type: &str,
    entity_id: &str,
) -> Result<Option<CachedEntity>, String> {
    let db = state.db.lock().unwrap();
    db.get_entity(entity_type, entity_id).map_err(|e| e.to_string())
}

pub fn list_entities(state: &CacheState, entity_type: &str) -> Result<Vec<CachedEntity>, String> {
    let db = state.db.lock().unwrap();
    db.list_entities(entity_type).map_err(|e| e.to_string())
}

pub fn put_entity(state: &CacheState, entity: &CachedEntity) -> Result<(), String> {
    let db = state.db.lock().unwrap();
    db.put_entity(entity).map_err(|e| e.to_string())
}

pub fn enqueue_change(state: &CacheState, change: &NewPendingChange) -> Result<i64, String> {
    let db = state.db.lock().unwrap();
    db.enqueue_change(change).map_err(|e| e.to_string())
}

pub fn pending_changes(state: &CacheState) -> Result<Vec<PendingChange>, String> {
    let db = state.db.lock().unwrap();
    db.pending_changes().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn cache_get_entity(
    state: State<'_, CacheState>,
    entity_type: String,
    entity_id: String,
) -> Result<Option<CachedEntity>, String> {
    get_entity(&state, &entity_type, &entity_id)
}

#[tauri::command]
pub fn cache_list_entities(
    state: State<'_, CacheState>,
    entity_type: String,
) -> Result<Vec<CachedEntity>, String> {
    list_entities(&state, &entity_type)
}

#[tauri::command]
pub fn cache_put_entity(state: State<'_, CacheState>, entity: CachedEntity) -> Result<(), String> {
    put_entity(&state, &entity)
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub fn cache_enqueue_change(
    state: State<'_, CacheState>,
    op: String,
    entity_type: String,
    entity_id: String,
    payload_json: String,
    base_version: Option<i64>,
    queued_at: String,
) -> Result<i64, String> {
    enqueue_change(
        &state,
        &NewPendingChange { op, entity_type, entity_id, payload_json, base_version, queued_at },
    )
}

#[tauri::command]
pub fn cache_pending_changes(state: State<'_, CacheState>) -> Result<Vec<PendingChange>, String> {
    pending_changes(&state)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> CacheState {
        CacheState::new(CacheDb::open_in_memory().unwrap())
    }

    fn entity(id: &str) -> CachedEntity {
        CachedEntity {
            entity_type: "User".to_string(),
            entity_id: id.to_string(),
            version: 1,
            payload_json: format!("{{\"id\":\"{id}\"}}"),
            cached_at: "2026-06-11T10:00:00Z".to_string(),
        }
    }

    #[test]
    fn entity_round_trips_through_ipc_layer() {
        let state = state();

        put_entity(&state, &entity("u-1")).unwrap();

        assert_eq!(get_entity(&state, "User", "u-1").unwrap(), Some(entity("u-1")));
        assert_eq!(list_entities(&state, "User").unwrap().len(), 1);
    }

    #[test]
    fn missing_entity_is_none_not_error() {
        assert_eq!(get_entity(&state(), "User", "missing").unwrap(), None);
    }

    #[test]
    fn offline_write_queues_through_ipc_layer() {
        let state = state();
        let id = enqueue_change(
            &state,
            &NewPendingChange {
                op: "UPDATE".to_string(),
                entity_type: "User".to_string(),
                entity_id: "u-1".to_string(),
                payload_json: "{}".to_string(),
                base_version: Some(2),
                queued_at: "2026-06-11T10:00:00Z".to_string(),
            },
        )
        .unwrap();

        let queued = pending_changes(&state).unwrap();
        assert_eq!(queued.len(), 1);
        assert_eq!(queued[0].id, id);
        assert_eq!(queued[0].base_version, Some(2));
    }
}
