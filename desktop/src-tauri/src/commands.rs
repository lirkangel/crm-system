use crate::config::BackendConfig;
use crate::state::{ConnectionStatus, ShellState};
use tauri::State;

pub fn get_shell_status(state: &ShellState) -> ConnectionStatus {
    state.connection_status()
}

pub fn get_backend_config(state: &ShellState) -> BackendConfig {
    state.backend_config()
}

#[tauri::command]
pub fn shell_status(state: State<'_, ShellState>) -> ConnectionStatus {
    get_shell_status(&state)
}

#[tauri::command]
pub fn backend_config(state: State<'_, ShellState>) -> BackendConfig {
    get_backend_config(&state)
}

#[cfg(test)]
mod tests {
    use super::{get_backend_config, get_shell_status};
    use crate::config::BackendConfig;
    use crate::state::{ConnectionStatus, ShellState};

    #[test]
    fn get_shell_status_returns_disconnected_by_default() {
        let state = ShellState::new(BackendConfig::default());

        let status = get_shell_status(&state);

        assert_eq!(status, ConnectionStatus::Disconnected);
    }

    #[test]
    fn get_backend_config_returns_current_config() {
        let state = ShellState::new(BackendConfig::default());

        let config = get_backend_config(&state);

        assert_eq!(config.base_url, "http://127.0.0.1:8082");
    }
}
