use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LoginPayload {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RefreshPayload {
    pub refresh_token: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AuthSession {
    pub access_token: String,
    pub access_token_expires_at: String,
    pub refresh_token: String,
    pub refresh_token_expires_at: String,
    pub token_type: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AuthStatus {
    pub session: Option<AuthSession>,
    pub pending_logout_revoke: Option<String>,
    pub is_authenticated: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum LogoutStatus {
    REVOKED,
    NOT_FOUND,
    EXPIRED,
    ALREADY_USED,
    ALREADY_REVOKED,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct LogoutResponse {
    pub status: LogoutStatus,
    pub message: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommonResponse<T> {
    pub success: bool,
    pub code: String,
    pub message: String,
    pub data: Option<T>,
}

#[cfg(test)]
mod tests {
    use super::{AuthSession, CommonResponse, LogoutResponse, LogoutStatus};

    #[test]
    fn auth_session_deserializes_backend_payload_shape() {
        let session: AuthSession = serde_json::from_str(
            r#"{
                "accessToken":"access-token",
                "accessTokenExpiresAt":"2030-01-01T00:15:00Z",
                "refreshToken":"refresh-token",
                "refreshTokenExpiresAt":"2030-01-08T00:00:00Z",
                "tokenType":"Bearer"
            }"#,
        )
        .unwrap();

        assert_eq!(session.access_token, "access-token");
        assert_eq!(session.refresh_token, "refresh-token");
        assert_eq!(session.token_type, "Bearer");
    }

    #[test]
    fn common_response_deserializes_failure_envelope() {
        let response: CommonResponse<LogoutResponse> = serde_json::from_str(
            r#"{
                "success": false,
                "code": "AUTH_LOGOUT_EXPIRED",
                "message": "Refresh token expired",
                "data": {
                    "status": "EXPIRED",
                    "message": "Refresh token expired"
                }
            }"#,
        )
        .unwrap();

        assert!(!response.success);
        assert_eq!(response.code, "AUTH_LOGOUT_EXPIRED");
        assert_eq!(
            response.data,
            Some(LogoutResponse {
                status: LogoutStatus::EXPIRED,
                message: "Refresh token expired".to_string(),
            })
        );
    }
}
