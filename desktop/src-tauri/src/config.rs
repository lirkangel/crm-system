use serde::Serialize;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BackendConfig {
    pub base_url: String,
    pub ws_path: String,
    pub health_path: String,
}

impl Default for BackendConfig {
    fn default() -> Self {
        Self {
            base_url: "http://127.0.0.1:8082".to_string(),
            ws_path: "/ws".to_string(),
            health_path: "/actuator/health".to_string(),
        }
    }
}

impl BackendConfig {
    pub fn login_url(&self) -> String {
        format!("{}/api/v1/auth/login", self.base_url.trim_end_matches('/'))
    }

    pub fn revoke_url(&self, refresh_token: &str) -> String {
        format!(
            "{}/api/v1/auth/revoke/{}",
            self.base_url.trim_end_matches('/'),
            refresh_token
        )
    }
}

#[cfg(test)]
mod tests {
    use super::BackendConfig;

    #[test]
    fn backend_config_defaults_to_local_server_url() {
        let config = BackendConfig::default();

        assert_eq!(config.base_url, "http://127.0.0.1:8082");
        assert_eq!(config.ws_path, "/ws");
        assert_eq!(config.health_path, "/actuator/health");
    }

    #[test]
    fn backend_config_builds_auth_urls() {
        let config = BackendConfig::default();

        assert_eq!(config.login_url(), "http://127.0.0.1:8082/api/v1/auth/login");
        assert_eq!(
            config.revoke_url("refresh-token"),
            "http://127.0.0.1:8082/api/v1/auth/revoke/refresh-token"
        );
    }
}
