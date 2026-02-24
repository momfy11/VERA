const mockSuggestions = [
  {
    id: "1",
    title: "Focus block in 30 minutes",
    reason: "Based on your pattern of starting deep work before noon.",
  },
  {
    id: "2",
    title: "Draft reply to client email",
    reason: "Unread message from Project North is waiting.",
  },
];

export function SuggestionsPanel() {
  return (
    <section className="panel">
      <h2>Suggestions</h2>
      <p>Proactive ideas with clear reasons and next actions.</p>
      {mockSuggestions.map((item) => (
        <div className="suggestion" key={item.id}>
          <strong>{item.title}</strong>
          <span>{item.reason}</span>
          <div className="suggestion-actions">
            <button type="button">Accept</button>
            <button type="button">Snooze</button>
            <button type="button">Reject</button>
          </div>
        </div>
      ))}
    </section>
  );
}
