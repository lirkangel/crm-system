use crate::auth::{AuthSession, AuthStatus};
use crate::config::BackendConfig;
use crate::credentials::{CredentialError, CredentialStore, InMemoryStore, StoredRefreshToken};
use serde::Serialize;
use std::sync::{Arc, Mutex};

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
    /// Persists the refresh token across restarts (OS keychain in the app).
    /// Kept outside `inner` so keychain I/O never runs under the state lock.
    credentials: Arc<dyn CredentialStore>,
}

impl ShellState {
    /// State with a process-lifetime credential store (nothing survives restart).
    pub fn new(config: BackendConfig) -> Self {
        Self::with_credential_store(config, Arc::new(InMemoryStore::default()))
    }

    pub fn with_credential_store(config: BackendConfig, credentials: Arc<dyn CredentialStore>) -> Self {
        Self {
            inner: Mutex::new(ShellStateInner {
                connection_status: ConnectionStatus::Disconnected,
                backend_config: config,
                auth_session: None,
                pending_logout_revoke: None,
            }),
            credentials,
        }
    }

    /// The refresh token persisted by a previous run, if any. A malformed entry
    /// is cleared so the user simply logs in again; a keychain failure is
    /// treated as "nothing stored".
    pub fn stored_refresh_token(&self) -> Option<String> {
        match self.credentials.load() {
            Ok(stored) => stored.map(|token| token.refresh_token),
            Err(CredentialError::Malformed(error)) => {
                eprintln!("discarding malformed stored refresh token: {error}");
                self.forget_refresh_token();
                None
            }
            Err(error) => {
                eprintln!("could not read stored refresh token: {error}");
                None
            }
        }
    }

    // Persistence is best-effort: a locked or missing keychain must not block
    // login/logout, it only means the session will not survive a restart.
    fn persist_refresh_token(&self, session: &AuthSession) {
        let token = StoredRefreshToken {
            refresh_token: session.refresh_token.clone(),
            refresh_token_expires_at: session.refresh_token_expires_at.clone(),
        };
        if let Err(error) = self.credentials.save(&token) {
            eprintln!("could not persist refresh token: {error}");
        }
    }

    fn forget_refresh_token(&self) {
        if let Err(error) = self.credentials.clear() {
            eprintln!("could not clear stored refresh token: {error}");
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
        self.persist_refresh_token(&session);
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
        let status = AuthStatus {
            session: None,
            pending_logout_revoke: inner.pending_logout_revoke.clone(),
            is_authenticated: false,
        };
        drop(inner);

        self.forget_refresh_token();
        Ok(status)
    }

    /// Drops the active session outright (e.g. the refresh token was rejected
    /// and cannot be recovered). Unlike `logout_locally`, this records no
    /// pending-revoke marker, since the backend has already invalidated it.
    pub fn clear_session(&self) -> AuthStatus {
        self.forget_refresh_token();
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
    use crate::credentials::{CredentialStore, InMemoryStore};
    use std::sync::Arc;

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

    #[test]
    fn set_auth_session_persists_refresh_token_to_credential_store() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());

        state.set_auth_session(sample_session());

        let stored = store.load().unwrap().expect("refresh token should be persisted");
        assert_eq!(stored.refresh_token, "refresh-token");
        assert_eq!(stored.refresh_token_expires_at, "2030-01-08T00:00:00Z");
    }

    #[test]
    fn rotated_session_replaces_persisted_refresh_token() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        state.set_auth_session(sample_session());

        let mut rotated = sample_session();
        rotated.refresh_token = "rotated-refresh-token".to_string();
        state.set_auth_session(rotated);

        assert_eq!(
            store.load().unwrap().unwrap().refresh_token,
            "rotated-refresh-token"
        );
    }

    #[test]
    fn logout_locally_removes_persisted_refresh_token() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        state.set_auth_session(sample_session());

        state.logout_locally().unwrap();

        assert_eq!(store.load().unwrap(), None);
    }

    #[test]
    fn clear_session_removes_persisted_refresh_token() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        state.set_auth_session(sample_session());

        state.clear_session();

        assert_eq!(store.load().unwrap(), None);
    }

    #[test]
    fn stored_refresh_token_survives_a_fresh_shell_state() {
        // Simulates an app restart: a new ShellState over the same store.
        let store = Arc::new(InMemoryStore::default());
        ShellState::with_credential_store(BackendConfig::default(), store.clone())
            .set_auth_session(sample_session());

        let restarted = ShellState::with_credential_store(BackendConfig::default(), store);

        assert!(!restarted.auth_status().is_authenticated);
        assert_eq!(
            restarted.stored_refresh_token().as_deref(),
            Some("refresh-token")
        );
    }

    #[test]
    fn malformed_stored_token_is_discarded() {
        let store = Arc::new(InMemoryStore::with_raw("not-json"));
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());

        assert_eq!(state.stored_refresh_token(), None);
        assert!(matches!(store.load(), Ok(None)));
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
