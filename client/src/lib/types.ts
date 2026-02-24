export type ChatMessage = {
  id: string;
  role: "assistant" | "user" | "system";
  text: string;
};
