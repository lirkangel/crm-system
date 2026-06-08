use thiserror::Error;

#[derive(Debug, Error)]
pub enum ShellError {
    #[error("shell state lock poisoned")]
    StatePoisoned,
    #[error("request failed: {0}")]
    Request(#[from] reqwest::Error),
    #[error("invalid username or password")]
    Unauthorized(String),
    #[error("unexpected backend status: {0}")]
    UnexpectedHttpStatus(u16),
    #[error("logout rejected by backend: {0}")]
    RevokeRejected(String),
    #[error("no active session")]
    NoActiveSession,
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
