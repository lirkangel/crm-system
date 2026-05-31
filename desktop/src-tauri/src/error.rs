use thiserror::Error;

#[derive(Debug, Error)]
pub enum ShellError {
    #[error("shell state lock poisoned")]
    StatePoisoned,
}
