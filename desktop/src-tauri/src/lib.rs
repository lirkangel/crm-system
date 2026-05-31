mod auth;
mod auth_client;
mod commands;
pub mod config;
pub mod error;
pub mod state;

use auth_client::AuthApiClient;
use config::BackendConfig;
use state::ShellState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AuthApiClient::new())
        .manage(ShellState::new(BackendConfig::default()))
        .invoke_handler(tauri::generate_handler![
            commands::shell_status,
            commands::backend_config,
            commands::auth_status,
            commands::login,
            commands::logout,
            commands::sync_pending_logout_revoke
        ])
        .run(tauri::generate_context!())
        .expect("error while running CRM desktop shell");
}
