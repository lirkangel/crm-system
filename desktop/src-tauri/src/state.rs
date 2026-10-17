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
    /// Bumped on every login/logout/clear. An awaited refresh captures it
    /// first and applies its result only if it is unchanged, so a response
    /// that raced a logout or a new login cannot resurrect a stale session.
    session_epoch: u64,
}

#[derive(Debug)]
pub struct ShellState {
    inner: Mutex<ShellStateInner>,
    /// Persists the refresh token across restarts (OS keychain in the app).
    /// Writes happen while holding `inner`, so keychain contents always follow
    /// the same order as the in-memory session.
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
                session_epoch: 0,
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

    pub fn session_epoch(&self) -> u64 {
        self.inner.lock().unwrap().session_epoch
    }

    /// Installs a session from a fresh login.
    pub fn set_auth_session(&self, session: AuthSession) -> AuthStatus {
        let mut inner = self.inner.lock().unwrap();
        self.install_session(&mut inner, session)
    }

    /// Installs a session obtained by a refresh that started at `epoch`. If a
    /// login/logout happened while it was in flight, nothing changes and the
    /// session is handed back so the caller can revoke its refresh token.
    pub fn apply_refreshed_session(
        &self,
        epoch: u64,
        session: AuthSession,
    ) -> Result<AuthStatus, AuthSession> {
        let mut inner = self.inner.lock().unwrap();
        if inner.session_epoch != epoch {
            return Err(session);
        }
        Ok(self.install_session(&mut inner, session))
    }

    fn install_session(&self, inner: &mut ShellStateInner, session: AuthSession) -> AuthStatus {
        self.persist_refresh_token(&session);
        inner.session_epoch += 1;
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
        inner.session_epoch += 1;
        self.forget_refresh_token();

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
        self.clear_locked(&mut inner)
    }

    /// `clear_session` for a rejection observed by a refresh that started at
    /// `epoch`; a no-op if a newer login/logout has since replaced that session.
    pub fn clear_session_if_current(&self, epoch: u64) -> AuthStatus {
        let mut inner = self.inner.lock().unwrap();
        if inner.session_epoch != epoch {
            return AuthStatus {
                session: inner.auth_session.clone(),
                pending_logout_revoke: inner.pending_logout_revoke.clone(),
                is_authenticated: inner.auth_session.is_some(),
            };
        }
        self.clear_locked(&mut inner)
    }

    fn clear_locked(&self, inner: &mut ShellStateInner) -> AuthStatus {
        self.forget_refresh_token();
        inner.session_epoch += 1;
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
    fn refreshed_session_applies_when_nothing_changed_in_flight() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        state.set_auth_session(sample_session());
        let epoch = state.session_epoch();

        let status = state
            .apply_refreshed_session(epoch, rotated_session())
            .expect("an in-flight refresh with no intervening change should apply");

        assert!(status.is_authenticated);
        assert_eq!(
            store.load().unwrap().unwrap().refresh_token,
            "rotated-refresh-token"
        );
    }

    #[test]
    fn refreshed_session_is_discarded_when_logout_happened_in_flight() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        state.set_auth_session(sample_session());
        let epoch = state.session_epoch();

        state.logout_locally().unwrap();
        let discarded = state
            .apply_refreshed_session(epoch, rotated_session())
            .expect_err("a refresh that raced a logout must not resurrect the session");

        assert_eq!(discarded.refresh_token, "rotated-refresh-token");
        assert!(!state.auth_status().is_authenticated);
        assert_eq!(store.load().unwrap(), None);
    }

    #[test]
    fn refreshed_session_is_discarded_when_a_new_login_happened_in_flight() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        let epoch = state.session_epoch();

        let mut fresh_login = sample_session();
        fresh_login.refresh_token = "fresh-login-refresh-token".to_string();
        state.set_auth_session(fresh_login);
        assert!(state.apply_refreshed_session(epoch, rotated_session()).is_err());

        assert_eq!(
            state.auth_status().session.unwrap().refresh_token,
            "fresh-login-refresh-token"
        );
        assert_eq!(
            store.load().unwrap().unwrap().refresh_token,
            "fresh-login-refresh-token"
        );
    }

    #[test]
    fn stale_rejection_does_not_clear_a_newer_session() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        let epoch = state.session_epoch();
        state.set_auth_session(sample_session());

        let status = state.clear_session_if_current(epoch);

        assert!(status.is_authenticated);
        assert_eq!(store.load().unwrap().unwrap().refresh_token, "refresh-token");
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

    fn rotated_session() -> AuthSession {
        AuthSession {
            access_token: "rotated-access-token".to_string(),
            access_token_expires_at: "2030-01-01T00:30:00Z".to_string(),
            refresh_token: "rotated-refresh-token".to_string(),
            refresh_token_expires_at: "2030-01-15T00:00:00Z".to_string(),
            token_type: "Bearer".to_string(),
        }
    }
}
