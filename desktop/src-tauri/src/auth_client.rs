use crate::auth::{AuthSession, CommonResponse, LoginPayload, OptionalCommonResponse};
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
                Ok(payload.data)
            }
            StatusCode::UNAUTHORIZED => {
                let payload: OptionalCommonResponse<AuthSession> =
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
        let payload: CommonResponse<bool> = response.json().await.map_err(ShellError::from)?;

        if status.is_success() && payload.data {
            Ok(())
        } else if status.is_success() {
            Err(ShellError::RevokeRejected(payload.message))
        } else {
            Err(ShellError::UnexpectedHttpStatus(status.as_u16()))
        }
    }
}

impl Default for AuthApiClient {
    fn default() -> Self {
        Self::new()
    }
}
