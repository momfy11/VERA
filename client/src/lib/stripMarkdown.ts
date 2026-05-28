/**
 * Strip markdown syntax from a string so TTS reads it naturally.
 *
 * Handles: headings, bold/italic/strikethrough, inline code, code blocks,
 * links (keep text, drop URL), images (drop), blockquotes, list markers,
 * horizontal rules, and tables (rows become comma-separated sentences).
 *
 * Preserves emoji — most TTS voices skip them silently anyway.
 */
export function stripMarkdown(text: string): string {
  return text
    // Code blocks first — keep contents, drop fences and language hint
    .replace(/```[a-zA-Z0-9_+-]*\n?([\s\S]*?)```/g, (_m, inner: string) => ` ${inner.trim()} `)
    // Inline code
    .replace(/`([^`]+)`/g, "$1")
    // Images: drop entirely (alt-text-only narration is confusing)
    .replace(/!\[[^\]]*\]\([^)]*\)/g, "")
    // Links: keep visible text, drop the URL
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    // Bold + italic (** __ * _) — order matters: longest first
    .replace(/\*\*([^*\n]+)\*\*/g, "$1")
    .replace(/__([^_\n]+)__/g, "$1")
    .replace(/(?<![*\w])\*([^*\n]+?)\*(?!\w)/g, "$1")
    .replace(/(?<![_\w])_([^_\n]+?)_(?!\w)/g, "$1")
    // Strikethrough
    .replace(/~~([^~\n]+)~~/g, "$1")
    // Headings (## Foo → Foo)
    .replace(/^#{1,6}\s+/gm, "")
    // Blockquote markers
    .replace(/^>\s?/gm, "")
    // Horizontal rules: ---, ***, ___
    .replace(/^[-*_]{3,}\s*$/gm, "")
    // List bullets (-, *, +) and numbered items (1.)
    .replace(/^(\s*)[-*+]\s+/gm, "$1")
    .replace(/^(\s*)\d+\.\s+/gm, "$1")
    // Tables: convert each row's cells into "cell1, cell2, cell3"
    .replace(/^\|(.+)\|\s*$/gm, (_m, row: string) => {
      // Skip separator rows like |---|---|
      if (/^[\s\-:|]+$/.test(row)) return "";
      return row
        .split("|")
        .map((c) => c.trim())
        .filter(Boolean)
        .join(", ");
    })
    // Collapse 3+ consecutive newlines into 2
    .replace(/\n{3,}/g, "\n\n")
    // Convert remaining single newlines into spaces inside short paragraphs
    // (TTS pauses too long on newlines)
    .replace(/([^\n])\n([^\n])/g, "$1 $2")
    .trim();
}
