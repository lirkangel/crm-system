use crate::config::BackendConfig;
use serde::Serialize;
use std::sync::Mutex;

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Degraded,
}

#[derive(Debug)]
struct ShellStateInner {
    connection_status: ConnectionStatus,
    backend_config: BackendConfig,
}

#[derive(Debug)]
pub struct ShellState {
    inner: Mutex<ShellStateInner>,
}

impl ShellState {
    pub fn new(config: BackendConfig) -> Self {
        Self {
            inner: Mutex::new(ShellStateInner {
                connection_status: ConnectionStatus::Disconnected,
                backend_config: config,
            }),
        }
    }

    pub fn connection_status(&self) -> ConnectionStatus {
        self.inner.lock().unwrap().connection_status
    }

    pub fn backend_config(&self) -> BackendConfig {
        self.inner.lock().unwrap().backend_config.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::{ConnectionStatus, ShellState};
    use crate::config::BackendConfig;

    #[test]
    fn shell_state_starts_disconnected() {
        let state = ShellState::new(BackendConfig::default());

        assert_eq!(state.connection_status(), ConnectionStatus::Disconnected);
        assert_eq!(state.backend_config().base_url, "http://127.0.0.1:8082");
    }
}
