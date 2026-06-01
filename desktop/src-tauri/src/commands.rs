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

    match auth_api.revoke(&config, &refresh_token).await {
        Ok(()) => Ok(finalize_pending_logout_revoke(&state, &refresh_token)),
        Err(ShellError::Request(_)) => Ok(get_auth_status(&state)),
        Err(error) => Err(error.to_string()),
    }
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

    match auth_api.revoke(&config, &refresh_token).await {
        Ok(()) => Ok(finalize_pending_logout_revoke(&state, &refresh_token)),
        Err(ShellError::Request(_)) => Ok(get_auth_status(&state)),
        Err(error) => Err(error.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        apply_local_logout, apply_login, finalize_pending_logout_revoke, get_auth_status,
        get_backend_config, get_shell_status,
    };
    use crate::auth::AuthSession;
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
