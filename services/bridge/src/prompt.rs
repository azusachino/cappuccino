//! Screen-state prompt and tool approval interceptor.
//! Recognizes interactive TUI prompts (tool approval, ask_question confirmation/choice,
//! option menus) from visible pane text and maps user responses back to Herdr keystrokes/prompts.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PromptCard {
    pub prompt_id: String,
    #[serde(rename = "type")]
    pub card_type: String, // "tool_approval" | "ask_question" | "confirm"
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    pub options: Vec<PromptOption>,
    pub selected_index: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PromptOption {
    pub id: String,
    pub label: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type")]
pub enum PromptSubmission {
    #[serde(rename = "prompt")]
    Prompt { text: String },
    #[serde(rename = "answer_prompt")]
    AnswerPrompt {
        prompt_id: String,
        action: Option<String>, // "select_option" | "cancel"
        option_index: Option<usize>,
        option_id: Option<String>,
    },
    #[serde(rename = "answer_question")]
    AnswerQuestion { prompt_id: String, answers: Value },
}

/// Computes a stable hash ID from the prompt title, message, and options
pub fn compute_prompt_id(title: &str, message: Option<&str>, options: &[PromptOption]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(title.as_bytes());
    if let Some(m) = message {
        hasher.update(m.as_bytes());
    }
    for opt in options {
        hasher.update(opt.id.as_bytes());
        hasher.update(opt.label.as_bytes());
    }
    let hash = hasher.finalize();
    format!("p-{}", &hex::encode(hash)[..12])
}

mod hex {
    pub fn encode<T: AsRef<[u8]>>(data: T) -> String {
        data.as_ref().iter().map(|b| format!("{b:02x}")).collect()
    }
}

/// Detects if the visible screen is presenting an interactive prompt.
pub fn parse_prompt_from_screen(screen_text: &str) -> Option<PromptCard> {
    let lines: Vec<&str> = screen_text.lines().map(str::trim).collect();

    // 1. Tool Approval Detection
    // Signatures: "Approve tool call", "Allow ... to run", "Execute command?"
    if let Some(card) = detect_tool_approval(&lines) {
        return Some(card);
    }

    // 2. Numbered Options / Choice Selection
    // Signatures: "enter to select", "enter to confirm" or explicit 1. / 2. choices
    if let Some(card) = detect_choice_menu(&lines) {
        return Some(card);
    }

    None
}

fn detect_tool_approval(lines: &[&str]) -> Option<PromptCard> {
    let approval_header_idx = lines.iter().position(|line| {
        line.contains("Approve tool call")
            || line.contains("Approve this tool call")
            || (line.contains("Allow ") && line.contains("to run"))
            || line.contains("Execute command?")
            || line.contains("Permission Required")
    })?;

    let header_line = lines[approval_header_idx];

    // Find tool command or details if present
    let command = lines
        .iter()
        .skip(approval_header_idx + 1)
        .find_map(|raw_line| {
            let line = raw_line.trim_matches(|c: char| c == '│' || c == ' ' || c == '\t');
            if line.starts_with('$') || line.starts_with('>') || line.starts_with("CommandLine:") {
                Some(
                    line.trim_start_matches(|c| c == '$' || c == '>' || c == ' ')
                        .trim()
                        .to_string(),
                )
            } else {
                None
            }
        });

    let default_options = vec![
        PromptOption {
            id: "allow_once".to_string(),
            label: "Allow Once".to_string(),
            description: None,
        },
        PromptOption {
            id: "allow_always".to_string(),
            label: "Allow Always".to_string(),
            description: None,
        },
        PromptOption {
            id: "reject".to_string(),
            label: "Reject".to_string(),
            description: None,
        },
    ];

    let prompt_id = compute_prompt_id(
        "Tool Permission Approval",
        command.as_deref(),
        &default_options,
    );

    Some(PromptCard {
        prompt_id,
        card_type: "tool_approval".to_string(),
        title: "Tool Permission Approval".to_string(),
        message: Some(header_line.to_string()),
        tool_name: Some("run_command".to_string()),
        command,
        options: default_options,
        selected_index: 0,
    })
}

fn detect_choice_menu(lines: &[&str]) -> Option<PromptCard> {
    // Look for hints like "enter select", "enter to confirm", "enter to select"
    let hint_idx = lines.iter().rposition(|line| {
        let l = line.to_lowercase();
        (l.contains("enter")
            && (l.contains("select") || l.contains("confirm") || l.contains("toggle")))
            || (l.contains("↑/↓") && l.contains("move"))
    })?;

    // Collect numbered options (e.g. "1. Yes", "2. No", or lines starting with ❯)
    let mut options = Vec::new();
    let mut selected_idx = 0;
    let mut first_opt_idx: Option<usize> = None;

    let scan_start = hint_idx.saturating_sub(15);
    for (offset, raw_line) in lines[scan_start..hint_idx].iter().enumerate() {
        let actual_idx = scan_start + offset;
        let line = raw_line.trim_matches(|c: char| c == '│' || c == ' ' || c == '\t');
        let is_selected = line.starts_with('❯') || line.starts_with('>');
        let clean = line.trim_start_matches(|c| c == '❯' || c == '>' || c == ' ');

        // Check for "1. Option" or "[x] Option" or simple list items
        if let Some(pos) = clean.find(". ") {
            let num_str = &clean[..pos];
            if num_str.chars().all(|c| c.is_ascii_digit()) {
                let label = clean[pos + 2..].trim().to_string();
                if first_opt_idx.is_none() {
                    first_opt_idx = Some(actual_idx);
                }
                if is_selected {
                    selected_idx = options.len();
                }
                options.push(PromptOption {
                    id: format!("opt-{}", options.len()),
                    label,
                    description: None,
                });
            }
        } else if clean.starts_with('(') && clean.contains(')') {
            // e.g. "(Recommended) Option"
            if first_opt_idx.is_none() {
                first_opt_idx = Some(actual_idx);
            }
            if is_selected {
                selected_idx = options.len();
            }
            options.push(PromptOption {
                id: format!("opt-{}", options.len()),
                label: clean.to_string(),
                description: None,
            });
        }
    }

    if options.len() < 2 {
        return None;
    }

    // Try finding the question line above the first option
    let opt_bound = first_opt_idx.unwrap_or(scan_start);
    let question = lines[..opt_bound]
        .iter()
        .rev()
        .find(|l| {
            let t = l.trim_matches(|c: char| c == '│' || c == ' ' || c == '\t');
            !t.is_empty() && !t.starts_with('┌') && !t.starts_with('├')
        })
        .map(|s| {
            s.trim_matches(|c: char| c == '│' || c == ' ' || c == '\t')
                .to_string()
        });

    let title = question
        .clone()
        .unwrap_or_else(|| "Select Option".to_string());
    let prompt_id = compute_prompt_id(&title, question.as_deref(), &options);

    Some(PromptCard {
        prompt_id,
        card_type: "ask_question".to_string(),
        title,
        message: question,
        tool_name: None,
        command: None,
        options,
        selected_index: selected_idx,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_tool_approval_screen() {
        let screen = r#"
┌─────────────────────────────────────────────────────────┐
│ Approve tool call: run_command                          │
│ $ git push origin main                                  │
│                                                         │
│ 1. Allow once                                           │
│ 2. Allow always                                         │
│ 3. Reject                                               │
│                                                         │
│ enter to select · ↑/↓ to move                           │
└─────────────────────────────────────────────────────────┘
"#;
        let card = parse_prompt_from_screen(screen).expect("tool approval prompt");
        assert_eq!(card.card_type, "tool_approval");
        assert_eq!(card.tool_name.as_deref(), Some("run_command"));
        assert_eq!(card.command.as_deref(), Some("git push origin main"));
        assert_eq!(card.options.len(), 3);
        assert_eq!(card.options[0].id, "allow_once");
    }

    #[test]
    fn detects_choice_menu_screen() {
        let screen = r#"
Which backend framework should we use?
1. Axum (Recommended)
2. Actix-web

enter select · ↑/↓ move · esc cancel
"#;
        let card = parse_prompt_from_screen(screen).expect("choice prompt");
        assert_eq!(card.card_type, "ask_question");
        assert_eq!(card.title, "Which backend framework should we use?");
        assert_eq!(card.options.len(), 2);
        assert_eq!(card.options[0].label, "Axum (Recommended)");
        assert_eq!(card.options[1].label, "Actix-web");
    }

    #[test]
    fn ignores_normal_scrollback_without_prompts() {
        let screen = r#"
Running cargo test...
test result: ok. 15 passed; 0 failed
All tests completed in 0.4s
"#;
        assert!(parse_prompt_from_screen(screen).is_none());
    }
}
