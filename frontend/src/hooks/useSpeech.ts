// Text-to-speech for the interviewer's replies, via the browser's built-in speechSynthesis
// (free and fully local). Speech-to-text is handled separately, on-device, by
// features/voice (see hooks/useLocalSpeech.ts).

export function speak(text: string, lang = 'en-US'): void {
  if (!('speechSynthesis' in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  u.lang = lang;
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(u);
}
