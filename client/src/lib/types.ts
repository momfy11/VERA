export type ChatMessage = {
  id: string;
  role: "assistant" | "user" | "system";
  text: string;
};

export type Suggestion = {
  id: string;
  type: string;
  priority: number;
  title: string;
  reason: string;
  ts: string;
  status: "new" | "accepted" | "rejected" | "snoozed";
};

export type SuggestionAction = "accepted" | "rejected" | "snoozed";
