export type ChatMessage = {
  id: string;
  role: "assistant" | "user" | "system";
  text: string;
  imageData?: string;  // base64, user messages only, not persisted to localStorage
  imageMime?: string;
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
