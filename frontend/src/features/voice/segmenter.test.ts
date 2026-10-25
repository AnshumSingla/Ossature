// Run with: npm test   (Node's built-in test runner; no extra dependencies)
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { SAMPLE_RATE, UtteranceSegmenter, type SegmenterEvent } from './segmenter.ts';

const secs = (s: number) => Math.round(s * SAMPLE_RATE);

/** A 220 Hz tone with a speech-like amplitude. */
function tone(seconds: number, amp = 0.2): Float32Array {
  const out = new Float32Array(secs(seconds));
  for (let i = 0; i < out.length; i += 1) out[i] = amp * Math.sin((2 * Math.PI * 220 * i) / SAMPLE_RATE);
  return out;
}

function noise(seconds: number, amp: number): Float32Array {
  let seed = 12345;
  const rand = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 0xffffffff) * 2 - 1;
  return Float32Array.from({ length: secs(seconds) }, () => amp * rand());
}

function silence(seconds: number): Float32Array {
  return new Float32Array(secs(seconds));
}

function join(...parts: Float32Array[]): Float32Array {
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

/** Feed in awkward chunk sizes, like a real audio callback would. */
function run(seg: UtteranceSegmenter, audio: Float32Array, chunk = 2048): SegmenterEvent[] {
  const events: SegmenterEvent[] = [];
  for (let i = 0; i < audio.length; i += chunk) events.push(...seg.push(audio.subarray(i, i + chunk)));
  return events;
}

const utterances = (events: SegmenterEvent[]) =>
  events.filter((e): e is Extract<SegmenterEvent, { type: 'utterance' }> => e.type === 'utterance');

describe('UtteranceSegmenter', () => {
  it('emits one utterance for speech surrounded by silence', () => {
    const seg = new UtteranceSegmenter();
    const events = run(seg, join(silence(1), tone(2), silence(1.5)));
    const u = utterances(events);
    assert.equal(u.length, 1);
    const seconds = u[0].audio.length / SAMPLE_RATE;
    // 2 s of speech + <= 0.3 s pre-roll + ~0.2 s trailing quiet
    assert.ok(seconds >= 2 && seconds <= 2.8, `utterance was ${seconds}s`);
    assert.equal(events[0].type, 'speech-start');
  });

  it('splits two phrases separated by a pause', () => {
    const seg = new UtteranceSegmenter();
    const u = utterances(run(seg, join(silence(0.5), tone(1.5), silence(1.2), tone(1.5), silence(1.2))));
    assert.equal(u.length, 2);
  });

  it('keeps a short dip inside one sentence together', () => {
    const seg = new UtteranceSegmenter();
    const u = utterances(run(seg, join(silence(0.5), tone(1), silence(0.4), tone(1), silence(1.2))));
    assert.equal(u.length, 1, 'a 0.4 s gap is below the 0.7 s end-of-utterance silence');
  });

  it('ignores a brief click', () => {
    const seg = new UtteranceSegmenter();
    assert.equal(utterances(run(seg, join(silence(1), tone(0.1, 0.5), silence(2)))).length, 0);
  });

  it('ignores steady background noise', () => {
    const seg = new UtteranceSegmenter();
    assert.equal(utterances(run(seg, noise(5, 0.006))).length, 0);
  });

  it('still detects speech over a noisy background', () => {
    const seg = new UtteranceSegmenter();
    const bg = noise(6, 0.006);
    const speech = join(silence(2), tone(2, 0.25), silence(2));
    const mixed = bg.map((v, i) => v + speech[i]);
    assert.equal(utterances(run(seg, mixed)).length, 1);
  });

  it('force-cuts a very long monologue into pieces', () => {
    const seg = new UtteranceSegmenter({ maxUtteranceMs: 5000 });
    const u = utterances(run(seg, join(silence(0.5), tone(12), silence(1.5))));
    assert.ok(u.length >= 2, `expected the 12 s of speech to be cut, got ${u.length}`);
    for (const x of u) assert.ok(x.audio.length / SAMPLE_RATE <= 5.6);
  });

  it('keeps the start of the first word (pre-roll)', () => {
    const seg = new UtteranceSegmenter();
    const u = utterances(run(seg, join(silence(1), tone(1.5), silence(1.5))));
    // 1.5 s of tone + ~0.2-0.3 s pre-roll + trailing quiet: clearly longer than the tone alone
    assert.ok(u[0].audio.length > secs(1.5) + secs(0.15));
  });

  it('flush() emits speech that was still in progress when the mic stopped', () => {
    const seg = new UtteranceSegmenter();
    const events = run(seg, join(silence(0.5), tone(1.5)));
    assert.equal(utterances(events).length, 0);
    assert.equal(utterances(seg.flush()).length, 1);
  });

  it('gives the same result however the audio is chunked', () => {
    const audio = join(silence(1), tone(1.5), silence(1.2), tone(1), silence(1.2));
    const a = utterances(run(new UtteranceSegmenter(), audio, 128));
    const b = utterances(run(new UtteranceSegmenter(), audio, 4096));
    assert.deepEqual(
      a.map((u) => u.audio.length),
      b.map((u) => u.audio.length),
    );
  });
});
