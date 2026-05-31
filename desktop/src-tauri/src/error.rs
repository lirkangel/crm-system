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
