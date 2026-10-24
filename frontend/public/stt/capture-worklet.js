// AudioWorklet that hands raw microphone samples to the main thread in ~43 ms batches.
// Plain JS on purpose: worklet modules are loaded by URL and are not run through the bundler.
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.batch = new Float32Array(2048);
    this.filled = 0;
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) return true;
    let i = 0;
    while (i < channel.length) {
      const n = Math.min(channel.length - i, this.batch.length - this.filled);
      this.batch.set(channel.subarray(i, i + n), this.filled);
      this.filled += n;
      i += n;
      if (this.filled === this.batch.length) {
        // Transfer (not copy) the buffer; allocate a fresh one for the next batch.
        this.port.postMessage(this.batch, [this.batch.buffer]);
        this.batch = new Float32Array(2048);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('capture-processor', CaptureProcessor);
