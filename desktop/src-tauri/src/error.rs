use thiserror::Error;

#[derive(Debug, Error)]
pub enum ShellError {
    #[error("shell state lock poisoned")]
    StatePoisoned,
    #[error("request failed: {0}")]
    Request(reqwest::Error),
    #[error("invalid username or password")]
    Unauthorized(String),
    #[error("unexpected backend status: {0}")]
    UnexpectedHttpStatus(u16),
    #[error("logout rejected by backend: {0}")]
    RevokeRejected(String),
    #[error("no active session")]
    NoActiveSession,
}

/// Strips the URL before wrapping: auth endpoints (e.g. revoke) carry the
/// refresh token in the path, and these messages reach stderr and the webview.
impl From<reqwest::Error> for ShellError {
    fn from(error: reqwest::Error) -> Self {
        ShellError::Request(error.without_url())
    }
}

impl ShellError {
    /// True when the backend has confirmed the refresh token can no longer be
    /// used (expired / used / revoked / not found). The revoke goal is already
    /// satisfied, so any pending-revoke marker should be cleared rather than
    /// retried.
    pub fn is_token_already_invalid(&self) -> bool {
        matches!(self, ShellError::RevokeRejected(_))
    }

    /// True for failures that may succeed on a later attempt (e.g. the backend
    /// is unreachable). A pending-revoke marker should be kept so the revoke
    /// can be retried once connectivity returns.
    pub fn is_transient(&self) -> bool {
        matches!(self, ShellError::Request(_))
    }
}

#[cfg(test)]
mod tests {
    use super::ShellError;

    #[tokio::test]
    async fn transport_error_message_does_not_leak_token_from_url() {
        // The revoke endpoint carries the refresh token in its path; a
        // connection failure must not echo that URL into logs or the webview.
        let error = reqwest::get("http://127.0.0.1:1/api/v1/auth/revoke/secret-refresh-token")
            .await
            .expect_err("nothing listens on port 1");

        let shell_error = ShellError::from(error);

        assert!(shell_error.is_transient());
        assert!(!shell_error.to_string().contains("secret-refresh-token"));
        assert!(!format!("{shell_error:?}").contains("secret-refresh-token"));
    }
}
