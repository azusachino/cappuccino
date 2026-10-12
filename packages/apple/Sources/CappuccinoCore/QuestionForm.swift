import Foundation

/// Ports answerComplete from ui/QuestionForm.kt. Submit is enabled only for an answer the
/// bridge can accept: a multi-select needs one choice or text; a single-select needs exactly
/// one of a choice or text.
public func answerComplete(_ question: PromptQuestion, _ answer: QuestionAnswer) -> Bool {
  let hasText = !(answer.other?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
  if question.multiSelect {
    return !answer.selected.isEmpty || hasText
  }
  return (answer.selected.count == 1) != hasText
}
