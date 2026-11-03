use super::notice::Kind;
use super::notice::Notice;

/// The plain text sent for a notice. Never includes a transcript, a path or a
/// native reference, and is capped at 200 characters. The pane name is added only
/// when the owner opted in with `show_pane_names`.
pub fn message(machine: &str, notice: &Notice, show_names: bool) -> String {
    let verb = match notice.kind {
        Kind::Completion => "finished a work cycle",
        Kind::NeedsAnswer => "is waiting for an answer",
    };
    let agent = match notice.name.as_ref().filter(|_| show_names) {
        Some(name) => format!("{} #{} ({name})", notice.agent, notice.number),
        None => format!("{} #{}", notice.agent, notice.number),
    };
    cap(format!("[cappuccino] {machine} · {agent} {verb}"))
}

pub(super) fn route_failure_message(machine: &str, status: u16) -> String {
    cap(format!(
        "[cappuccino] {machine} · telegram route failing (HTTP {status})"
    ))
}

pub(super) fn cap(text: String) -> String {
    if text.chars().count() <= crate::tuning::active().telegram.max_text_chars {
        return text;
    }
    let mut capped: String = text
        .chars()
        .take(crate::tuning::active().telegram.max_text_chars - 1)
        .collect();
    capped.push('…');
    capped
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_are_plain_and_capped() {
        let notice = Notice {
            kind: Kind::Completion,
            agent: "pi".into(),
            number: 3,
            name: None,
        };
        assert_eq!(
            message("mini", &notice, false),
            "[cappuccino] mini · pi #3 finished a work cycle"
        );
        let named = Notice {
            kind: Kind::Completion,
            agent: "claude".into(),
            number: 2,
            name: Some("telegram".into()),
        };
        assert_eq!(
            message("MacMini", &named, true),
            "[cappuccino] MacMini · claude #2 (telegram) finished a work cycle"
        );
        assert_eq!(
            message("MacMini", &named, false),
            "[cappuccino] MacMini · claude #2 finished a work cycle",
            "the name stays out unless the owner opted in"
        );
        let long = Notice {
            kind: Kind::NeedsAnswer,
            agent: "a".repeat(400),
            number: 1,
            name: None,
        };
        let text = message("mini", &long, false);
        assert_eq!(
            text.chars().count(),
            crate::tuning::active().telegram.max_text_chars
        );
        assert!(text.ends_with('…'));
    }
}
