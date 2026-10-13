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

/// The answers being written on an AskUserQuestion card, all questions at once as the
/// terminal form submits them. Rules follow ui/QuestionForm.kt.
public struct QuestionDraft: Equatable, Sendable {
  public let questions: [PromptQuestion]
  public private(set) var selected: [[Int]]
  public private(set) var other: [String]

  public init(questions: [PromptQuestion]) {
    self.questions = questions
    selected = Array(repeating: [], count: questions.count)
    other = Array(repeating: "", count: questions.count)
  }

  /// A checkbox toggles; a radio takes the choice and drops any typed text.
  public mutating func toggle(question index: Int, option: Int) {
    guard questions.indices.contains(index) else { return }
    if questions[index].multiSelect {
      if let position = selected[index].firstIndex(of: option) {
        selected[index].remove(at: position)
      } else {
        selected[index].append(option)
      }
    } else {
      selected[index] = [option]
      other[index] = ""
    }
  }

  /// One line of own text. A radio's text replaces its choice.
  public mutating func setOther(question index: Int, _ text: String) {
    guard questions.indices.contains(index) else { return }
    other[index] = text.replacingOccurrences(of: "\n", with: " ")
    if !questions[index].multiSelect && !text.trimmingCharacters(in: .whitespaces).isEmpty {
      selected[index] = []
    }
  }

  public var answers: [QuestionAnswer] {
    questions.indices.map { index in
      let text = other[index].trimmingCharacters(in: .whitespacesAndNewlines)
      return QuestionAnswer(selected: selected[index], other: text.isEmpty ? nil : text)
    }
  }

  /// Submit is enabled only when every question is answerable.
  public var isComplete: Bool {
    zip(questions, answers).allSatisfy { answerComplete($0, $1) }
  }
}
