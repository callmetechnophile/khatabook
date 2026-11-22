// Live loopback: Timbre v3.0 speaks a sentence -> Prisma v3.0 transcribes it -> parser. Uses real Gnani credits.
import fs from 'node:fs';
import { synthesize, openStt } from '../server/gnani.js';
import { parse } from '../src/parser.js';

for (const line of fs.readFileSync(new URL('../.env', import.meta.url), 'utf8').split('\n')) {
  const m = line.match(/^\s*([A-Z_]+)\s*=\s*([^#\n]*)/);
  if (m && !process.env[m[1]]) process.env[m[1]] = m[2].trim();
}
const sentences = process.argv.slice(2).length ? process.argv.slice(2) : ['Ramesh ka teen sau chaalis rupaye udhaar'];

for (const text of sentences) {
  const chunks = [];
  const tts = await synthesize(text, { sampleRate: 16000, onAudio: (b) => chunks.push(b) });
  const pcm = Buffer.concat(chunks);
  console.log(`\nSPOKEN : ${text}\nTTS    : first audio ${tts.firstAudioMs} ms, ${(pcm.length / 32000).toFixed(1)} s of audio`);

  const stt = openStt({ hotwords: ['Ramesh', 'Sunita'] });
  const t0 = Date.now();
  const heard = await new Promise((resolve, reject) => {
    const out = [];
    stt.ws.on('message', (m) => {
      const r = JSON.parse(m.toString());
      if (r.type === 'connected') console.log('STT    : connected', JSON.stringify(r.config));
      if (r.type === 'transcript') out.push({ text: r.text, lat: r.latency, since: Date.now() - t0 });
      if (r.type === 'error') reject(new Error(r.message));
    });
    stt.ws.on('unexpected-response', (_q, res) => reject(new Error(`HTTP ${res.statusCode} at handshake`)));
    stt.ws.on('error', reject);
    stt.ws.on('open', async () => {
      for (let i = 0; i < pcm.length; i += 1024) { // real-time pacing: 32 ms per frame
        stt.sendPcm(pcm.subarray(i, i + 1024));
        await new Promise((r) => setTimeout(r, 30));
      }
      stt.finish();
      setTimeout(() => { stt.ws.close(); resolve(out); }, 3000);
    });
  });
  console.log('HEARD  :', heard.map((h) => h.text).join(' ') || '(nothing)', heard.map((h) => `[gnani latency ${h.lat} ms]`).join(' '));
  const r = parse(heard.map((h) => h.text).join(' '));
  console.log('PARSED :', JSON.stringify(r.actions.map((a) => ({ kind: a.kind, amount: a.amount, customer: a.customer?.name }))), r.needs_llm ? '(needs Qwen)' : '');
}
