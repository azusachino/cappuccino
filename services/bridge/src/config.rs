//! Configurable-but-simple settings: defaults for the tailnet-only MVP, an
//! optional JSON config file, then environment overrides. The `auth` section
//! is reserved: unset (or `enabled: false`) gives the no-auth MVP behavior;
//! `enabled: true` is rejected until an authenticator exists, so the config
//! format never has to change when one lands.

use serde::Deserialize;
use serde_json::Value;
use std::path::PathBuf;

#[derive(Debug, Clone)]
pub struct BridgeConfig {
    pub bind: String,
    pub port: u16,
    pub data_dir: PathBuf,
    pub auth: AuthConfig,
    /// Status/startup apply the `tailscale serve` entry automatically when
    /// true (default); false never touches Tailscale (safe verification).
    pub serve_auto_apply: bool,
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

/// Strict knob: accepted values are true/false (JSON booleans) or the
/// strings "0"/"false"/"no"/"off"/"true" (case-insensitive). Anything else is
/// a parse error — never a silent default.
#[derive(Debug, Clone, Copy, Default)]
pub struct StrictBool(pub bool);

impl<'de> Deserialize<'de> for StrictBool {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let value = Value::deserialize(deserializer)?;
        match value {
            Value::Bool(flag) => Ok(StrictBool(flag)),
            Value::String(text) => match text.trim().to_lowercase().as_str() {
                "true" => Ok(StrictBool(true)),
                "0" | "false" | "no" | "off" => Ok(StrictBool(false)),
                other => Err(serde::de::Error::custom(format!(
                    "invalid serve.auto_apply value '{other}' — accepted: true, 0, false, no, off (case-insensitive)"
                ))),
            },
            other => Err(serde::de::Error::custom(format!(
                "invalid serve.auto_apply value {other} — accepted: true, 0, false, no, off (case-insensitive)"
            ))),
        }
    }
}

#[derive(Debug, Default, Deserialize)]
pub struct ServeConfig {
    #[serde(default, deserialize_with = "deserialize_default_true")]
    pub auto_apply: StrictBool,
}

fn deserialize_default_true<'de, D>(deserializer: D) -> Result<StrictBool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let option = Option::<StrictBool>::deserialize(deserializer)?;
    Ok(option.unwrap_or(StrictBool(true)))
}

#[derive(Debug, Default, Deserialize)]
struct ConfigFile {
    bind: Option<String>,
    port: Option<u16>,
    data_dir: Option<PathBuf>,
    auth: Option<AuthConfig>,
    serve: Option<ServeConfig>,
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
            serve_auto_apply: true,
        }
    }

    /// File first (CAPP_BRIDGE_CONFIG or ~/.config/cappuccino-bridge/config.json),
    /// then environment overrides. Unknown file fields are ignored so the
    /// config can be written forward-compatibly.
    pub fn load() -> Result<Self, String> {
        let path = std::env::var("CAPP_BRIDGE_CONFIG")
            .unwrap_or_else(|_| format!("{}/.config/cappuccino-bridge/config.json", home_dir()));
        Self::load_from_with_serve(
            Some(&path),
            std::env::var("CAPP_BRIDGE_HOST").ok().as_deref(),
            std::env::var("CAPP_BRIDGE_PORT").ok().as_deref(),
            std::env::var("CAPP_BRIDGE_DATA_DIR").ok().as_deref(),
            std::env::var("CAPP_BRIDGE_SERVE_AUTO_APPLY")
                .ok()
                .as_deref(),
        )
    }

    /// Pure form for tests: no process-env reads (tests run in parallel and
    /// must not race each other's environment).
    #[cfg(test)]
    fn load_from(
        config_path: Option<&str>,
        host_override: Option<&str>,
        port_override: Option<&str>,
        data_dir_override: Option<&str>,
    ) -> Result<Self, String> {
        Self::load_from_with_serve(
            config_path,
            host_override,
            port_override,
            data_dir_override,
            None,
        )
    }

    fn load_from_with_serve(
        config_path: Option<&str>,
        host_override: Option<&str>,
        port_override: Option<&str>,
        data_dir_override: Option<&str>,
        serve_auto_apply_override: Option<&str>,
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
                if let Some(serve) = file.serve {
                    config.serve_auto_apply = serve.auto_apply.0;
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
        if let Some(value) = serve_auto_apply_override {
            config.serve_auto_apply = parse_serve_auto_apply(value)?;
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

fn parse_serve_auto_apply(value: &str) -> Result<bool, String> {
    match value.trim().to_lowercase().as_str() {
        "true" => Ok(true),
        "0" | "false" | "no" | "off" => Ok(false),
        _ => Err(format!(
            "invalid CAPP_BRIDGE_SERVE_AUTO_APPLY value '{value}' — accepted: true, 0, false, no, off (case-insensitive)"
        )),
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

#[cfg(test)]
mod knob_tests {
    use super::*;

    fn config_file(content: &str) -> String {
        // Unique per call: these tests run in parallel within one process.
        static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let unique = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "cap-knob-{}-{unique}-{}",
            std::process::id(),
            content.len()
        ));
        std::fs::write(&path, content).unwrap();
        path.to_string_lossy().to_string()
    }

    #[test]
    fn accepted_disable_values_parse_false_case_insensitively() {
        for text in ["0", "false", "no", "off", "FALSE", "No", "OFF"] {
            let path = config_file(&format!(r#"{{"serve": {{"auto_apply": "{text}"}}}}"#));
            let config = BridgeConfig::load_from(Some(&path), None, None, None).unwrap();
            assert!(
                !config.serve_auto_apply,
                "string '{text}' must disable auto-apply"
            );
        }
    }

    #[test]
    fn true_and_bool_true_parse_enabled() {
        let a = config_file(r#"{"serve": {"auto_apply": true}}"#);
        let b = config_file(r#"{"serve": {"auto_apply": "true"}}"#);
        assert!(
            BridgeConfig::load_from(Some(&a), None, None, None)
                .unwrap()
                .serve_auto_apply
        );
        assert!(
            BridgeConfig::load_from(Some(&b), None, None, None)
                .unwrap()
                .serve_auto_apply
        );
    }

    #[test]
    fn invalid_string_is_a_parse_error_not_a_silent_default() {
        let path = config_file(r#"{"serve": {"auto_apply": "maybe"}}"#);
        let error = BridgeConfig::load_from(Some(&path), None, None, None).unwrap_err();
        assert!(
            error.contains("invalid serve.auto_apply value 'maybe'"),
            "{error}"
        );
        assert!(error.contains("0, false, no, off"));
    }

    #[test]
    fn environment_override_is_strict_and_case_insensitive() {
        for value in ["true", "TRUE"] {
            assert!(
                BridgeConfig::load_from_with_serve(None, None, None, None, Some(value))
                    .unwrap()
                    .serve_auto_apply
            );
        }
        for value in ["0", "false", "no", "off", "FALSE", "No", "OFF"] {
            assert!(
                !BridgeConfig::load_from_with_serve(None, None, None, None, Some(value))
                    .unwrap()
                    .serve_auto_apply
            );
        }
        for value in ["", "1", "maybe"] {
            let error = BridgeConfig::load_from_with_serve(None, None, None, None, Some(value))
                .unwrap_err();
            assert!(
                error.contains("invalid CAPP_BRIDGE_SERVE_AUTO_APPLY"),
                "{error}"
            );
        }
    }

    #[test]
    fn invalid_type_is_a_parse_error() {
        let path = config_file(r#"{"serve": {"auto_apply": 3}}"#);
        let error = BridgeConfig::load_from(Some(&path), None, None, None).unwrap_err();
        assert!(error.contains("invalid serve.auto_apply"));
    }
}
