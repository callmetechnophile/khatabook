// Downsamples mic audio to 16 kHz mono PCM16 and posts ~100 ms chunks. Runs off the main thread.
// Each output sample is the AVERAGE of the input samples it covers (a box low-pass filter), which avoids the aliasing
// distortion you get from simply dropping samples when going from 44.1/48 kHz down to 16 kHz.
class Pcm extends AudioWorkletProcessor {
  constructor() { super(); this.buf = []; this.ratio = sampleRate / 16000; this.acc = 0; this.sum = 0; this.n = 0; }
  process(inputs) {
    const ch = inputs[0][0];
    if (!ch) return true;
    for (let i = 0; i < ch.length; i++) {
      this.sum += Math.max(-1, Math.min(1, ch[i])); this.n += 1; this.acc += 1;
      if (this.acc >= this.ratio) { this.acc -= this.ratio; this.buf.push(this.sum / this.n); this.sum = 0; this.n = 0; }
    }
    if (this.buf.length >= 1600) {
      const out = new Int16Array(this.buf.length);
      this.buf.forEach((v, i) => { out[i] = v < 0 ? v * 0x8000 : v * 0x7fff; });
      this.port.postMessage(out.buffer, [out.buffer]);
      this.buf = [];
    }
    return true;
  }
}
registerProcessor('pcm', Pcm);
