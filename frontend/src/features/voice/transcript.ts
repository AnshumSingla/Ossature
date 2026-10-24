/**
 * Drop empty output and the non-speech markers a model emits on near-silence. Whisper writes
 * things like "[BLANK_AUDIO]", "(silence)" or "♪ ♪"; none of that is something the candidate said.
 */
export function cleanTranscript(raw: string): string {
  const text = raw
    .replace(/\[[^\]]*\]|\([^)]*\)|[♪♫][^♪♫]*[♪♫]?/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!/[\p{L}\p{N}]/u.test(text)) return '';
  return text;
}
