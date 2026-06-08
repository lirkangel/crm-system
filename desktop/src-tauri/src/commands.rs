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

/// Reconciles shell state with the outcome of an access-token refresh attempt.
///
/// - success → store the rotated session (new access *and* refresh token);
/// - the refresh token was rejected → drop the session so the UI returns to
///   login (the token is already dead server-side, so no revoke is queued);
/// - any other failure → keep the session and surface the error for retry.
fn reconcile_after_refresh(
    state: &ShellState,
    result: Result<AuthSession, ShellError>,
) -> Result<AuthStatus, String> {
    match result {
        Ok(session) => Ok(state.set_auth_session(session)),
        Err(ShellError::Unauthorized(_)) => Ok(state.clear_session()),
        Err(error) => Err(error.to_string()),
    }
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
    let Some(session) = state.auth_status().session else {
        return Ok(get_auth_status(&state));
    };
    let config = state.backend_config();

    let outcome = auth_api.refresh(&config, &session.refresh_token).await;
    reconcile_after_refresh(&state, outcome)
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
    use crate::error::ShellError;
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
        let status = reconcile_after_refresh(&state, Ok(rotated_session())).unwrap();

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
            Err(ShellError::Unauthorized("Refresh token is invalid or expired".to_string())),
        )
        .expect("a rejected refresh token should resolve to logged-out, not error");

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
            reconcile_after_refresh(&state, Err(ShellError::UnexpectedHttpStatus(500)));

        assert!(result.is_err());
        assert!(get_auth_status(&state).is_authenticated);
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
