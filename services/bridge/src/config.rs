//! Configurable-but-simple settings: defaults for the tailnet-only MVP, an
//! optional JSON config file, then environment overrides. The `auth` section
//! is reserved: unset (or `enabled: false`) gives the no-auth MVP behavior;
//! `enabled: true` is rejected until an authenticator exists, so the config
//! format never has to change when one lands.

use iroh::RelayUrl;
use serde::Deserialize;
use serde_json::Value;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
pub struct BridgeConfig {
    pub bind: String,
    pub port: u16,
    pub data_dir: PathBuf,
    pub auth: AuthConfig,
    /// Status/startup apply the `tailscale serve` entry automatically when
    /// true (default); false never touches Tailscale (safe verification).
    pub serve_auto_apply: bool,
    /// Opt-in iroh transport (`iroh.enabled`, default false). When false no
    /// iroh endpoint is created and `GET /pair` is not served.
    pub iroh_enabled: bool,
    /// Self-hosted iroh relay (`iroh.relay_url`, absolute https). `None` keeps
    /// n0's relays. Set, the endpoint uses only this relay. Design:
    /// docs/design/public-exposure-iroh.md ("Relays").
    pub iroh_relay_url: Option<RelayUrl>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
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

impl Default for AuthConfig {
    fn default() -> Self {
        AuthConfig {
            enabled: false,
            options: serde_json::Map::new(),
        }
    }
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
    iroh: Option<IrohFileConfig>,
}

#[derive(Debug, Default, Deserialize)]
struct IrohFileConfig {
    enabled: Option<StrictBool>,
    relay_url: Option<String>,
}

/// Strict: an absolute https URL with a host and no surrounding whitespace.
/// Anything else is a startup error, never a silent fall back to n0.
pub fn parse_iroh_relay_url(text: &str) -> Result<RelayUrl, String> {
    let invalid = |reason: &str| format!("invalid iroh.relay_url '{text}': {reason}");
    if text.trim() != text {
        return Err(invalid("surrounding whitespace"));
    }
    let url: RelayUrl = text.parse().map_err(|error| invalid(&format!("{error}")))?;
    if url.scheme() != "https" {
        return Err(invalid("must be an https URL"));
    }
    if url.host_str().is_none() {
        return Err(invalid("must include a host"));
    }
    Ok(url)
}

/// Resolves an XDG base directory: the variable's value when it is an absolute
/// path, otherwise `$HOME/<home_relative>`. Relative and empty values are
/// ignored, as the XDG Base Directory spec requires.
fn xdg_base(xdg_value: Option<&str>, home: &str, home_relative: &str) -> PathBuf {
    match xdg_value.map(Path::new) {
        Some(path) if path.is_absolute() => path.to_path_buf(),
        _ => Path::new(home).join(home_relative),
    }
}

/// Default runtime state dir: `$XDG_STATE_HOME/cappuccino-bridge`, else
/// `$HOME/.local/state/cappuccino-bridge`, on macOS and Linux alike.
pub fn default_data_dir(xdg_state_home: Option<&str>, home: &str) -> PathBuf {
    xdg_base(xdg_state_home, home, ".local/state").join("cappuccino-bridge")
}

/// Config file path: `CAPP_BRIDGE_CONFIG` when set, else
/// `$XDG_CONFIG_HOME/cappuccino-bridge/config.toml` (if it exists), else
/// `$XDG_CONFIG_HOME/cappuccino-bridge/config.json`.
pub fn resolve_config_path(
    capp_bridge_config: Option<&str>,
    xdg_config_home: Option<&str>,
    home: &str,
) -> String {
    match capp_bridge_config {
        Some(path) => path.to_string(),
        None => {
            let base = xdg_base(xdg_config_home, home, ".config").join("cappuccino-bridge");
            let toml = base.join("config.toml");
            if toml.exists() {
                toml.to_string_lossy().into_owned()
            } else {
                base.join("config.json").to_string_lossy().into_owned()
            }
        }
    }
}

fn parse_config_file(contents: &str, path: &str) -> Result<ConfigFile, String> {
    if path.ends_with(".toml") {
        toml::from_str(contents)
            .map_err(|error| format!("config {path} is not valid TOML: {error}"))
    } else if path.ends_with(".json") {
        serde_json::from_str(contents)
            .map_err(|error| format!("config {path} is not valid JSON: {error}"))
    } else if contents.trim_start().starts_with('{') {
        serde_json::from_str(contents)
            .map_err(|error| format!("config {path} is not valid JSON: {error}"))
    } else {
        toml::from_str(contents)
            .map_err(|error| format!("config {path} is not valid TOML: {error}"))
    }
}

impl BridgeConfig {
    /// Defaults for the tailnet-only MVP.
    fn defaults() -> Self {
        BridgeConfig {
            bind: "127.0.0.1".to_string(),
            port: 7392,
            data_dir: default_data_dir(
                std::env::var("XDG_STATE_HOME").ok().as_deref(),
                &home_dir(),
            ),
            auth: AuthConfig::default(),
            serve_auto_apply: true,
            iroh_enabled: false,
            iroh_relay_url: None,
        }
    }

    /// File first (CAPP_BRIDGE_CONFIG or the XDG config path), then
    /// environment overrides. Unknown file fields are ignored so the config
    /// can be written forward-compatibly.
    pub fn load() -> Result<Self, String> {
        let path = resolve_config_path(
            std::env::var("CAPP_BRIDGE_CONFIG").ok().as_deref(),
            std::env::var("XDG_CONFIG_HOME").ok().as_deref(),
            &home_dir(),
        );
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
                let file = parse_config_file(&contents, path)?;
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
                if let Some(iroh) = file.iroh {
                    config.iroh_enabled = iroh.enabled.unwrap_or(StrictBool(false)).0;
                    config.iroh_relay_url = iroh
                        .relay_url
                        .as_deref()
                        .map(parse_iroh_relay_url)
                        .transpose()?;
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

#[cfg(test)]
mod toml_config_tests {
    use super::*;

    #[test]
    fn toml_config_parses_all_sections() {
        let toml = r#"
bind = "127.0.0.1"
port = 7392
data_dir = "/tmp/cappuccino-test"

[serve]
auto_apply = false

[iroh]
enabled = true
relay_url = "https://relay.azusachino.com"
"#;
        let path = std::env::temp_dir().join(format!("cap-test-{}.toml", std::process::id()));
        std::fs::write(&path, toml).unwrap();
        let cfg = BridgeConfig::load_from(Some(path.to_str().unwrap()), None, None, None).unwrap();
        assert_eq!(cfg.bind, "127.0.0.1");
        assert_eq!(cfg.port, 7392);
        assert_eq!(cfg.data_dir, PathBuf::from("/tmp/cappuccino-test"));
        assert!(!cfg.serve_auto_apply);
        assert!(cfg.iroh_enabled);
        assert_eq!(
            cfg.iroh_relay_url.unwrap().as_str(),
            "https://relay.azusachino.com/"
        );
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn toml_file_takes_precedence_over_json_when_both_exist() {
        let dir = std::env::temp_dir().join(format!("cap-xdg-test-{}", std::process::id()));
        let bridge_dir = dir.join("cappuccino-bridge");
        std::fs::create_dir_all(&bridge_dir).unwrap();
        std::fs::write(bridge_dir.join("config.json"), r#"{"port": 7392}"#).unwrap();
        std::fs::write(bridge_dir.join("config.toml"), "port = 8443\n").unwrap();

        let resolved = resolve_config_path(None, Some(dir.to_str().unwrap()), "/home/u");
        assert!(resolved.ends_with("config.toml"));
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[cfg(test)]
mod iroh_config_tests {
    use super::*;

    fn load(content: &str) -> Result<BridgeConfig, String> {
        static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let unique = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let path =
            std::env::temp_dir().join(format!("cap-iroh-cfg-{}-{unique}", std::process::id()));
        std::fs::write(&path, content).unwrap();
        BridgeConfig::load_from(Some(path.to_str().unwrap()), None, None, None)
    }

    #[test]
    fn iroh_is_disabled_by_default() {
        assert!(!BridgeConfig::defaults().iroh_enabled);
        assert!(!load("{}").unwrap().iroh_enabled);
    }

    #[test]
    fn iroh_enabled_is_a_strict_boolean() {
        assert!(load(r#"{"iroh": {"enabled": true}}"#).unwrap().iroh_enabled);
        assert!(load(r#"{"iroh": {"enabled": "on"}}"#).is_err());
        assert!(load(r#"{"iroh": {"enabled": "maybe"}}"#).is_err());
        assert!(!load(r#"{"iroh": {}}"#).unwrap().iroh_enabled);
    }

    #[test]
    fn retired_pair_token_ttl_is_a_parse_error() {
        assert!(load(r#"{"auth": {"pair_token_ttl_secs": 120}}"#).is_err());
    }

    #[test]
    fn relay_url_is_unset_by_default() {
        assert!(BridgeConfig::defaults().iroh_relay_url.is_none());
        assert!(load("{}").unwrap().iroh_relay_url.is_none());
        assert!(load(r#"{"iroh": {"enabled": true}}"#)
            .unwrap()
            .iroh_relay_url
            .is_none());
    }

    #[test]
    fn valid_relay_url_is_kept() {
        let config = load(r#"{"iroh": {"relay_url": "https://relay.example.test"}}"#).unwrap();
        let url = config.iroh_relay_url.expect("relay_url set");
        assert_eq!(url.scheme(), "https");
        assert_eq!(url.host_str(), Some("relay.example.test"));
    }

    #[test]
    fn invalid_relay_urls_are_startup_errors() {
        for text in [
            "",
            "relay.example.test",
            "/relative/path",
            "http://relay.example.test",
            "ftp://relay.example.test",
            "https://",
            " https://relay.example.test",
            "https://relay.example.test ",
        ] {
            let content = format!(
                r#"{{"iroh": {{"relay_url": {}}}}}"#,
                serde_json::json!(text)
            );
            let error = load(&content).unwrap_err();
            assert!(
                error.starts_with("invalid iroh.relay_url"),
                "{text:?} must be rejected, got {error}"
            );
        }
    }

    #[test]
    fn non_string_relay_url_is_a_parse_error() {
        assert!(load(r#"{"iroh": {"relay_url": 3}}"#).is_err());
    }
}

#[cfg(test)]
mod path_tests {
    use super::*;

    #[test]
    fn data_dir_uses_absolute_xdg_state_home() {
        assert_eq!(
            default_data_dir(Some("/xdg/state"), "/home/u"),
            PathBuf::from("/xdg/state/cappuccino-bridge")
        );
    }

    #[test]
    fn data_dir_falls_back_to_home_local_state_when_unset() {
        assert_eq!(
            default_data_dir(None, "/Users/u"),
            PathBuf::from("/Users/u/.local/state/cappuccino-bridge")
        );
    }

    #[test]
    fn data_dir_ignores_relative_and_empty_xdg_state_home() {
        for value in ["relative/state", "./state", ""] {
            assert_eq!(
                default_data_dir(Some(value), "/Users/u"),
                PathBuf::from("/Users/u/.local/state/cappuccino-bridge"),
                "XDG_STATE_HOME={value:?} must be ignored"
            );
        }
    }

    #[test]
    fn config_path_prefers_capp_bridge_config_over_xdg() {
        assert_eq!(
            resolve_config_path(Some("/etc/cap.json"), Some("/xdg/config"), "/home/u"),
            "/etc/cap.json"
        );
    }

    #[test]
    fn config_path_uses_absolute_xdg_config_home() {
        assert_eq!(
            resolve_config_path(None, Some("/xdg/config"), "/home/u"),
            "/xdg/config/cappuccino-bridge/config.json"
        );
    }

    #[test]
    fn config_path_falls_back_to_home_dot_config() {
        assert_eq!(
            resolve_config_path(None, None, "/Users/u"),
            "/Users/u/.config/cappuccino-bridge/config.json"
        );
        assert_eq!(
            resolve_config_path(None, Some("relative/config"), "/Users/u"),
            "/Users/u/.config/cappuccino-bridge/config.json"
        );
    }

    #[test]
    fn file_data_dir_and_env_override_win_over_default() {
        let path = std::env::temp_dir().join(format!("cap-datadir-{}", std::process::id()));
        std::fs::write(&path, r#"{"data_dir": "/from/file"}"#).unwrap();
        let file = path.to_str().unwrap();
        let from_file = BridgeConfig::load_from(Some(file), None, None, None).unwrap();
        assert_eq!(from_file.data_dir, PathBuf::from("/from/file"));
        let from_env = BridgeConfig::load_from(Some(file), None, None, Some("/from/env")).unwrap();
        assert_eq!(from_env.data_dir, PathBuf::from("/from/env"));
    }
}
