import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Resampler } from './resample.ts';

function sine(freq: number, rate: number, seconds: number): Float32Array {
  const out = new Float32Array(Math.round(rate * seconds));
  for (let i = 0; i < out.length; i += 1) out[i] = Math.sin((2 * Math.PI * freq * i) / rate);
  return out;
}

/** Dominant frequency by counting rising zero crossings. */
function frequencyOf(samples: Float32Array, rate: number): number {
  let crossings = 0;
  for (let i = 1; i < samples.length; i += 1) if (samples[i - 1] < 0 && samples[i] >= 0) crossings += 1;
  return crossings / (samples.length / rate);
}

function resampleInChunks(r: Resampler, input: Float32Array, chunk: number): Float32Array {
  const parts: Float32Array[] = [];
  for (let i = 0; i < input.length; i += chunk) parts.push(r.push(input.subarray(i, i + chunk)));
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

describe('Resampler', () => {
  for (const rate of [48_000, 44_100]) {
    it(`converts ${rate} Hz to 16 kHz with the right length and pitch`, () => {
      const out = resampleInChunks(new Resampler(rate), sine(440, rate, 2), 1024);
      assert.ok(Math.abs(out.length - 32_000) <= 2, `got ${out.length} samples`);
      assert.ok(Math.abs(frequencyOf(out, 16_000) - 440) < 3);
    });
  }

  it('is a no-op at 16 kHz', () => {
    const input = sine(300, 16_000, 0.5);
    assert.equal(new Resampler(16_000).push(input), input);
  });

  it('gives the same result however the input is chunked (no glitches at chunk edges)', () => {
    const input = sine(300, 48_000, 1);
    const a = resampleInChunks(new Resampler(48_000), input, 128);
    const b = resampleInChunks(new Resampler(48_000), input, 4096);
    const n = Math.min(a.length, b.length);
    for (let i = 0; i < n; i += 1) assert.ok(Math.abs(a[i] - b[i]) < 1e-5, `differs at ${i}`);
  });

  it('keeps the amplitude of an in-band tone roughly intact', () => {
    const out = resampleInChunks(new Resampler(48_000), sine(1000, 48_000, 1), 2048);
    const peak = out.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
    assert.ok(peak > 0.9 && peak <= 1.01, `peak was ${peak}`);
  });
});
