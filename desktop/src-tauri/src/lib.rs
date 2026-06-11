mod auth;
mod auth_client;
pub mod cache;
mod commands;
pub mod ipc;
pub mod config;
pub mod error;
pub mod state;
pub mod sync;

use auth_client::AuthApiClient;
use config::BackendConfig;
use state::ShellState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AuthApiClient::new())
        .manage(ShellState::new(BackendConfig::default()))
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
