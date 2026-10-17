mod auth;
mod auth_client;
pub mod cache;
mod commands;
pub mod credentials;
pub mod ipc;
pub mod config;
pub mod error;
pub mod state;
pub mod sync;

use auth_client::AuthApiClient;
use config::BackendConfig;
use credentials::{CredentialStore, InMemoryStore, KeyringStore};
use state::ShellState;
use std::sync::Arc;

/// OS keychain when available; otherwise sessions just won't survive restart.
fn credential_store() -> Arc<dyn CredentialStore> {
    match KeyringStore::new(credentials::KEYCHAIN_SERVICE, credentials::KEYCHAIN_ACCOUNT) {
        Ok(store) => Arc::new(store),
        Err(error) => {
            eprintln!("OS keychain unavailable, refresh token will not persist: {error}");
            Arc::new(InMemoryStore::default())
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AuthApiClient::new())
        .manage(ShellState::with_credential_store(
            BackendConfig::default(),
            credential_store(),
        ))
        .setup(|app| {
            use tauri::Manager;
            let data_dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&data_dir)?;
            let db = cache::CacheDb::open(&data_dir.join("cache.db"))?;
            app.manage(ipc::CacheState::new(db));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::shell_status,
            commands::backend_config,
            commands::auth_status,
            commands::login,
            commands::logout,
            commands::refresh,
            commands::restore_session,
            commands::sync_pending_logout_revoke,
            ipc::cache_get_entity,
            ipc::cache_list_entities,
            ipc::cache_put_entity,
            ipc::cache_enqueue_change,
            ipc::cache_pending_changes
        ])
        .run(tauri::generate_context!())
        .expect("error while running CRM desktop shell");
}
