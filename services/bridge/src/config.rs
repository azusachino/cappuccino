//! Configurable-but-simple settings: defaults for the tailnet-only MVP, an
//! optional JSON config file, then environment overrides. The `auth` section
//! is reserved: unset (or `enabled: false`) gives the no-auth MVP behavior;
//! `enabled: true` is rejected until an authenticator exists, so the config
//! format never has to change when one lands.

use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Clone)]
pub struct BridgeConfig {
    pub bind: String,
    pub port: u16,
    pub data_dir: PathBuf,
    pub auth: AuthConfig,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[allow(dead_code)] // reserved sections are parsed now, consumed by a later authenticator
pub struct AuthConfig {
    /// Reserved. The MVP ships auth-free behind the tailnet/loopback boundary.
    #[serde(default)]
    pub enabled: bool,
    /// Reserved for a future authenticator's settings (accepted and ignored
    /// until one exists, so the config never changes shape).
    #[serde(default)]
    pub options: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Default, Deserialize)]
struct ConfigFile {
    bind: Option<String>,
    port: Option<u16>,
    data_dir: Option<PathBuf>,
    auth: Option<AuthConfig>,
}

impl BridgeConfig {
    /// Defaults for the tailnet-only MVP.
    fn defaults() -> Self {
        BridgeConfig {
            bind: "127.0.0.1".to_string(),
            port: 7392,
            data_dir: PathBuf::from(format!(
                "{}/Library/Application Support/cappuccino-bridge",
                home_dir()
            )),
            auth: AuthConfig::default(),
        }
    }

    /// File first (CAPP_BRIDGE_CONFIG or ~/.config/cappuccino-bridge/config.json),
    /// then environment overrides. Unknown file fields are ignored so the
    /// config can be written forward-compatibly.
    pub fn load() -> Result<Self, String> {
        let path = std::env::var("CAPP_BRIDGE_CONFIG")
            .unwrap_or_else(|_| format!("{}/.config/cappuccino-bridge/config.json", home_dir()));
        Self::load_from(
            Some(&path),
            std::env::var("CAPP_BRIDGE_HOST").ok().as_deref(),
            std::env::var("CAPP_BRIDGE_PORT").ok().as_deref(),
            std::env::var("CAPP_BRIDGE_DATA_DIR").ok().as_deref(),
        )
    }

    /// Pure form for tests: no process-env reads (tests run in parallel and
    /// must not race each other's environment).
    pub fn load_from(
        config_path: Option<&str>,
        host_override: Option<&str>,
        port_override: Option<&str>,
        data_dir_override: Option<&str>,
    ) -> Result<Self, String> {
        let mut config = Self::defaults();
        if let Some(path) = config_path {
            if let Ok(contents) = std::fs::read_to_string(path) {
                let file: ConfigFile = serde_json::from_str(&contents)
                    .map_err(|error| format!("config {path} is not valid JSON: {error}"))?;
                if let Some(bind) = file.bind {
                    config.bind = bind;
                }
                if let Some(port) = file.port {
                    config.port = port;
                }
                if let Some(data_dir) = file.data_dir {
                    config.data_dir = data_dir;
                }
                if let Some(auth) = file.auth {
                    config.auth = auth;
                }
            }
        }
        if let Some(bind) = host_override {
            config.bind = bind.to_string();
        }
        if let Some(port) = port_override {
            config.port = port
                .parse()
                .map_err(|error| format!("CAPP_BRIDGE_PORT: {error}"))?;
        }
        if let Some(data_dir) = data_dir_override {
            config.data_dir = PathBuf::from(data_dir);
        }
        if config.auth.enabled {
            return Err(
                "auth.enabled is reserved: no authenticator ships in this MVP; \
                 leave it unset for the no-auth tailnet/loopback behavior"
                    .to_string(),
            );
        }
        Ok(config)
    }
}

fn home_dir() -> String {
    std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_loopback_no_auth() {
        let config = BridgeConfig::defaults();
        assert_eq!(config.bind, "127.0.0.1");
        assert_eq!(config.port, 7392);
        assert!(!config.auth.enabled);
    }

    #[test]
    fn file_overrides_defaults() {
        let dir = std::env::temp_dir().join(format!("cap-cfg-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("config.json");
        std::fs::write(
            &path,
            r#"{"port": 7400, "auth": {"enabled": false}, "unknown_future": true}"#,
        )
        .unwrap();
        let config =
            BridgeConfig::load_from(Some(path.to_str().unwrap()), None, None, None).unwrap();
        assert_eq!(config.port, 7400);
        assert!(!config.auth.enabled, "disabled auth stays the MVP behavior");
        let config =
            BridgeConfig::load_from(Some(path.to_str().unwrap()), None, Some("7401"), None)
                .unwrap();
        assert_eq!(config.port, 7401);
    }

    #[test]
    fn enabled_auth_is_rejected_until_an_authenticator_exists() {
        let dir = std::env::temp_dir().join(format!("cap-cfg-auth-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("config.json");
        std::fs::write(&path, r#"{"auth": {"enabled": true}}"#).unwrap();
        let error =
            BridgeConfig::load_from(Some(path.to_str().unwrap()), None, None, None).unwrap_err();
        assert!(error.contains("reserved"));
    }
}
