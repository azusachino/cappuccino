use crate::config::StrictBool;
use serde::Deserialize;
use std::path::PathBuf;

/// Events an owner can enable in `[telegram] events`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Event {
    Completion,
    NeedsAnswer,
    RouteFailure,
}

impl Event {
    /// The name the owner writes in `events` and the phone reads from status.
    pub(super) fn name(self) -> &'static str {
        match self {
            Event::Completion => "completion",
            Event::NeedsAnswer => "needs_answer",
            Event::RouteFailure => "route_failure",
        }
    }

    fn parse(text: &str) -> Result<Event, String> {
        match text {
            "completion" => Ok(Event::Completion),
            "needs_answer" => Ok(Event::NeedsAnswer),
            "route_failure" => Ok(Event::RouteFailure),
            other => Err(format!("telegram.events has unknown event '{other}'")),
        }
    }
}

/// The `[telegram]` table as written. Unknown keys are rejected so a typo never
/// silently disables an alert.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct File {
    pub enabled: Option<StrictBool>,
    pub chat_id: Option<String>,
    pub token_file: Option<PathBuf>,
    pub machine_label: Option<String>,
    pub events: Option<Vec<String>>,
    pub show_pane_names: Option<StrictBool>,
}

/// Validated settings for an enabled route. The token itself is read from
/// `token_file` at start and is never part of these settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Settings {
    pub chat_id: String,
    pub token_file: PathBuf,
    pub machine_label: String,
    pub events: Vec<Event>,
    /// Off by default. Any process in a pane can rename it, so the name is shown only when the owner opts in.
    pub show_pane_names: bool,
}

/// `Ok(None)` when the route is disabled. Shape errors fail startup; a missing or
/// unreadable token file does not, it is reported as `misconfigured` instead.
pub fn settings(file: File, max_label_chars: usize) -> Result<Option<Settings>, String> {
    if !file.enabled.unwrap_or(StrictBool(false)).0 {
        return Ok(None);
    }
    let chat_id = file.chat_id.unwrap_or_default();
    let digits = chat_id.strip_prefix('-').unwrap_or(&chat_id);
    if digits.is_empty() || !digits.chars().all(|c| c.is_ascii_digit()) {
        return Err("telegram.chat_id must be the numeric id of the group".to_string());
    }
    let token_file = file
        .token_file
        .ok_or_else(|| "telegram.token_file must be set".to_string())?;
    let machine_label = file.machine_label.unwrap_or_default();
    let label = machine_label.trim();
    if label.is_empty()
        || label.chars().count() > max_label_chars
        || label.chars().any(char::is_control)
    {
        return Err(format!(
            "telegram.machine_label must be 1-{max_label_chars} printable characters"
        ));
    }
    let mut events = Vec::new();
    for text in file.events.unwrap_or_else(|| {
        vec![
            "completion".to_string(),
            "needs_answer".to_string(),
            "route_failure".to_string(),
        ]
    }) {
        let event = Event::parse(&text)?;
        if !events.contains(&event) {
            events.push(event);
        }
    }
    Ok(Some(Settings {
        chat_id,
        token_file,
        machine_label: label.to_string(),
        events,
        show_pane_names: file.show_pane_names.unwrap_or(StrictBool(false)).0,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telegram::test_support::*;

    fn settings(file: File) -> Result<Option<Settings>, String> {
        super::settings(
            file,
            crate::tuning::Tuning::default().telegram.max_label_chars,
        )
    }

    #[test]
    fn disabled_route_has_no_settings() {
        assert_eq!(settings(file("enabled = false")).unwrap(), None);
        assert_eq!(settings(File::default()).unwrap(), None);
    }

    #[test]
    fn enabled_route_defaults_to_every_event() {
        let parsed = settings(file(
        "enabled = true\nchat_id = \"-100123\"\ntoken_file = \"/tmp/t\"\nmachine_label = \"mini\"",
    ))
    .unwrap()
    .unwrap();
        assert_eq!(
            parsed.events,
            vec![Event::Completion, Event::NeedsAnswer, Event::RouteFailure]
        );
        assert_eq!(parsed.chat_id, "-100123");
    }

    #[test]
    fn invalid_shape_fails_startup() {
        let base = "enabled = true\ntoken_file = \"/tmp/t\"\nmachine_label = \"mini\"\n";
        for extra in [
            "chat_id = \"@group\"",
            "chat_id = \"12a\"",
            "chat_id = \"-\"",
            "chat_id = \"1\"\nevents = [\"deploy\"]",
        ] {
            assert!(
                settings(file(&format!("{base}{extra}"))).is_err(),
                "{extra}"
            );
        }
        let blank_label =
            "enabled = true\nchat_id = \"1\"\ntoken_file = \"/tmp/t\"\nmachine_label = \"  \"";
        assert!(settings(file(blank_label)).is_err());
        // A typo'd key must fail the parse, not silently fall back to a default.
        assert!(toml::from_str::<File>(&format!("{base}chat_id = \"1\"\nmystery = 1")).is_err());
        assert!(settings(file(&format!("{base}chat_id = \"1\""))).is_ok());
    }

    #[test]
    fn configured_label_limit_is_enforced() {
        let base = "enabled = true\nchat_id = \"-100\"\ntoken_file = \"/tmp/t\"\n";
        let label = format!("machine_label = \"{}\"", "a".repeat(6));
        assert!(super::settings(file(&format!("{base}{label}")), 5).is_err());
        assert!(super::settings(file(&format!("{base}{label}")), 6).is_ok());
    }

    #[test]
    fn pane_names_are_off_unless_opted_in() {
        let base = "enabled = true\nchat_id = \"-100\"\ntoken_file = \"/tmp/t\"\nmachine_label = \"mini\"\n";
        let default = settings(file(base)).unwrap().unwrap();
        assert!(!default.show_pane_names);
        let opted_in = settings(file(&format!("{base}show_pane_names = true")))
            .unwrap()
            .unwrap();
        assert!(opted_in.show_pane_names);
    }
}
