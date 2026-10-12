//! Claude Code's AskUserQuestion. The pending question comes from the transcript, which holds its
//! exact questions, options and descriptions; the screen only says the form is up. Answers become
//! the keys the terminal form takes (checked against Claude Code 2.1):
//! - single-select: the option's digit picks it and moves on; free text is the "Type something"
//!   digit, the text, then enter.
//! - multi-select: digits toggle without moving the cursor; free text is that row's digit, down to
//!   it, then the text. Tab moves to the next question; a lone question uses its Submit row.
//! - several questions, or a lone multi-select, end on a review whose `1` submits.

use crate::prompt::{PromptCard, PromptOption};
use serde::{Deserialize, Serialize};
use serde_json::Value;

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

/// The form's own footer: options plus "Chat about this", answered with enter.
pub fn on_screen(screen: &str) -> bool {
    screen.contains("Chat about this") && screen.contains("Enter to select")
}

/// Nothing answered yet: the first question shows, and no question or option is ticked.
pub fn at_start(screen: &str, questions: &[Question]) -> bool {
    on_screen(screen)
        && questions
            .first()
            .is_some_and(|first| screen.contains(first.question.as_str()))
        && !screen.contains('☒')
        && !screen.contains("[✔]")
        && !screen.contains("Review your answers")
}

/// The newest AskUserQuestion call in a Claude transcript that has no result yet.
pub fn pending(content: &str) -> Option<(String, Vec<Question>)> {
    let mut open: Vec<(String, Vec<Question>)> = Vec::new();
    for line in content.lines() {
        let Ok(entry) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        if entry["isSidechain"] == true {
            continue;
        }
        let Some(blocks) = entry["message"]["content"].as_array() else {
            continue;
        };
        for block in blocks {
            match block["type"].as_str() {
                Some("tool_use") if block["name"] == "AskUserQuestion" => {
                    if let (Some(id), Some(questions)) =
                        (block["id"].as_str(), parse_questions(&block["input"]))
                    {
                        open.push((id.to_string(), questions));
                    }
                }
                Some("tool_result") => {
                    if let Some(id) = block["tool_use_id"].as_str() {
                        open.retain(|(open_id, _)| open_id != id);
                    }
                }
                _ => {}
            }
        }
    }
    open.pop()
}

fn parse_questions(input: &Value) -> Option<Vec<Question>> {
    let questions = input["questions"]
        .as_array()?
        .iter()
        .map(|question| {
            let options = question["options"]
                .as_array()?
                .iter()
                .enumerate()
                .map(|(index, option)| {
                    Some(PromptOption {
                        id: format!("opt-{index}"),
                        label: option["label"].as_str()?.to_string(),
                        description: option["description"].as_str().map(str::to_string),
                    })
                })
                .collect::<Option<Vec<_>>>()?;
            Some(Question {
                header: question["header"].as_str().unwrap_or_default().to_string(),
                question: question["question"].as_str()?.to_string(),
                multi_select: question["multiSelect"].as_bool().unwrap_or(false),
                options,
            })
        })
        .collect::<Option<Vec<_>>>()?;
    (!questions.is_empty()).then_some(questions)
}

/// The phone's card for a pending call; its ID names that exact call.
pub fn card(tool_use_id: &str, questions: Vec<Question>) -> PromptCard {
    PromptCard {
        prompt_id: format!("q-{tool_use_id}"),
        card_type: "ask_user_question".to_string(),
        title: questions[0].question.clone(),
        message: None,
        tool_name: Some("AskUserQuestion".to_string()),
        command: None,
        options: Vec::new(),
        selected_index: 0,
        questions,
    }
}

fn digit(index: usize) -> String {
    (index + 1).to_string()
}

/// The keys and text that answer every question, or why the answers don't fit them.
pub fn answer_steps(questions: &[Question], answers: &[Answer]) -> Result<Vec<Step>, String> {
    if answers.len() != questions.len() {
        return Err(format!(
            "expected {} answers, got {}",
            questions.len(),
            answers.len()
        ));
    }
    let keys = |keys: &[&str]| Step::Keys(keys.iter().map(|key| key.to_string()).collect());
    let mut steps = Vec::new();
    let lone = questions.len() == 1;
    for (question, answer) in questions.iter().zip(answers) {
        let count = question.options.len();
        let other = match answer.other.as_deref().map(str::trim) {
            Some(text) if text.chars().any(char::is_control) => {
                return Err(format!("\"{}\": text must be one line", question.header))
            }
            Some(text) if !text.is_empty() => Some(text.to_string()),
            _ => None,
        };
        let mut selected = answer.selected.clone();
        selected.sort_unstable();
        selected.dedup();
        if selected.iter().any(|&index| index >= count) {
            return Err(format!("\"{}\": no such option", question.header));
        }
        if !question.multi_select {
            match (selected.as_slice(), other) {
                ([index], None) => steps.push(Step::Keys(vec![digit(*index)])),
                ([], Some(text)) => {
                    steps.push(Step::Keys(vec![digit(count)]));
                    steps.push(Step::Text(text));
                    steps.push(keys(&["enter"]));
                }
                _ => {
                    return Err(format!(
                        "\"{}\": choose one option or write one answer",
                        question.header
                    ))
                }
            }
            continue;
        }
        if selected.is_empty() && other.is_none() {
            return Err(format!("\"{}\": choose at least one", question.header));
        }
        for index in selected {
            steps.push(Step::Keys(vec![digit(index)]));
        }
        // Rows: options 1..=count, "Type something" count+1, Submit count+2; the cursor starts on 1.
        let mut row = 1;
        if let Some(text) = other {
            steps.push(Step::Keys(vec![digit(count)]));
            steps.push(Step::Keys(vec!["down".to_string(); count]));
            steps.push(Step::Text(text));
            row = count + 1;
        }
        if lone {
            steps.push(Step::Keys(vec!["down".to_string(); count + 2 - row]));
            steps.push(keys(&["enter"]));
        } else {
            steps.push(keys(&["tab"]));
        }
    }
    if !lone || questions[0].multi_select {
        steps.push(keys(&["1"]));
    }
    Ok(steps)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn question(header: &str, multi: bool, labels: &[&str]) -> Question {
        Question {
            header: header.to_string(),
            question: format!("{header}?"),
            multi_select: multi,
            options: labels
                .iter()
                .enumerate()
                .map(|(index, label)| PromptOption {
                    id: format!("opt-{index}"),
                    label: label.to_string(),
                    description: None,
                })
                .collect(),
        }
    }

    fn answer(selected: &[usize], other: Option<&str>) -> Answer {
        Answer {
            selected: selected.to_vec(),
            other: other.map(str::to_string),
        }
    }

    fn keys(keys: &[&str]) -> Step {
        Step::Keys(keys.iter().map(|key| key.to_string()).collect())
    }

    #[test]
    fn pending_is_the_newest_call_without_a_result() {
        let jsonl = r#"{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"AskUserQuestion","input":{"questions":[{"header":"Old","question":"Old?","multiSelect":false,"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"answered"}]}}
{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"tool_use","id":"side","name":"AskUserQuestion","input":{"questions":[{"question":"Side?","options":[{"label":"S"}]}]}}]}}
{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"AskUserQuestion","input":{"questions":[{"header":"Color","question":"Pick a color?","multiSelect":true,"options":[{"label":"Red","description":"warm"},{"label":"Blue","description":"cool"}]}]}}]}}"#;
        let (id, questions) = pending(jsonl).unwrap();
        assert_eq!(id, "t2");
        assert_eq!(questions[0].question, "Pick a color?");
        assert!(questions[0].multi_select);
        assert_eq!(questions[0].options[1].description.as_deref(), Some("cool"));
        let answered = format!(
            "{jsonl}\n{}",
            r#"{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"x"}]}}"#
        );
        assert_eq!(pending(&answered), None);
    }

    // Each sequence below was replayed against Claude Code and produced the recorded answers.
    #[test]
    fn two_questions_single_then_multi() {
        let questions = [
            question("Color", false, &["Red", "Blue", "Green"]),
            question("Pets", true, &["Cat", "Dog", "Fish"]),
        ];
        let steps = answer_steps(&questions, &[answer(&[1], None), answer(&[2, 0], None)]);
        // {"Pick a color?":"Blue","Which pets?":"Cat, Fish"}
        assert_eq!(
            steps.unwrap(),
            vec![
                keys(&["2"]),
                keys(&["1"]),
                keys(&["3"]),
                keys(&["tab"]),
                keys(&["1"])
            ]
        );
    }

    #[test]
    fn lone_single_select_submits_on_its_digit_or_typed_text() {
        let questions = [question("Name", false, &["Ann", "Bob"])];
        assert_eq!(
            answer_steps(&questions, &[answer(&[1], None)]).unwrap(),
            vec![keys(&["2"])]
        );
        // {"Your name?":"Zed"}
        assert_eq!(
            answer_steps(&questions, &[answer(&[], Some(" Zed "))]).unwrap(),
            vec![keys(&["3"]), Step::Text("Zed".into()), keys(&["enter"])]
        );
    }

    #[test]
    fn lone_multi_select_with_text_uses_its_submit_row_and_review() {
        let questions = [question("Tags", true, &["A", "B"])];
        // {"Tags?":"A, zz"}
        assert_eq!(
            answer_steps(&questions, &[answer(&[0], Some("zz"))]).unwrap(),
            vec![
                keys(&["1"]),
                keys(&["3"]),
                keys(&["down", "down"]),
                Step::Text("zz".into()),
                keys(&["down"]),
                keys(&["enter"]),
                keys(&["1"]),
            ]
        );
        assert_eq!(
            answer_steps(&questions, &[answer(&[1], None)]).unwrap(),
            vec![
                keys(&["2"]),
                keys(&["down", "down", "down"]),
                keys(&["enter"]),
                keys(&["1"])
            ]
        );
    }

    #[test]
    fn typed_text_on_a_single_select_moves_to_the_next_question() {
        let questions = [
            question("A", false, &["P", "Q"]),
            question("B", false, &["X", "Y"]),
        ];
        // {"First?":"Teal","Second?":"X"}
        assert_eq!(
            answer_steps(&questions, &[answer(&[], Some("Teal")), answer(&[0], None)]).unwrap(),
            vec![
                keys(&["3"]),
                Step::Text("Teal".into()),
                keys(&["enter"]),
                keys(&["1"]),
                keys(&["1"]),
            ]
        );
    }

    #[test]
    fn answers_that_do_not_fit_are_refused() {
        let single = [question("One", false, &["A", "B"])];
        let multi = [question("Many", true, &["A", "B"])];
        assert!(answer_steps(&single, &[]).is_err());
        assert!(answer_steps(&single, &[answer(&[0, 1], None)]).is_err());
        assert!(answer_steps(&single, &[answer(&[0], Some("x"))]).is_err());
        assert!(answer_steps(&single, &[answer(&[5], None)]).is_err());
        assert!(answer_steps(&single, &[answer(&[], Some("two\nlines"))]).is_err());
        assert!(answer_steps(&multi, &[answer(&[], Some("   "))]).is_err());
    }

    #[test]
    fn screen_checks_follow_the_form() {
        let questions = [question("Color", false, &["Red", "Blue"])];
        let start = "←  ☐ Color  ☐ Pets  ✔ Submit  →\nColor?\n❯ 1. Red\n  2. Blue\n  3. Type something.\n  4. Chat about this\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel";
        assert!(at_start(start, &questions));
        assert!(!at_start(&start.replace("☐ Color", "☒ Color"), &questions));
        assert!(!at_start(
            "Color?\n1. Red\n2. Blue\nEnter to select",
            &questions
        ));
        let other = [question("Size", false, &["S", "M"])];
        assert!(!at_start(start, &other));
    }
}
