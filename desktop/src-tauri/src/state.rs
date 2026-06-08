use crate::auth::{AuthSession, AuthStatus};
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
    auth_session: Option<AuthSession>,
    pending_logout_revoke: Option<String>,
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
                auth_session: None,
                pending_logout_revoke: None,
            }),
        }
    }

    pub fn connection_status(&self) -> ConnectionStatus {
        self.inner.lock().unwrap().connection_status
    }

    pub fn backend_config(&self) -> BackendConfig {
        self.inner.lock().unwrap().backend_config.clone()
    }

    pub fn auth_status(&self) -> AuthStatus {
        let inner = self.inner.lock().unwrap();

        AuthStatus {
            session: inner.auth_session.clone(),
            pending_logout_revoke: inner.pending_logout_revoke.clone(),
            is_authenticated: inner.auth_session.is_some(),
        }
    }

    pub fn set_auth_session(&self, session: AuthSession) -> AuthStatus {
        let mut inner = self.inner.lock().unwrap();
        inner.auth_session = Some(session);

        AuthStatus {
            session: inner.auth_session.clone(),
            pending_logout_revoke: inner.pending_logout_revoke.clone(),
            is_authenticated: true,
        }
    }

    pub fn logout_locally(&self) -> Result<AuthStatus, crate::error::ShellError> {
        let mut inner = self.inner.lock().unwrap();
        let session = inner
            .auth_session
            .take()
            .ok_or(crate::error::ShellError::NoActiveSession)?;

        inner.pending_logout_revoke = Some(session.refresh_token);

        Ok(AuthStatus {
            session: None,
            pending_logout_revoke: inner.pending_logout_revoke.clone(),
            is_authenticated: false,
        })
    }

    /// Drops the active session outright (e.g. the refresh token was rejected
    /// and cannot be recovered). Unlike `logout_locally`, this records no
    /// pending-revoke marker, since the backend has already invalidated it.
    pub fn clear_session(&self) -> AuthStatus {
        let mut inner = self.inner.lock().unwrap();
        inner.auth_session = None;
        inner.pending_logout_revoke = None;

        AuthStatus {
            session: None,
            pending_logout_revoke: None,
            is_authenticated: false,
        }
    }

    pub fn pending_logout_revoke(&self) -> Option<String> {
        self.inner.lock().unwrap().pending_logout_revoke.clone()
    }

    pub fn clear_pending_logout_revoke(&self, refresh_token: &str) -> AuthStatus {
        let mut inner = self.inner.lock().unwrap();

        if inner.pending_logout_revoke.as_deref() == Some(refresh_token) {
            inner.pending_logout_revoke = None;
        }

        AuthStatus {
            session: inner.auth_session.clone(),
            pending_logout_revoke: inner.pending_logout_revoke.clone(),
            is_authenticated: inner.auth_session.is_some(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{ConnectionStatus, ShellState};
    use crate::auth::AuthSession;
    use crate::config::BackendConfig;

    #[test]
    fn shell_state_starts_disconnected() {
        let state = ShellState::new(BackendConfig::default());

        assert_eq!(state.connection_status(), ConnectionStatus::Disconnected);
        assert_eq!(state.backend_config().base_url, "http://127.0.0.1:8082");
        assert_eq!(state.auth_status().session, None);
    }

    #[test]
    fn set_auth_session_marks_state_authenticated() {
        let state = ShellState::new(BackendConfig::default());

        let status = state.set_auth_session(sample_session());

        assert!(status.is_authenticated);
        assert_eq!(status.session.unwrap().refresh_token, "refresh-token");
    }

    #[test]
    fn logout_locally_clears_active_session_and_tracks_pending_revoke() {
        let state = ShellState::new(BackendConfig::default());
        state.set_auth_session(sample_session());

        let status = state.logout_locally().unwrap();

        assert!(!status.is_authenticated);
        assert_eq!(status.session, None);
        assert_eq!(
            status.pending_logout_revoke.as_deref(),
            Some("refresh-token")
        );
    }

    #[test]
    fn clear_pending_logout_revoke_removes_matching_marker() {
        let state = ShellState::new(BackendConfig::default());
        state.set_auth_session(sample_session());
        state.logout_locally().unwrap();

        let status = state.clear_pending_logout_revoke("refresh-token");

        assert_eq!(status.pending_logout_revoke, None);
        assert!(!status.is_authenticated);
    }

    #[test]
    fn clear_session_drops_active_session_without_pending_revoke() {
        let state = ShellState::new(BackendConfig::default());
        state.set_auth_session(sample_session());

        let status = state.clear_session();

        assert!(!status.is_authenticated);
        assert_eq!(status.session, None);
        assert_eq!(status.pending_logout_revoke, None);
    }

    fn sample_session() -> AuthSession {
        AuthSession {
            access_token: "access-token".to_string(),
            access_token_expires_at: "2030-01-01T00:15:00Z".to_string(),
            refresh_token: "refresh-token".to_string(),
            refresh_token_expires_at: "2030-01-08T00:00:00Z".to_string(),
            token_type: "Bearer".to_string(),
        }
    }
}
