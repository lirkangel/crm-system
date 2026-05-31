use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LoginPayload {
    pub username: String,
    pub password: String,
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

#[derive(Debug, Deserialize)]
pub struct CommonResponse<T> {
    pub data: T,
    pub message: String,
}

#[derive(Debug, Deserialize)]
pub struct OptionalCommonResponse<T> {
    #[serde(rename = "data")]
    pub _data: Option<T>,
    pub message: String,
}

#[cfg(test)]
mod tests {
    use super::AuthSession;

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
}
