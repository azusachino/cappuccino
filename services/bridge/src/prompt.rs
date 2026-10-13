//! Screen-state prompt and tool approval interceptor.
//! Recognizes interactive TUI prompts (tool approval, ask_question confirmation/choice,
//! option menus) from visible pane text and maps user responses back to Herdr keystrokes/prompts.

use serde::{Deserialize, Serialize};
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
    /// AskUserQuestion's questions, read from the transcript; empty for screen-read prompts.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub questions: Vec<crate::questions::Question>,
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
    AnswerQuestion {
        prompt_id: String,
        answers: Vec<crate::questions::Answer>,
    },
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

/// The card for whatever prompt the screen shows. Claude's question form has its own reader: the
/// generic menu reader would take its "Type something" and "Chat about this" rows for answers.
pub fn screen_card(screen: &str) -> Option<PromptCard> {
    // Scrollback can still hold the form above its review; the later one is what shows.
    let review_last =
        screen.rfind("Ready to submit your answers?") > screen.rfind("Enter to select");
    let review = || crate::questions::review(screen);
    let form = || crate::questions::form(screen).map(|form| crate::questions::card(&form));
    let card = if review_last {
        review().or_else(form)
    } else {
        form().or_else(review)
    };
    if card.is_some() {
        return card;
    }
    if crate::questions::on_screen(screen) {
        return None;
    }
    parse_prompt_from_screen(screen)
}

/// Content ids repeat when an agent asks the same thing twice, so a stale tap could answer the
/// newer prompt. Each pane's shown card keeps one nonce until the card leaves the screen. An
/// answered card is spent (`None`) and stays hidden until it leaves, so the screen catching up
/// cannot announce it again.
static SHOWN: std::sync::Mutex<std::collections::BTreeMap<String, (String, Option<String>)>> =
    std::sync::Mutex::new(std::collections::BTreeMap::new());

fn fresh_nonce() -> String {
    static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    static START: std::sync::OnceLock<u128> = std::sync::OnceLock::new();
    let start = START.get_or_init(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or_default()
    });
    let n = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let mut hasher = Sha256::new();
    hasher.update(start.to_le_bytes());
    hasher.update(n.to_le_bytes());
    hex::encode(hasher.finalize())[..8].to_string()
}

/// Records what the pane shows now and returns the card with its per-showing id; `None` when no
/// card shows or the one showing was already answered.
pub fn shown(pane_id: &str, card: Option<PromptCard>) -> Option<PromptCard> {
    let mut map = SHOWN.lock().unwrap_or_else(|e| e.into_inner());
    let Some(mut card) = card else {
        map.remove(pane_id);
        return None;
    };
    let base = card.prompt_id.clone();
    let nonce = match map.get(pane_id) {
        Some((seen, nonce)) if *seen == base => nonce.clone()?,
        _ => {
            let nonce = fresh_nonce();
            map.insert(pane_id.to_string(), (base.clone(), Some(nonce.clone())));
            nonce
        }
    };
    card.prompt_id = format!("{base}-{nonce}");
    Some(card)
}

/// Spends `prompt_id` if it is still the pane's live card, atomically, so of two racing answers
/// only one types. False means it was already answered or is no longer shown.
pub fn claim(pane_id: &str, prompt_id: &str) -> bool {
    let mut map = SHOWN.lock().unwrap_or_else(|e| e.into_inner());
    match map.get_mut(pane_id) {
        Some((base, nonce @ Some(_)))
            if prompt_id == format!("{base}-{}", nonce.as_deref().unwrap_or_default()) =>
        {
            *nonce = None;
            true
        }
        _ => false,
    }
}

/// Herdr refused the answer, so the card may still be live: the next read shows it with a new id.
pub fn forget(pane_id: &str) {
    SHOWN
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(pane_id);
}

/// A waiting prompt sits at the bottom of the pane; text further up is scrollback.
const PROMPT_TAIL_LINES: usize = 25;

/// Detects if the visible screen is presenting an interactive prompt.
pub fn parse_prompt_from_screen(screen_text: &str) -> Option<PromptCard> {
    let lines: Vec<&str> = screen_text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect();
    let lines = &lines[lines.len().saturating_sub(PROMPT_TAIL_LINES)..];

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
    // A header quoted in output is not a prompt: the live card lists its numbered choices.
    let lists_choices = lines[approval_header_idx + 1..].iter().any(|raw_line| {
        let line = raw_line
            .trim_matches(|c: char| c == '│' || c == ' ' || c == '\t')
            .trim_start_matches(|c| c == '❯' || c == '>' || c == ' ');
        line.starts_with("1. ")
    });
    if !lists_choices {
        return None;
    }

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
        questions: Vec::new(),
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
        questions: Vec::new(),
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

    const APPROVAL: &str = "Approve tool call: run_command\n$ git push origin main\n1. Allow once\n2. Allow always\n3. Reject\n";

    #[test]
    fn ignores_approval_text_quoted_in_output() {
        let quoted = "grep found:\n    || line.contains(\"Permission Required\")\nDone.\n";
        assert!(parse_prompt_from_screen(quoted).is_none());
        let scrolled = format!("{APPROVAL}{}", "later output\n".repeat(PROMPT_TAIL_LINES));
        assert!(parse_prompt_from_screen(&scrolled).is_none());
        assert!(parse_prompt_from_screen(&format!("earlier\n{APPROVAL}")).is_some());
    }

    #[test]
    fn a_reshown_prompt_gets_a_new_id() {
        let pane = "nonce-test-pane";
        let card = || parse_prompt_from_screen(APPROVAL);
        let first = shown(pane, card()).unwrap().prompt_id;
        assert_eq!(
            shown(pane, card()).unwrap().prompt_id,
            first,
            "stable while shown"
        );
        assert!(shown(pane, None).is_none());
        let again = shown(pane, card()).unwrap().prompt_id;
        assert_ne!(again, first, "the same command asked again is a new prompt");
        assert!(!claim(pane, &first), "an earlier showing's id is stale");
        assert!(claim(pane, &again));
        assert!(!claim(pane, &again), "only one of two racing answers types");
        assert!(
            shown(pane, card()).is_none(),
            "an answered card stays hidden while the screen catches up"
        );
        assert!(shown(pane, None).is_none());
        let third = shown(pane, card()).unwrap().prompt_id;
        assert_ne!(third, again, "asked again after it left: a new prompt");
        assert!(claim(pane, &third));
        forget(pane);
        let retry = shown(pane, card()).unwrap().prompt_id;
        assert_ne!(
            retry, third,
            "a refused answer leaves the card answerable under a new id"
        );
    }
}
