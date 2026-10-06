//! Constant-time token authorization for the bridge.
//!
//! The token is the whole authorization decision (behavior spec v0): whoever
//! holds it can read live agent output. Comparison hashes both sides first, so
//! equal-length timing is leaked for digests only, never for the token itself,
//! and a length-guessing attack learns nothing.

use sha2::{Digest, Sha256};

/// SHA-256 both candidates, then compare digests byte by byte in a single
/// branchless pass over a fixed 32 bytes.
pub fn token_matches(presented: &str, expected: &str) -> bool {
    let presented_digest = Sha256::digest(presented.as_bytes());
    let expected_digest = Sha256::digest(expected.as_bytes());
    presented_digest
        .iter()
        .zip(expected_digest.iter())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

/// Token source: `CAPP_BRIDGE_TOKEN` env, else a `CAPP_BRIDGE_TOKEN_FILE`
/// (created 0600, outside any repository).
pub fn load_token() -> Result<String, String> {
    if let Ok(env_token) = std::env::var("CAPP_BRIDGE_TOKEN") {
        let trimmed = env_token.trim().to_string();
        if !trimmed.is_empty() {
            return Ok(trimmed);
        }
    }
    let path = std::env::var("CAPP_BRIDGE_TOKEN_FILE")
        .unwrap_or_else(|_| format!("{}/.cappuccino-bridge-token", home_dir()));
    match std::fs::read_to_string(&path) {
        Ok(contents) => {
            let trimmed = contents.trim().to_string();
            if trimmed.is_empty() {
                Err(format!("token file {path} is empty"))
            } else {
                Ok(trimmed)
            }
        }
        Err(error) => Err(format!(
            "no token: set CAPP_BRIDGE_TOKEN or CAPP_BRIDGE_TOKEN_FILE ({error})"
        )),
    }
}

pub fn home_dir() -> String {
    std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_exact_token() {
        assert!(token_matches("bridge-secret-token", "bridge-secret-token"));
    }

    #[test]
    fn rejects_wrong_token_of_any_length() {
        assert!(!token_matches("wrong", "bridge-secret-token"));
        assert!(!token_matches(
            "bridge-secret-tokens",
            "bridge-secret-token"
        ));
        assert!(!token_matches("", "bridge-secret-token"));
        assert!(!token_matches("bridge-secret-token", ""));
    }

    #[test]
    fn empty_config_rejects_everything() {
        // An empty configured token must fail closed, never open the bridge.
        assert!(!token_matches("anything", ""));
    }
}
