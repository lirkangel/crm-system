//! Refresh-token persistence (D501).
//!
//! The refresh token is kept in the OS keychain (macOS Keychain, Windows
//! Credential Manager, Secret Service on Linux) so a session survives an app
//! restart without the token ever touching disk in plaintext. The access token
//! stays in memory only and is re-minted from the refresh token on startup.

use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use thiserror::Error;

pub const KEYCHAIN_SERVICE: &str = "com.crm.desktop";
pub const KEYCHAIN_ACCOUNT: &str = "refresh-token";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StoredRefreshToken {
    pub refresh_token: String,
    pub refresh_token_expires_at: String,
}

#[derive(Debug, Error)]
pub enum CredentialError {
    #[error("keychain error: {0}")]
    Keychain(String),
    #[error("stored credential is malformed: {0}")]
    Malformed(#[from] serde_json::Error),
}

/// Where the refresh token lives between app runs.
pub trait CredentialStore: Send + Sync + std::fmt::Debug {
    fn load(&self) -> Result<Option<StoredRefreshToken>, CredentialError>;
    fn save(&self, token: &StoredRefreshToken) -> Result<(), CredentialError>;
    /// Removes the stored token. Clearing an empty store is not an error.
    fn clear(&self) -> Result<(), CredentialError>;
}

/// OS-keychain-backed store used by the running app.
pub struct KeyringStore {
    entry: keyring::Entry,
}

impl KeyringStore {
    pub fn new(service: &str, account: &str) -> Result<Self, CredentialError> {
        let entry = keyring::Entry::new(service, account)
            .map_err(|error| CredentialError::Keychain(error.to_string()))?;
        Ok(Self { entry })
    }
}

impl std::fmt::Debug for KeyringStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KeyringStore").finish_non_exhaustive()
    }
}

impl CredentialStore for KeyringStore {
    fn load(&self) -> Result<Option<StoredRefreshToken>, CredentialError> {
        match self.entry.get_password() {
            Ok(json) => Ok(Some(serde_json::from_str(&json)?)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(CredentialError::Keychain(error.to_string())),
        }
    }

    fn save(&self, token: &StoredRefreshToken) -> Result<(), CredentialError> {
        let json = serde_json::to_string(token)?;
        self.entry
            .set_password(&json)
            .map_err(|error| CredentialError::Keychain(error.to_string()))
    }

    fn clear(&self) -> Result<(), CredentialError> {
        match self.entry.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(CredentialError::Keychain(error.to_string())),
        }
    }
}

/// Process-lifetime store: the fallback when no keychain is available, and the
/// default for tests. Holds the same JSON the keychain would, so a malformed
/// payload behaves identically.
#[derive(Debug, Default)]
pub struct InMemoryStore {
    slot: Mutex<Option<String>>,
}

impl InMemoryStore {
    #[cfg(test)]
    pub fn with_raw(raw: &str) -> Self {
        Self {
            slot: Mutex::new(Some(raw.to_string())),
        }
    }
}

impl CredentialStore for InMemoryStore {
    fn load(&self) -> Result<Option<StoredRefreshToken>, CredentialError> {
        match self.slot.lock().unwrap().as_deref() {
            Some(json) => Ok(Some(serde_json::from_str(json)?)),
            None => Ok(None),
        }
    }

    fn save(&self, token: &StoredRefreshToken) -> Result<(), CredentialError> {
        let json = serde_json::to_string(token)?;
        *self.slot.lock().unwrap() = Some(json);
        Ok(())
    }

    fn clear(&self) -> Result<(), CredentialError> {
        *self.slot.lock().unwrap() = None;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{
        CredentialError, CredentialStore, InMemoryStore, KEYCHAIN_SERVICE, KeyringStore,
        StoredRefreshToken,
    };

    #[test]
    fn empty_store_loads_none() {
        let store = InMemoryStore::default();

        assert_eq!(store.load().unwrap(), None);
    }

    #[test]
    fn saved_token_round_trips() {
        let store = InMemoryStore::default();

        store.save(&sample_token("refresh-token")).unwrap();

        assert_eq!(store.load().unwrap(), Some(sample_token("refresh-token")));
    }

    #[test]
    fn save_replaces_previous_token_after_rotation() {
        let store = InMemoryStore::default();
        store.save(&sample_token("refresh-token")).unwrap();

        store.save(&sample_token("rotated-refresh-token")).unwrap();

        assert_eq!(
            store.load().unwrap(),
            Some(sample_token("rotated-refresh-token"))
        );
    }

    #[test]
    fn clear_removes_token_and_is_idempotent() {
        let store = InMemoryStore::default();
        store.save(&sample_token("refresh-token")).unwrap();

        store.clear().unwrap();
        store.clear().unwrap();

        assert_eq!(store.load().unwrap(), None);
    }

    #[test]
    fn malformed_payload_is_reported_not_panicked() {
        let store = InMemoryStore::with_raw("not-json");

        assert!(matches!(store.load(), Err(CredentialError::Malformed(_))));
    }

    /// Hits the real OS keychain; run manually with `cargo test -- --ignored`
    /// (macOS may show an access prompt the first time).
    #[test]
    #[ignore]
    fn keyring_store_round_trips_through_os_keychain() {
        let store = KeyringStore::new(KEYCHAIN_SERVICE, "refresh-token-test").unwrap();
        store.clear().unwrap();

        store.save(&sample_token("keychain-refresh-token")).unwrap();
        let loaded = store.load().unwrap();
        store.clear().unwrap();

        assert_eq!(loaded, Some(sample_token("keychain-refresh-token")));
        assert_eq!(store.load().unwrap(), None);
    }

    fn sample_token(refresh_token: &str) -> StoredRefreshToken {
        StoredRefreshToken {
            refresh_token: refresh_token.to_string(),
            refresh_token_expires_at: "2030-01-08T00:00:00Z".to_string(),
        }
    }
}
