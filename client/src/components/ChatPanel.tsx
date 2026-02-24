import { useState } from "react";
import { ChatMessage } from "../lib/types";

type ChatPanelProps = {
  messages: ChatMessage[];
  onSend: (text: string) => void;
  disabled?: boolean;
};

export function ChatPanel({ messages, onSend, disabled = false }: ChatPanelProps) {
  const [input, setInput] = useState("");

  const handleSend = () => {
    if (!input.trim()) {
      return;
    }
    onSend(input.trim());
    setInput("");
  };

  return (
    <section className="panel">
      <h2>Conversation</h2>
      <p>Live transcript and responses will stream here.</p>
      <div className="chat-window">
        {messages.map((message) => (
          <div className="chat-bubble" key={message.id}>
            <strong>{message.role === "user" ? "You" : message.role === "assistant" ? "VERA" : "System"}</strong>
            <p>{message.text}</p>
          </div>
        ))}
      </div>
      <div className="chat-input">
        <input
          placeholder="Type a message or press the mic"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          disabled={disabled}
        />
        <button type="button" onClick={handleSend} disabled={disabled}>
          Send
        </button>
      </div>
    </section>
  );
}
