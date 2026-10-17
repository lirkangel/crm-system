use crate::auth::{AuthSession, AuthStatus};
use crate::auth_client::AuthApiClient;
use crate::config::BackendConfig;
use crate::error::ShellError;
use crate::state::{ConnectionStatus, ShellState};
use tauri::State;

pub fn get_shell_status(state: &ShellState) -> ConnectionStatus {
    state.connection_status()
}

pub fn get_backend_config(state: &ShellState) -> BackendConfig {
    state.backend_config()
}

pub fn get_auth_status(state: &ShellState) -> AuthStatus {
    state.auth_status()
}

pub fn apply_login(state: &ShellState, session: AuthSession) -> AuthStatus {
    state.set_auth_session(session)
}

pub fn apply_local_logout(state: &ShellState) -> Result<AuthStatus, ShellError> {
    state.logout_locally()
}

pub fn finalize_pending_logout_revoke(state: &ShellState, refresh_token: &str) -> AuthStatus {
    state.clear_pending_logout_revoke(refresh_token)
}

/// Reconciles shell state with the outcome of a refresh-token revoke attempt.
///
/// - success or an already-invalid token → clear the pending-revoke marker
///   (the token can no longer be used, so logout is complete);
/// - a transient failure (backend unreachable) → keep the marker so the revoke
///   is retried later, and report the current status without error;
/// - any other failure → surface the error and leave the marker in place.
fn reconcile_after_revoke(
    state: &ShellState,
    refresh_token: &str,
    result: Result<(), ShellError>,
) -> Result<AuthStatus, String> {
    match result {
        Ok(()) => Ok(finalize_pending_logout_revoke(state, refresh_token)),
        Err(error) if error.is_token_already_invalid() => {
            Ok(finalize_pending_logout_revoke(state, refresh_token))
        }
        Err(error) if error.is_transient() => Ok(get_auth_status(state)),
        Err(error) => Err(error.to_string()),
    }
}

#[derive(Debug)]
struct RefreshReconciled {
    status: AuthStatus,
    /// Refresh token of a rotated session that arrived after a login/logout
    /// had already replaced the state; the caller revokes it server-side.
    discarded_refresh_token: Option<String>,
}

/// Reconciles shell state with the outcome of a refresh that started at
/// `epoch` (see `ShellState::session_epoch`).
///
/// - success → store the rotated session (new access *and* refresh token),
///   unless a login/logout happened in flight: then discard it for revoke;
/// - the refresh token was rejected → drop the session so the UI returns to
///   login (the token is already dead server-side, so no revoke is queued),
///   again only if that session is still the current one;
/// - any other failure → keep the session and surface the error for retry.
fn reconcile_after_refresh(
    state: &ShellState,
    epoch: u64,
    result: Result<AuthSession, ShellError>,
) -> Result<RefreshReconciled, String> {
    match result {
        Ok(session) => Ok(match state.apply_refreshed_session(epoch, session) {
            Ok(status) => RefreshReconciled {
                status,
                discarded_refresh_token: None,
            },
            Err(stale) => RefreshReconciled {
                status: get_auth_status(state),
                discarded_refresh_token: Some(stale.refresh_token),
            },
        }),
        Err(ShellError::Unauthorized(_)) => Ok(RefreshReconciled {
            status: state.clear_session_if_current(epoch),
            discarded_refresh_token: None,
        }),
        Err(error) => Err(error.to_string()),
    }
}

/// Runs a refresh started at `epoch` to completion: reconciles state and
/// best-effort revokes a rotated token that was discarded as stale.
async fn finish_refresh(
    state: &ShellState,
    auth_api: &AuthApiClient,
    config: &BackendConfig,
    epoch: u64,
    outcome: Result<AuthSession, ShellError>,
) -> Result<AuthStatus, String> {
    let reconciled = reconcile_after_refresh(state, epoch, outcome)?;
    if let Some(stale_token) = reconciled.discarded_refresh_token
        && let Err(error) = auth_api.revoke(config, &stale_token).await
    {
        eprintln!("could not revoke discarded refresh token: {error}");
    }
    Ok(reconciled.status)
}

#[tauri::command]
pub fn shell_status(state: State<'_, ShellState>) -> ConnectionStatus {
    get_shell_status(&state)
}

#[tauri::command]
pub fn backend_config(state: State<'_, ShellState>) -> BackendConfig {
    get_backend_config(&state)
}

#[tauri::command]
pub fn auth_status(state: State<'_, ShellState>) -> AuthStatus {
    get_auth_status(&state)
}

#[tauri::command]
pub async fn login(
    username: String,
    password: String,
    state: State<'_, ShellState>,
    auth_api: State<'_, AuthApiClient>,
) -> Result<AuthStatus, String> {
    let config = state.backend_config();
    let session = auth_api
        .login(&config, username, password)
        .await
        .map_err(|error| error.to_string())?;

    Ok(apply_login(&state, session))
}

#[tauri::command]
pub async fn logout(
    state: State<'_, ShellState>,
    auth_api: State<'_, AuthApiClient>,
) -> Result<AuthStatus, String> {
    let status = apply_local_logout(&state).map_err(|error| error.to_string())?;
    let refresh_token = status
        .pending_logout_revoke
        .clone()
        .ok_or_else(|| ShellError::NoActiveSession.to_string())?;
    let config = state.backend_config();

    let outcome = auth_api.revoke(&config, &refresh_token).await;
    reconcile_after_revoke(&state, &refresh_token, outcome)
}

#[tauri::command]
pub async fn refresh(
    state: State<'_, ShellState>,
    auth_api: State<'_, AuthApiClient>,
) -> Result<AuthStatus, String> {
    let epoch = state.session_epoch();
    let Some(session) = state.auth_status().session else {
        return Ok(get_auth_status(&state));
    };
    let config = state.backend_config();

    let outcome = auth_api.refresh(&config, &session.refresh_token).await;
    finish_refresh(&state, &auth_api, &config, epoch, outcome).await
}

/// Startup path (D501): if a previous run left a refresh token in the OS
/// keychain, exchange it for a fresh session so the user skips the login
/// screen. Outcomes follow `reconcile_after_refresh`: a rejected token clears
/// the keychain; a transient failure keeps it for the next attempt. Call it
/// once per launch: concurrent restores would present the same single-use
/// refresh token twice.
#[tauri::command]
pub async fn restore_session(
    state: State<'_, ShellState>,
    auth_api: State<'_, AuthApiClient>,
) -> Result<AuthStatus, String> {
    let epoch = state.session_epoch();
    let status = get_auth_status(&state);
    if status.is_authenticated {
        return Ok(status);
    }
    let Some(refresh_token) = state.stored_refresh_token() else {
        return Ok(status);
    };
    let config = state.backend_config();

    let outcome = auth_api.refresh(&config, &refresh_token).await;
    finish_refresh(&state, &auth_api, &config, epoch, outcome).await
}

#[tauri::command]
pub async fn sync_pending_logout_revoke(
    state: State<'_, ShellState>,
    auth_api: State<'_, AuthApiClient>,
) -> Result<AuthStatus, String> {
    let Some(refresh_token) = state.pending_logout_revoke() else {
        return Ok(get_auth_status(&state));
    };
    let config = state.backend_config();

    let outcome = auth_api.revoke(&config, &refresh_token).await;
    reconcile_after_revoke(&state, &refresh_token, outcome)
}

#[cfg(test)]
mod tests {
    use super::{
        apply_local_logout, apply_login, finalize_pending_logout_revoke, get_auth_status,
        get_backend_config, get_shell_status, reconcile_after_refresh, reconcile_after_revoke,
    };
    use crate::auth::AuthSession;
    use crate::config::BackendConfig;
    use crate::credentials::{CredentialStore, InMemoryStore};
    use crate::error::ShellError;
    use crate::state::{ConnectionStatus, ShellState};
    use std::sync::Arc;

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

    #[test]
    fn apply_login_stores_active_session() {
        let state = ShellState::new(BackendConfig::default());

        let status = apply_login(&state, sample_session());

        assert!(status.is_authenticated);
        assert_eq!(status.session.unwrap().access_token, "access-token");
    }

    #[test]
    fn apply_local_logout_clears_session_but_keeps_pending_revoke() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());

        let status = apply_local_logout(&state).unwrap();

        assert!(!status.is_authenticated);
        assert_eq!(status.session, None);
        assert_eq!(status.pending_logout_revoke.as_deref(), Some("refresh-token"));
    }

    #[test]
    fn finalize_pending_logout_revoke_clears_pending_marker() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());
        apply_local_logout(&state).unwrap();

        let status = finalize_pending_logout_revoke(&state, "refresh-token");

        assert_eq!(status.pending_logout_revoke, None);
        assert!(!status.is_authenticated);
    }

    #[test]
    fn get_auth_status_reports_logged_out_state_after_local_logout() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());
        apply_local_logout(&state).unwrap();

        let status = get_auth_status(&state);

        assert_eq!(status.session, None);
        assert_eq!(status.pending_logout_revoke.as_deref(), Some("refresh-token"));
    }

    #[test]
    fn reconcile_after_revoke_clears_pending_on_success() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());
        apply_local_logout(&state).unwrap();

        let status = reconcile_after_revoke(&state, "refresh-token", Ok(())).unwrap();

        assert_eq!(status.pending_logout_revoke, None);
        assert!(!status.is_authenticated);
    }

    #[test]
    fn reconcile_after_revoke_clears_pending_when_backend_reports_token_already_invalid() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());
        apply_local_logout(&state).unwrap();

        // Server says the refresh token is expired/used/revoked/not-found.
        // The token is already unusable, so logout is effectively complete:
        // the pending marker must be cleared instead of retried forever.
        let result = reconcile_after_revoke(
            &state,
            "refresh-token",
            Err(ShellError::RevokeRejected("Refresh token already used".to_string())),
        );

        let status = result.expect("an already-invalid token should resolve logout, not error");
        assert_eq!(status.pending_logout_revoke, None);
        assert!(!status.is_authenticated);
    }

    #[test]
    fn reconcile_after_revoke_surfaces_unexpected_status_and_keeps_pending() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());
        apply_local_logout(&state).unwrap();

        // A genuine server failure (5xx, unparseable body) is not proof the
        // token is dead: surface the error and keep the marker for a later retry.
        let result = reconcile_after_revoke(
            &state,
            "refresh-token",
            Err(ShellError::UnexpectedHttpStatus(500)),
        );

        assert!(result.is_err());
        assert_eq!(
            state.pending_logout_revoke().as_deref(),
            Some("refresh-token")
        );
    }

    #[test]
    fn reconcile_after_refresh_stores_rotated_session_on_success() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());

        // Backend rotates the refresh token, so success carries a new session.
        let status = reconcile_after_refresh(&state, state.session_epoch(), Ok(rotated_session())).unwrap().status;

        assert!(status.is_authenticated);
        let session = status.session.expect("rotated session should be stored");
        assert_eq!(session.access_token, "rotated-access-token");
        assert_eq!(session.refresh_token, "rotated-refresh-token");
    }

    #[test]
    fn reconcile_after_refresh_drops_session_when_refresh_token_rejected() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());

        // An invalid/expired refresh token is unrecoverable: drop the local
        // session so the UI returns to login, with no pending-revoke retry.
        let status = reconcile_after_refresh(
            &state,
            state.session_epoch(),
            Err(ShellError::Unauthorized("Refresh token is invalid or expired".to_string())),
        )
        .expect("a rejected refresh token should resolve to logged-out, not error").status;

        assert!(!status.is_authenticated);
        assert_eq!(status.session, None);
        assert_eq!(status.pending_logout_revoke, None);
    }

    #[test]
    fn reconcile_after_refresh_keeps_session_and_surfaces_server_failure() {
        let state = ShellState::new(BackendConfig::default());
        apply_login(&state, sample_session());

        // A transient server failure is not proof the session is dead: keep it
        // and surface the error so the caller can retry.
        let result =
            reconcile_after_refresh(&state, state.session_epoch(), Err(ShellError::UnexpectedHttpStatus(500)));

        assert!(result.is_err());
        assert!(get_auth_status(&state).is_authenticated);
    }

    #[test]
    fn restored_session_persists_rotated_refresh_token() {
        // Restart: only the keychain holds the old refresh token.
        let store = Arc::new(InMemoryStore::default());
        ShellState::with_credential_store(BackendConfig::default(), store.clone())
            .set_auth_session(sample_session());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());

        let status = reconcile_after_refresh(&state, state.session_epoch(), Ok(rotated_session())).unwrap().status;

        assert!(status.is_authenticated);
        assert_eq!(
            store.load().unwrap().unwrap().refresh_token,
            "rotated-refresh-token"
        );
    }

    #[test]
    fn rejected_restore_clears_keychain_and_stays_logged_out() {
        let store = Arc::new(InMemoryStore::default());
        ShellState::with_credential_store(BackendConfig::default(), store.clone())
            .set_auth_session(sample_session());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());

        let status = reconcile_after_refresh(
            &state,
            state.session_epoch(),
            Err(ShellError::Unauthorized("Refresh token is invalid or expired".to_string())),
        )
        .unwrap().status;

        assert!(!status.is_authenticated);
        assert_eq!(store.load().unwrap(), None);
    }

    #[test]
    fn failed_restore_keeps_keychain_token_for_retry() {
        let store = Arc::new(InMemoryStore::default());
        ShellState::with_credential_store(BackendConfig::default(), store.clone())
            .set_auth_session(sample_session());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());

        let result = reconcile_after_refresh(&state, state.session_epoch(), Err(ShellError::UnexpectedHttpStatus(503)));

        assert!(result.is_err());
        assert_eq!(state.stored_refresh_token().as_deref(), Some("refresh-token"));
    }

    #[test]
    fn refresh_result_arriving_after_logout_is_discarded_for_revoke() {
        let store = Arc::new(InMemoryStore::default());
        let state = ShellState::with_credential_store(BackendConfig::default(), store.clone());
        apply_login(&state, sample_session());
        let epoch = state.session_epoch();

        // The user logs out while the refresh request is still in flight.
        apply_local_logout(&state).unwrap();
        let reconciled = reconcile_after_refresh(&state, epoch, Ok(rotated_session())).unwrap();

        assert!(!reconciled.status.is_authenticated);
        assert_eq!(
            reconciled.discarded_refresh_token.as_deref(),
            Some("rotated-refresh-token")
        );
        assert_eq!(store.load().unwrap(), None);
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
