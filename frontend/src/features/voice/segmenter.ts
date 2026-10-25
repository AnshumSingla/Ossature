// Cuts a continuous 16 kHz mono mic stream into utterances, so each spoken phrase can be
// transcribed when the speaker pauses (the speech models used here transcribe whole
// clips, they are not word-by-word streaming recognisers). Pure logic with no browser APIs, so it is unit-tested
// in Node (segmenter.test.ts).
//
// Energy-based voice-activity detection with an adaptive noise floor:
//  - speech starts after START_FRAMES consecutive loud frames (so a click doesn't count);
//  - a short pre-roll is kept so the first syllable isn't clipped;
//  - it ends after END_SILENCE_MS of quiet, or is force-cut at MAX_UTTERANCE_MS so a long
//    monologue is transcribed in pieces instead of one huge clip at the end.

export const SAMPLE_RATE = 16_000;

export interface SegmenterOptions {
  /** Samples per analysis frame (default 100 ms). */
  frameSize?: number;
  /** Quiet this long ends an utterance. */
  endSilenceMs?: number;
  /** Utterances shorter than this (of actual speech) are discarded as noise. */
  minSpeechMs?: number;
  /** Force-cut a continuous utterance at this length. */
  maxUtteranceMs?: number;
  /** Audio kept from before speech was detected. */
  preRollMs?: number;
  /** Absolute RMS floor below which nothing counts as speech. */
  minThreshold?: number;
}

export type SegmenterEvent =
  | { type: 'speech-start' }
  | { type: 'utterance'; audio: Float32Array };

const START_FRAMES = 2;

export class UtteranceSegmenter {
  private readonly frameSize: number;
  private readonly frameMs: number;
  private readonly endSilenceFrames: number;
  private readonly minSpeechFrames: number;
  private readonly maxFrames: number;
  private readonly preRollFrames: number;
  private readonly minThreshold: number;

  private pending = new Float32Array(0); // samples not yet forming a whole frame
  private noiseFloor = 0.004;
  private preRoll: Float32Array[] = [];
  private loudRun = 0;
  private inSpeech = false;
  private frames: Float32Array[] = [];
  private speechFrames = 0;
  private silentRun = 0;

  constructor(opts: SegmenterOptions = {}) {
    this.frameSize = opts.frameSize ?? SAMPLE_RATE / 10;
    this.frameMs = (this.frameSize / SAMPLE_RATE) * 1000;
    this.endSilenceFrames = Math.ceil((opts.endSilenceMs ?? 700) / this.frameMs);
    this.minSpeechFrames = Math.ceil((opts.minSpeechMs ?? 300) / this.frameMs);
    this.maxFrames = Math.ceil((opts.maxUtteranceMs ?? 20_000) / this.frameMs);
    this.preRollFrames = Math.ceil((opts.preRollMs ?? 300) / this.frameMs);
    this.minThreshold = opts.minThreshold ?? 0.012;
  }

  /** Feed any amount of 16 kHz mono samples; returns whatever events they complete. */
  push(samples: Float32Array): SegmenterEvent[] {
    const events: SegmenterEvent[] = [];
    const all = new Float32Array(this.pending.length + samples.length);
    all.set(this.pending);
    all.set(samples, this.pending.length);

    let offset = 0;
    while (all.length - offset >= this.frameSize) {
      this.onFrame(all.subarray(offset, offset + this.frameSize).slice(), events);
      offset += this.frameSize;
    }
    this.pending = all.slice(offset);
    return events;
  }

  /** End of stream (mic stopped): emit what is in flight, if it is long enough. */
  flush(): SegmenterEvent[] {
    const events: SegmenterEvent[] = [];
    if (this.pending.length > 0 && this.inSpeech) this.frames.push(this.pending);
    this.pending = new Float32Array(0);
    if (this.inSpeech) this.finish(events, false);
    this.reset();
    return events;
  }

  private threshold(): number {
    return Math.max(this.minThreshold, this.noiseFloor * 3);
  }

  private onFrame(frame: Float32Array, events: SegmenterEvent[]): void {
    const rms = rmsOf(frame);

    if (!this.inSpeech) {
      if (rms > this.threshold()) {
        this.loudRun += 1;
        this.preRoll.push(frame);
        if (this.loudRun >= START_FRAMES) {
          this.inSpeech = true;
          this.frames = [...this.preRoll];
          this.speechFrames = this.loudRun;
          this.silentRun = 0;
          this.preRoll = [];
          events.push({ type: 'speech-start' });
        }
      } else {
        // Quiet: track the background level slowly, and keep a rolling pre-roll.
        this.noiseFloor = 0.95 * this.noiseFloor + 0.05 * rms;
        this.loudRun = 0;
        this.preRoll.push(frame);
        if (this.preRoll.length > this.preRollFrames) this.preRoll.shift();
      }
      return;
    }

    this.frames.push(frame);
    // Hysteresis: once speaking, a frame only counts as quiet if it is clearly below the
    // start threshold, so normal dips between words don't end the utterance early.
    if (rms > this.threshold() * 0.7) {
      this.speechFrames += 1;
      this.silentRun = 0;
    } else {
      this.silentRun += 1;
    }

    if (this.silentRun >= this.endSilenceFrames) {
      this.finish(events, true);
    } else if (this.frames.length >= this.maxFrames) {
      this.finish(events, false);
    }
  }

  private finish(events: SegmenterEvent[], trimTrailingSilence: boolean): void {
    let frames = this.frames;
    if (trimTrailingSilence) {
      // Keep ~200 ms of the trailing quiet so the last word isn't cut off.
      const keep = Math.ceil(200 / this.frameMs);
      frames = frames.slice(0, Math.max(0, frames.length - this.silentRun + keep));
    }
    if (this.speechFrames >= this.minSpeechFrames) {
      events.push({ type: 'utterance', audio: concat(frames) });
    }
    this.reset();
  }

  private reset(): void {
    this.inSpeech = false;
    this.frames = [];
    this.speechFrames = 0;
    this.silentRun = 0;
    this.loudRun = 0;
    this.preRoll = [];
  }
}

export function rmsOf(frame: Float32Array): number {
  let sum = 0;
  for (let i = 0; i < frame.length; i += 1) sum += frame[i] * frame[i];
  return Math.sqrt(sum / frame.length);
}

function concat(parts: Float32Array[]): Float32Array {
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}
