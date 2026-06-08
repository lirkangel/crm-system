use crate::auth::{
    AuthSession, CommonResponse, LoginPayload, LogoutResponse, LogoutStatus, RefreshPayload,
};
use crate::config::BackendConfig;
use crate::error::ShellError;
use reqwest::StatusCode;

#[derive(Debug, Clone)]
pub struct AuthApiClient {
    client: reqwest::Client,
}

impl AuthApiClient {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::new(),
        }
    }

    pub async fn login(
        &self,
        config: &BackendConfig,
        username: String,
        password: String,
    ) -> Result<AuthSession, ShellError> {
        let response = self
            .client
            .post(config.login_url())
            .json(&LoginPayload { username, password })
            .send()
            .await
            .map_err(ShellError::from)?;

        match response.status() {
            StatusCode::OK => {
                let payload: CommonResponse<AuthSession> =
                    response.json().await.map_err(ShellError::from)?;
                if payload.success {
                    payload
                        .data
                        .ok_or(ShellError::UnexpectedHttpStatus(StatusCode::OK.as_u16()))
                } else {
                    Err(ShellError::Unauthorized(payload.message))
                }
            }
            StatusCode::UNAUTHORIZED => {
                let payload: CommonResponse<AuthSession> =
                    response.json().await.map_err(ShellError::from)?;
                Err(ShellError::Unauthorized(payload.message))
            }
            status => Err(ShellError::UnexpectedHttpStatus(status.as_u16())),
        }
    }

    pub async fn refresh(
        &self,
        config: &BackendConfig,
        refresh_token: &str,
    ) -> Result<AuthSession, ShellError> {
        let response = self
            .client
            .post(config.refresh_url())
            .json(&RefreshPayload {
                refresh_token: refresh_token.to_string(),
            })
            .send()
            .await
            .map_err(ShellError::from)?;

        match response.status() {
            StatusCode::OK => {
                let payload: CommonResponse<AuthSession> =
                    response.json().await.map_err(ShellError::from)?;
                if payload.success {
                    payload
                        .data
                        .ok_or(ShellError::UnexpectedHttpStatus(StatusCode::OK.as_u16()))
                } else {
                    Err(ShellError::Unauthorized(payload.message))
                }
            }
            StatusCode::UNAUTHORIZED => {
                let payload: CommonResponse<AuthSession> =
                    response.json().await.map_err(ShellError::from)?;
                Err(ShellError::Unauthorized(payload.message))
            }
            status => Err(ShellError::UnexpectedHttpStatus(status.as_u16())),
        }
    }

    pub async fn revoke(&self, config: &BackendConfig, refresh_token: &str) -> Result<(), ShellError> {
        let response = self
            .client
            .delete(config.revoke_url(refresh_token))
            .send()
            .await
            .map_err(ShellError::from)?;

        let status = response.status();
        let payload: CommonResponse<LogoutResponse> = response.json().await.map_err(ShellError::from)?;

        match (status, payload.success, payload.data) {
            (StatusCode::OK, true, Some(LogoutResponse { status: LogoutStatus::REVOKED, .. })) => Ok(()),
            (_, _, Some(logout)) => Err(ShellError::RevokeRejected(logout.message)),
            (_, _, None) => Err(ShellError::UnexpectedHttpStatus(status.as_u16())),
        }
    }
}

impl Default for AuthApiClient {
    fn default() -> Self {
        Self::new()
    }
}
