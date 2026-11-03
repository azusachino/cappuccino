use std::path::Path;

/// The bot token. Never printed: its `Debug` is redacted and it has no `Display`.
#[derive(Clone)]
pub struct Token(pub(super) String);

impl std::fmt::Debug for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Token(<redacted>)")
    }
}

/// Reads the token from its file. A missing, empty or multi-word file is an error;
/// the error carries no detail, so no path or content reaches the status or logs.
pub fn read_token(path: &Path) -> Result<Token, ()> {
    let text = std::fs::read_to_string(path).map_err(|_| ())?;
    let token = text.trim();
    if token.is_empty() || token.contains(char::is_whitespace) {
        return Err(());
    }
    Ok(Token(token.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_debug_is_redacted() {
        let token = Token("123:secret".into());
        assert_eq!(format!("{token:?}"), "Token(<redacted>)");
    }

    #[test]
    fn unreadable_token_file_is_an_error_without_detail() {
        assert!(read_token(Path::new("/does/not/exist")).is_err());
    }
}
