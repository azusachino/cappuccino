import CappuccinoCore
import SwiftUI

/// The pending prompt, as a sheet over the conversation. It is shown while the stream has a
/// prompt; the bridge's resolution closes it. Content stays on opaque surfaces.
struct PromptSheet: View {
  let session: SessionModel
  let prompt: PromptCard

  var body: some View {
    let answersEnabled = session.capabilities?["prompt_answers"] != false
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 16) {
          if !prompt.questions.isEmpty {
            // "Question 1 of 2": the terminal form shows one question at a time.
            if let progress = prompt.message {
              Text(progress).font(.caption).foregroundStyle(.secondary)
            }
            // Each card is its own question; a fresh draft per card, never the last one's.
            QuestionForm(session: session, prompt: prompt, enabled: answersEnabled)
              .id(prompt.promptID)
          } else {
            ScreenPrompt(session: session, prompt: prompt, enabled: answersEnabled)
          }
          if !answersEnabled {
            Text("This bridge can't accept answers.").foregroundStyle(.secondary)
          }
        }
        .padding(16)
      }
      .navigationTitle(prompt.title)
      .inlineTitle()
    }
    .presentationDetents([.medium, .large])
    .presentationBackground(.background)
  }
}

/// A screen prompt: numbered options read off the terminal, and Cancel.
private struct ScreenPrompt: View {
  let session: SessionModel
  let prompt: PromptCard
  let enabled: Bool

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      if let message = prompt.message {
        Text(message)
      }
      if let command = prompt.command {
        Text(command)
          .font(.callout.monospaced())
          .padding(12)
          .frame(maxWidth: .infinity, alignment: .leading)
          .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
      }
      ForEach(Array(prompt.options.enumerated()), id: \.offset) { index, option in
        Button {
          Task {
            await session.answerPrompt(
              prompt.promptID, optionIndex: index, optionID: option.id, action: "select_option")
          }
        } label: {
          VStack(alignment: .leading, spacing: 2) {
            Text("\(index + 1). \(option.label)").font(.body.weight(.semibold))
            if let description = option.description {
              Text(description).font(.caption).foregroundStyle(.secondary)
            }
          }
          .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.bordered)
        .disabled(!enabled)
      }
      Button("Cancel", role: .cancel) {
        Task { await session.answerPrompt(prompt.promptID, action: "cancel") }
      }
      .disabled(!enabled)
    }
  }
}

/// AskUserQuestion: every question on one form, answered together, as the terminal form submits.
private struct QuestionForm: View {
  let session: SessionModel
  let prompt: PromptCard
  let enabled: Bool
  @State private var draft: QuestionDraft

  init(session: SessionModel, prompt: PromptCard, enabled: Bool) {
    self.session = session
    self.prompt = prompt
    self.enabled = enabled
    _draft = State(initialValue: QuestionDraft(questions: prompt.questions))
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      ForEach(Array(prompt.questions.enumerated()), id: \.offset) { index, question in
        VStack(alignment: .leading, spacing: 6) {
          if !question.header.isEmpty {
            Text(question.header).font(.caption.bold()).foregroundStyle(.tint)
          }
          Text(question.question).font(.subheadline.weight(.semibold))
          ForEach(Array(question.options.enumerated()), id: \.offset) { optionIndex, option in
            let checked = draft.selected[index].contains(optionIndex)
            Button {
              draft.toggle(question: index, option: optionIndex)
            } label: {
              HStack(alignment: .top, spacing: 8) {
                Image(systemName: symbol(question.multiSelect, checked))
                  .foregroundStyle(checked ? .primary : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                  Text(option.label)
                  if let description = option.description {
                    Text(description).font(.caption).foregroundStyle(.secondary)
                  }
                }
                Spacer(minLength: 0)
              }
              .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(checked ? .isSelected : [])
            .disabled(!enabled)
          }
          TextField(
            "Other",
            text: Binding(
              get: { draft.other[index] },
              set: { draft.setOther(question: index, $0) })
          )
          .textFieldStyle(.roundedBorder)
          .disabled(!enabled)
        }
      }
      HStack {
        Button("Cancel", role: .cancel) {
          Task { await session.answerPrompt(prompt.promptID, action: "cancel") }
        }
        .disabled(!enabled)
        Spacer()
        Button("Submit") {
          let answers = draft.answers
          Task { await session.answerQuestions(prompt.promptID, answers) }
        }
        .buttonStyle(.borderedProminent)
        .disabled(!enabled || !draft.isComplete)
      }
    }
  }

  private func symbol(_ multi: Bool, _ checked: Bool) -> String {
    switch (multi, checked) {
    case (true, true): "checkmark.square.fill"
    case (true, false): "square"
    case (false, true): "circle.inset.filled"
    case (false, false): "circle"
    }
  }
}
