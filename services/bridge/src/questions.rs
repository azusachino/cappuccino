//! Claude Code's AskUserQuestion form, read off the screen one question at a time. Claude Code
//! writes the tool call to its transcript only once it is answered, so the visible form is the
//! only source while it waits. Answers become the keys the form takes (checked against Claude
//! Code 2.1 in a Herdr pane):
//! - single-select: the option's digit picks it and moves on; free text is the "Type something"
//!   digit, the text, then enter.
//! - multi-select: digits toggle without moving the cursor; free text is that row's digit, down to
//!   it, then the text. Its Submit row moves on (tab does not leave the text row).
//! - several questions, or a lone multi-select, end on a review whose `1` submits and `2` cancels.

use crate::prompt::{compute_prompt_id, PromptCard, PromptOption};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Question {
    pub header: String,
    pub question: String,
    pub multi_select: bool,
    pub options: Vec<PromptOption>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct Answer {
    #[serde(default)]
    pub selected: Vec<usize>,
    #[serde(default)]
    pub other: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Step {
    Keys(Vec<String>),
    Text(String),
}

/// The question on screen and what the keys depend on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Form {
    pub question: Question,
    /// 1-based row under the `❯` cursor.
    pub cursor: usize,
    /// Something is already ticked here; relative keys would undo it.
    pub ticked: bool,
    /// Headers as `(header, answered)`, for the card's progress line.
    pub tabs: Vec<(String, bool)>,
}

pub const QUESTION: &str = "ask_user_question";
pub const REVIEW: &str = "ask_user_question_review";

/// The form's own footer: options plus "Chat about this", answered with enter.
pub fn on_screen(screen: &str) -> bool {
    screen.contains("Chat about this") && screen.contains("Enter to select")
}

fn option_row(line: &str) -> Option<(usize, Option<bool>, String)> {
    let text = line.trim_start();
    let text = text.strip_prefix('❯').unwrap_or(text).trim_start();
    let (number, rest) = text.split_once(". ")?;
    let number: usize = number.parse().ok()?;
    let (ticked, label) = if let Some(label) = rest.strip_prefix("[ ] ") {
        (Some(false), label)
    } else if let Some(label) = rest.strip_prefix("[✔] ") {
        (Some(true), label)
    } else {
        (None, rest)
    };
    Some((number, ticked, label.trim().to_string()))
}

/// A multi-select's Submit row, with or without the cursor on it.
fn is_submit_row(text: &str) -> bool {
    text.trim_start_matches('❯').trim() == "Submit"
}

fn indent(line: &str) -> usize {
    line.len() - line.trim_start().len()
}

fn tabs(line: &str) -> Option<Vec<(String, bool)>> {
    if !line.contains('☐') && !line.contains('☒') {
        return None;
    }
    let mut tabs = Vec::new();
    for (index, mark) in line.char_indices() {
        if mark == '☐' || mark == '☒' {
            let rest = line[index + mark.len_utf8()..].trim_start();
            let header = rest.split("  ").next().unwrap_or("").trim().to_string();
            tabs.push((header, mark == '☒'));
        }
    }
    Some(tabs)
}

/// The open question, if the screen shows the form.
pub fn form(screen: &str) -> Option<Form> {
    let lines: Vec<&str> = screen.lines().map(str::trim_end).collect();
    let footer = lines
        .iter()
        .rposition(|line| line.contains("Enter to select"))?;
    let chat = lines[..footer]
        .iter()
        .rposition(|line| line.contains("Chat about this"))?;
    // Walk up over option rows, their descriptions, the Submit row and rules to the question.
    let mut top = chat;
    while top > 0 {
        let line = lines[top - 1];
        let text = line.trim();
        let part = text.is_empty()
            || text.starts_with('─')
            || is_submit_row(text)
            || option_row(line).is_some()
            || (!text.is_empty() && indent(line) >= 4);
        if !part {
            break;
        }
        top -= 1;
    }
    let mut rows: Vec<(Option<bool>, String, Option<String>, bool)> = Vec::new();
    let mut submit_cursor = false;
    for line in &lines[top..chat] {
        if let Some((_, ticked, label)) = option_row(line) {
            let cursor = line.trim_start().starts_with('❯');
            rows.push((ticked, label, None, cursor));
        } else if is_submit_row(line.trim()) {
            submit_cursor = line.trim_start().starts_with('❯');
        } else if indent(line) >= 4 {
            if let Some(row) = rows.last_mut() {
                let text = line.trim().to_string();
                row.2 = Some(match row.2.take() {
                    Some(previous) => format!("{previous} {text}"),
                    None => text,
                });
            }
        }
    }
    // The last row is "Type something": the place for own text, not an option.
    if rows.len() < 2 {
        return None;
    }
    // Above the options: the question's lines, then the tab row; blank lines may sit between.
    let mut above = lines[..top].iter().rev().map(|line| line.trim()).peekable();
    while above.next_if(|text| text.is_empty()).is_some() {}
    let mut question = Vec::new();
    while let Some(text) =
        above.next_if(|text| !text.is_empty() && !text.starts_with('─') && tabs(text).is_none())
    {
        question.push(text);
    }
    while above.next_if(|text| text.is_empty()).is_some() {}
    let tab_row = above.next().and_then(tabs);
    question.reverse();
    if question.is_empty() {
        return None;
    }
    let tabs = tab_row.unwrap_or_default();
    let multi_select = rows.iter().any(|row| row.0.is_some());
    let ticked = rows.iter().any(|row| row.0 == Some(true));
    // Rows count from 1; the Submit row sits after the "Type something" row.
    let cursor = if submit_cursor {
        rows.len() + 1
    } else {
        rows.iter()
            .position(|row| row.3)
            .map_or(1, |index| index + 1)
    };
    rows.pop();
    let header = tabs
        .iter()
        .find(|(_, answered)| !answered)
        .or(tabs.last())
        .map(|(header, _)| header.clone())
        .unwrap_or_default();
    Some(Form {
        question: Question {
            header,
            question: question.join(" "),
            multi_select,
            options: rows
                .into_iter()
                .enumerate()
                .map(|(index, (_, label, description, _))| PromptOption {
                    id: format!("opt-{index}"),
                    label,
                    description,
                })
                .collect(),
        },
        cursor,
        ticked,
        tabs,
    })
}

/// The phone's card for the question on screen; its ID changes as the form moves on.
pub fn card(form: &Form) -> PromptCard {
    let question = &form.question;
    let tabs: String = form
        .tabs
        .iter()
        .map(|(header, answered)| format!("{header}{}", if *answered { "+" } else { "-" }))
        .collect();
    let labels: Vec<PromptOption> = question.options.clone();
    let prompt_id = compute_prompt_id(&question.question, Some(&tabs), &labels);
    let answered = form.tabs.iter().filter(|(_, answered)| *answered).count();
    let progress = (form.tabs.len() > 1).then(|| {
        format!(
            "Question {} of {}",
            (answered + 1).min(form.tabs.len()),
            form.tabs.len()
        )
    });
    PromptCard {
        prompt_id,
        card_type: QUESTION.to_string(),
        title: question.question.clone(),
        message: progress,
        tool_name: Some("AskUserQuestion".to_string()),
        command: None,
        options: Vec::new(),
        selected_index: 0,
        questions: vec![question.clone()],
    }
}

/// The form's last step: the chosen answers and "Submit answers / Cancel".
pub fn review(screen: &str) -> Option<PromptCard> {
    let lines: Vec<&str> = screen.lines().map(str::trim).collect();
    let start = lines
        .iter()
        .rposition(|line| *line == "Review your answers")?;
    let end = start
        + lines[start..]
            .iter()
            .position(|line| *line == "Ready to submit your answers?")?;
    let submit = lines[end..]
        .iter()
        .any(|line| option_row(line).is_some_and(|row| row.2 == "Submit answers"));
    let cancel = lines[end..]
        .iter()
        .any(|line| option_row(line).is_some_and(|row| row.2 == "Cancel"));
    if !submit || !cancel {
        return None;
    }
    let summary = lines[start + 1..end]
        .iter()
        .filter(|line| !line.is_empty())
        .map(|line| line.trim_start_matches('●').trim())
        .collect::<Vec<_>>()
        .join("\n");
    let options = vec![
        PromptOption {
            id: "submit".to_string(),
            label: "Submit answers".to_string(),
            description: None,
        },
        PromptOption {
            id: "cancel".to_string(),
            label: "Cancel".to_string(),
            description: None,
        },
    ];
    Some(PromptCard {
        prompt_id: compute_prompt_id("Review your answers", Some(&summary), &options),
        card_type: REVIEW.to_string(),
        title: "Submit your answers?".to_string(),
        message: Some(summary),
        tool_name: Some("AskUserQuestion".to_string()),
        command: None,
        options,
        selected_index: 0,
        questions: Vec::new(),
    })
}

fn digit(index: usize) -> String {
    (index + 1).to_string()
}

/// The keys and text that answer the question on screen, or why the answer doesn't fit it.
pub fn answer_steps(form: &Form, answer: &Answer) -> Result<Vec<Step>, String> {
    let question = &form.question;
    let count = question.options.len();
    let other = match answer.other.as_deref().map(str::trim) {
        Some(text) if text.chars().any(char::is_control) => {
            return Err("own text must be one line".to_string())
        }
        Some(text) if !text.is_empty() => Some(text.to_string()),
        _ => None,
    };
    let mut selected = answer.selected.clone();
    selected.sort_unstable();
    selected.dedup();
    if selected.iter().any(|&index| index >= count) {
        return Err("no such option".to_string());
    }
    let keys = |keys: &[&str]| Step::Keys(keys.iter().map(|key| key.to_string()).collect());
    let mut steps = Vec::new();
    if !question.multi_select {
        match (selected.as_slice(), other) {
            ([index], None) => steps.push(Step::Keys(vec![digit(*index)])),
            ([], Some(text)) => {
                steps.push(Step::Keys(vec![digit(count)]));
                steps.push(Step::Text(text));
                steps.push(keys(&["enter"]));
            }
            _ => return Err("choose one option or write one answer".to_string()),
        }
        return Ok(steps);
    }
    if selected.is_empty() && other.is_none() {
        return Err("choose at least one".to_string());
    }
    for index in selected {
        steps.push(Step::Keys(vec![digit(index)]));
    }
    // Rows: options 1..=count, "Type something" count+1, Submit count+2.
    let mut row = form.cursor;
    if let Some(text) = other {
        steps.push(Step::Keys(vec![digit(count)]));
        let target = count + 1;
        let moves = if target >= row {
            vec!["down".to_string(); target - row]
        } else {
            vec!["up".to_string(); row - target]
        };
        if !moves.is_empty() {
            steps.push(Step::Keys(moves));
        }
        steps.push(Step::Text(text));
        row = target;
    }
    steps.push(Step::Keys(vec!["down".to_string(); count + 2 - row]));
    steps.push(keys(&["enter"]));
    Ok(steps)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Screens as Herdr reads them from a Claude Code 2.1 pane.
    const COLOR: &str = "──────────
←  ☐ Color  ☐ Pets  ✔ Submit  →

Pick a color?

❯ 1. Red
     warm
  2. Blue
     cool
  3. Green
     calm
  4. Type something.
──────────
  5. Chat about this

Enter to select · Tab/Arrow keys to navigate · Esc to cancel";

    const PETS: &str = "──────────
←  ☒ Color  ☐ Pets  ✔ Submit  →
Which pets?
❯ 1. [ ] Cat
         indep
  2. [ ] Dog
         loyal
  3. [ ] Fish
         quiet
  4. [ ] Type something
     Submit
──────────
  5. Chat about this
Enter to select · Tab/Arrow keys to navigate · Esc to cancel";

    const TAGS: &str = " ☐ Tags
Tags?
❯ 1. [ ] A
         a
  2. [ ] B
         b
  3. [ ] Type something
     Submit
──────────
  4. Chat about this
Enter to select · ↑/↓ to navigate · Esc to cancel";

    const REVIEW_SCREEN: &str = "←  ☒ Color  ☒ Pets  ✔ Submit  →
Review your answers
 ● Pick a color?
   → Blue
 ● Which pets?
   → Cat, Fish
Ready to submit your answers?
❯ 1. Submit answers
  2. Cancel";

    fn keys(keys: &[&str]) -> Step {
        Step::Keys(keys.iter().map(|key| key.to_string()).collect())
    }

    fn answer(selected: &[usize], other: Option<&str>) -> Answer {
        Answer {
            selected: selected.to_vec(),
            other: other.map(str::to_string),
        }
    }

    #[test]
    fn reads_the_question_options_and_descriptions_on_screen() {
        let form = form(COLOR).unwrap();
        assert_eq!(form.question.question, "Pick a color?");
        assert_eq!(form.question.header, "Color");
        assert!(!form.question.multi_select && !form.ticked);
        let labels: Vec<_> = form
            .question
            .options
            .iter()
            .map(|o| o.label.as_str())
            .collect();
        assert_eq!(labels, ["Red", "Blue", "Green"]);
        assert_eq!(
            form.question.options[1].description.as_deref(),
            Some("cool")
        );
        let color = card(&form);
        assert_eq!(color.card_type, QUESTION);
        assert_eq!(color.message.as_deref(), Some("Question 1 of 2"));

        let pets = form_of(PETS);
        assert!(pets.question.multi_select);
        assert_eq!(pets.question.header, "Pets");
        assert_eq!(card(&pets).message.as_deref(), Some("Question 2 of 2"));
        assert_ne!(card(&pets).prompt_id, card(&form).prompt_id);
    }

    fn form_of(screen: &str) -> Form {
        form(screen).unwrap()
    }

    #[test]
    fn reads_the_form_as_herdr_returns_it() {
        // Unwrapped scrollback: blank lines around the question, long rules, trailing blank rows.
        let form = form(include_str!(
            "../../../fixtures/claude/ask-user-question-screen.txt"
        ))
        .unwrap();
        assert_eq!(form.question.question, "Pick a color?");
        assert_eq!(form.question.options.len(), 3);
        assert_eq!(
            form.question.options[2].description.as_deref(),
            Some("calm")
        );
    }

    #[test]
    fn other_screens_are_not_the_form() {
        assert_eq!(form("Running cargo test...\nok"), None);
        assert_eq!(form(REVIEW_SCREEN), None);
        assert_eq!(review(COLOR), None);
    }

    #[test]
    fn the_review_is_its_own_submit_or_cancel_card() {
        // The form can linger in scrollback above its review.
        let both = format!("{PETS}\n{REVIEW_SCREEN}");
        assert_eq!(
            crate::prompt::screen_card(&both).map(|card| card.card_type),
            Some(REVIEW.to_string())
        );
        let card = review(REVIEW_SCREEN).unwrap();
        assert_eq!(card.card_type, REVIEW);
        assert_eq!(
            card.message.as_deref(),
            Some("Pick a color?\n→ Blue\nWhich pets?\n→ Cat, Fish")
        );
        assert_eq!(card.options.len(), 2);
    }

    // Each sequence was replayed against Claude Code and produced the recorded answer.
    #[test]
    fn single_select_digit_or_typed_text() {
        let color = form_of(COLOR);
        // "Blue", then the form moves to Pets.
        assert_eq!(
            answer_steps(&color, &answer(&[1], None)).unwrap(),
            vec![keys(&["2"])]
        );
        // "Teal" as own text.
        assert_eq!(
            answer_steps(&color, &answer(&[], Some(" Teal "))).unwrap(),
            vec![keys(&["4"]), Step::Text("Teal".into()), keys(&["enter"])]
        );
    }

    #[test]
    fn multi_select_toggles_then_leaves_by_its_submit_row() {
        // "Cat, Fish, a bird", then the Submit row to the review.
        assert_eq!(
            answer_steps(&form_of(PETS), &answer(&[2, 0], Some("a bird"))).unwrap(),
            vec![
                keys(&["1"]),
                keys(&["3"]),
                keys(&["4"]),
                keys(&["down", "down", "down"]),
                Step::Text("a bird".into()),
                keys(&["down"]),
                keys(&["enter"]),
            ]
        );
        // "A, zz" through the lone form's Submit row.
        assert_eq!(
            answer_steps(&form_of(TAGS), &answer(&[0], Some("zz"))).unwrap(),
            vec![
                keys(&["1"]),
                keys(&["3"]),
                keys(&["down", "down"]),
                Step::Text("zz".into()),
                keys(&["down"]),
                keys(&["enter"]),
            ]
        );
        // The cursor is read from the screen, not assumed.
        let moved = TAGS
            .replace("❯ 1. [ ] A", "  1. [ ] A")
            .replace("  2. [ ] B", "❯ 2. [ ] B");
        assert_eq!(
            answer_steps(&form_of(&moved), &answer(&[1], None)).unwrap(),
            vec![keys(&["2"]), keys(&["down", "down"]), keys(&["enter"])]
        );
        assert!(form_of(&PETS.replace("1. [ ] Cat", "1. [✔] Cat")).ticked);
        // The cursor can rest on the Submit row; the card stays and keys start from there.
        let on_submit = TAGS
            .replace("❯ 1. [ ] A", "  1. [ ] A")
            .replace("     Submit", "❯    Submit");
        let form = form_of(&on_submit);
        assert_eq!(form.cursor, 4);
        assert_eq!(
            answer_steps(&form, &answer(&[0], Some("zz"))).unwrap(),
            vec![
                keys(&["1"]),
                keys(&["3"]),
                keys(&["up"]),
                Step::Text("zz".into()),
                keys(&["down"]),
                keys(&["enter"]),
            ]
        );
    }

    #[test]
    fn answers_that_do_not_fit_are_refused() {
        let color = form_of(COLOR);
        let pets = form_of(PETS);
        assert!(answer_steps(&color, &answer(&[0, 1], None)).is_err());
        assert!(answer_steps(&color, &answer(&[0], Some("x"))).is_err());
        assert!(answer_steps(&color, &answer(&[5], None)).is_err());
        assert!(answer_steps(&color, &answer(&[], Some("two\nlines"))).is_err());
        assert!(answer_steps(&pets, &answer(&[], Some("   "))).is_err());
    }
}
