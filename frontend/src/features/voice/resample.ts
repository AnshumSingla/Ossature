// Streaming resampler to 16 kHz mono, so the mic can run at its native rate (44.1/48 kHz).
// We deliberately do NOT ask the browser for a 16 kHz AudioContext: Firefox has historically
// refused to connect a microphone stream to a context at a different sample rate, and
// resampling here keeps one code path for every browser.
//
// Box-filter averaging over each output sample's input window (a cheap anti-alias low-pass),
// which is more than good enough for speech recognition. Stateful so chunk boundaries don't
// glitch. Pure logic, unit-tested in Node (resample.test.ts).

export class Resampler {
  private readonly inputRate: number;
  private readonly outputRate: number;
  private readonly ratio: number; // input samples per output sample
  private position = 0; // fractional input index of the next output sample's window start
  private carry = new Float32Array(0); // input not yet consumed

  constructor(inputRate: number, outputRate = 16_000) {
    this.inputRate = inputRate;
    this.outputRate = outputRate;
    this.ratio = inputRate / outputRate;
  }

  push(input: Float32Array): Float32Array {
    if (this.inputRate === this.outputRate) return input;

    const buf = new Float32Array(this.carry.length + input.length);
    buf.set(this.carry);
    buf.set(input, this.carry.length);

    const out: number[] = [];
    let pos = this.position;
    // Emit an output sample only once its whole input window is available.
    while (pos + this.ratio <= buf.length) {
      const start = pos;
      const end = pos + this.ratio;
      let sum = 0;
      let weight = 0;
      for (let i = Math.floor(start); i < Math.ceil(end); i += 1) {
        const w = Math.min(i + 1, end) - Math.max(i, start); // partial overlap at the edges
        sum += buf[i] * w;
        weight += w;
      }
      out.push(sum / weight);
      pos = end;
    }

    const consumed = Math.floor(pos);
    this.carry = buf.slice(consumed);
    this.position = pos - consumed;
    return Float32Array.from(out);
  }
}
