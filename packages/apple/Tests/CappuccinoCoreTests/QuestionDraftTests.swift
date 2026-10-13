import Testing

@testable import CappuccinoCore

/// Ports the QuestionForm rules: radio and checkbox behaviour, the Other line, and Submit enablement.
struct QuestionDraftTests {
  private let options = [
    PromptOption(id: "opt-0", label: "A", description: nil),
    PromptOption(id: "opt-1", label: "B", description: nil),
  ]

  @Test func radioTakesOneChoiceAndTypedTextReplacesIt() {
    var draft = QuestionDraft(questions: [
      PromptQuestion(header: "H", question: "Q?", multiSelect: false, options: options)
    ])
    #expect(!draft.isComplete)
    draft.toggle(question: 0, option: 0)
    draft.toggle(question: 0, option: 1)
    #expect(draft.selected == [[1]])
    #expect(draft.isComplete)
    draft.setOther(question: 0, "own answer")
    #expect(draft.selected == [[]])
    #expect(draft.answers == [QuestionAnswer(selected: [], other: "own answer")])
    #expect(draft.isComplete)
  }

  @Test func checkboxTogglesAndOtherLineIsOneLine() {
    var draft = QuestionDraft(questions: [
      PromptQuestion(header: "H", question: "Q?", multiSelect: true, options: options)
    ])
    draft.toggle(question: 0, option: 0)
    draft.toggle(question: 0, option: 1)
    draft.toggle(question: 0, option: 0)
    #expect(draft.selected == [[1]])
    draft.setOther(question: 0, "line one\nline two")
    #expect(draft.other == ["line one line two"])
    #expect(draft.selected == [[1]], "checkboxes keep their choices with typed text")
  }

  @Test func everyQuestionMustBeAnsweredBeforeSubmit() {
    var draft = QuestionDraft(questions: [
      PromptQuestion(header: "A", question: "1?", multiSelect: false, options: options),
      PromptQuestion(header: "B", question: "2?", multiSelect: true, options: options),
    ])
    draft.toggle(question: 0, option: 0)
    #expect(!draft.isComplete)
    draft.toggle(question: 1, option: 1)
    #expect(draft.isComplete)
    #expect(draft.answers.count == 2)
  }
}
