// Benchmark: Timbre v3.0 speaks each test sentence -> (optionally mix synthetic shop noise) -> Prisma v3.0 -> parser (+Qwen fallback).
// Measures accuracy and latency with REAL Gnani calls (uses credits). Output: docs/benchmark.json + docs/benchmark.md
import fs from 'node:fs';
import { synthesize, openStt } from '../server/gnani.js';
import { parse } from '../src/parser.js';
import { parseWithQwen } from '../src/llm.js';
import { Ledger } from '../src/ledger.js';
import { nameKey } from '../src/translit.js';

for (const line of fs.readFileSync(new URL('../.env', import.meta.url), 'utf8').split('\n')) {
  const m = line.match(/^\s*([A-Z_]+)\s*=\s*([^#\n]*)/);
  if (m && !process.env[m[1]]) process.env[m[1]] = m[2].trim();
}

const SNR_DB = 5;
const VOICES = ['Shivani', 'Avinash', 'Devansh', 'Lakshmi'];
const E = (kind, amount, customer) => ({ kind, amount, customer });
const CASES = [
  ['Ramesh ka teen sau chaalis rupaye udhaar', [E('credit', 340, 'ramesh')]],
  ['Sunita ka paanch sau udhaar', [E('credit', 500, 'sunita')]],
  ['Mohan ka ek hazaar do sau udhaar', [E('credit', 1200, 'mohan')]],
  ['Geeta ka dedh sau udhaar', [E('credit', 150, 'geeta')]],
  ['Imran ka dhai sau udhaar', [E('credit', 250, 'imran')]],
  ['Anil ka saade teen sau udhaar', [E('credit', 350, 'anil')]],
  ['Pooja ka 340 rupaye udhaar', [E('credit', 340, 'pooja')]],
  ['Kavita ka three hundred forty rupees udhaar', [E('credit', 340, 'kavita')]],
  ['Ramesh ne do sau rupaye diye', [E('payment', 200, 'ramesh')]],
  ['Sunita ne paanch sau wapas kiye', [E('payment', 500, 'sunita')]],
  ['Mohan ka sau rupaye jama kar do', [E('payment', 100, 'mohan')]],
  ['Geeta ne pachaas rupaye de diye', [E('payment', 50, 'geeta')]],
  ['Nahi, chaar sau tees', [E('correction', 430)]],
  ['Nahi, paanch sau', [E('correction', 500)]],
  ['Aaj kitna udhaar gaya', [E('query_today')]],
  ['Ramesh ka total kitna hai', [E('query_customer', undefined, 'ramesh')]],
  ['Sunita ka hisaab batao', [E('query_customer', undefined, 'sunita')]],
  ['Kul kitna baaki hai', [E('query_total')]],
  ['Ramesh ka teen sau udhaar, kal do sau diya tha', [E('credit', 300, 'ramesh'), E('payment', 200, 'ramesh')]],
  ['Sunita ka paanch sau udhaar kal sau rupaye diye', [E('credit', 500, 'sunita'), E('payment', 100, 'sunita')]],
  ['Mohan ne parso ek sau pachaas diye', [E('payment', 150, 'mohan')]],
  ['Geeta ke khate me chaar sau ka atta chadha do', [E('credit', 400, 'geeta')]],
  ['Imran ko aath sau ka saman diya', [E('credit', 800, 'imran')]],
  ['Anil ne dedh hazaar ka maal liya', [E('credit', 1500, 'anil')]],
  // Held-out set: written AFTER run 1 and never used to tune the parser.
  ['Rajesh ka chhe sau pachaas udhaar', [E('credit', 650, 'rajesh')], true],
  ['Farida ne teen sau pachaas rupaye jama kiye', [E('payment', 350, 'farida')], true],
  ['Vikram ka do hazaar paanch sau udhaar', [E('credit', 2500, 'vikram')], true],
  ['Neha ka sattar rupaye udhaar', [E('credit', 70, 'neha')], true],
  ['Deepak ne ek sau bees diye', [E('payment', 120, 'deepak')], true],
  ['Salma ka total batao', [E('query_customer', undefined, 'salma')], true],
  ['Aaj kitne rupaye wapas aaye', [E('query_today')], true],
  ['Rajesh ka chaar sau udhaar, parso sau diya tha', [E('credit', 400, 'rajesh'), E('payment', 100, 'rajesh')], true],
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : null; };

async function speak(text, voice) {
  const chunks = [];
  const r = await synthesize(text, { sampleRate: 16000, voice, onAudio: (b) => chunks.push(b) });
  const buf = Buffer.concat(chunks);
  return { pcm: new Int16Array(buf.buffer, buf.byteOffset, Math.floor(buf.length / 2)), firstAudioMs: r.firstAudioMs };
}

// Synthetic shop noise: pink noise + babble made of other speakers' clips, scaled to the target SNR.
function mixNoise(speech, babbleClips) {
  const lead = 4800, tail = 4800;
  const n = speech.length + lead + tail;
  let sp = 0;
  for (const v of speech) sp += v * v;
  const speechRms = Math.sqrt(sp / speech.length);
  const noise = new Float64Array(n);
  let b0 = 0, b1 = 0, b2 = 0;
  for (let i = 0; i < n; i++) { // Paul Kellet pink-noise filter
    const w = Math.random() * 2 - 1;
    b0 = 0.99765 * b0 + w * 0.099046; b1 = 0.963 * b1 + w * 0.2965164; b2 = 0.57 * b2 + w * 1.0526913;
    noise[i] = (b0 + b1 + b2 + w * 0.1848) * 0.25;
  }
  for (const clip of babbleClips) { const off = Math.floor(Math.random() * 8000); for (let i = 0; i < n && i + off < clip.length + n; i++) { const j = i - off; if (j >= 0 && j < clip.length) noise[i] += clip[j] * 0.8; } }
  let np = 0;
  for (let i = 0; i < n; i++) np += noise[i] * noise[i];
  const scale = (speechRms / Math.pow(10, SNR_DB / 20)) / Math.sqrt(np / n);
  const out = new Int16Array(n);
  for (let i = 0; i < n; i++) {
    const s = i >= lead && i < lead + speech.length ? speech[i - lead] : 0;
    out[i] = Math.max(-32768, Math.min(32767, Math.round(s + noise[i] * scale)));
  }
  return out;
}

async function transcribe(pcm) {
  const stt = openStt({ hotwords: ['Ramesh', 'Sunita', 'Mohan', 'Geeta', 'Imran', 'Anil', 'Pooja', 'Kavita'] });
  const texts = []; let commitAt = 0, firstAfterCommit = null, lastAt = 0, lat = [];
  const done = new Promise((resolve, reject) => {
    stt.ws.on('message', (m) => {
      const r = JSON.parse(m.toString());
      if (r.type === 'transcript') { if (r.text) texts.push(r.text); lat.push(r.latency); lastAt = Date.now(); if (commitAt && firstAfterCommit === null) firstAfterCommit = Date.now() - commitAt; }
      if (r.type === 'error') reject(new Error(r.message));
    });
    stt.ws.on('error', reject);
    stt.ws.on('open', async () => {
      const bytes = Buffer.from(pcm.buffer, pcm.byteOffset, pcm.byteLength);
      for (let i = 0; i < bytes.length; i += 1024) { stt.sendPcm(bytes.subarray(i, i + 1024)); await sleep(30); } // real-time cadence
      commitAt = Date.now(); stt.finish();
      const t0 = Date.now();
      while (Date.now() - t0 < 6000) { await sleep(100); if (texts.length && Date.now() - lastAt > 1200) break; }
      stt.ws.close(); resolve();
    });
  });
  await done;
  return { text: texts.join(' '), sttAfterEndMs: firstAfterCommit, gnaniLatency: lat };
}

function matches(expected, actions) {
  if (expected.length !== actions.length) return false;
  return expected.every((e, i) => {
    const a = actions[i];
    if (a.kind !== e.kind) return false;
    if (e.amount !== undefined && a.amount !== e.amount) return false;
    if (e.customer && !(a.customer?.name || '').split(' ').some((w) => nameKey(w) === nameKey(e.customer))) return false; // phonetic: राजेश == Rajesh
    return true;
  });
}

const results = [];
const clips = [];
console.log('Synthesizing clean speech...');
for (let i = 0; i < CASES.length; i++) {
  const { pcm, firstAudioMs } = await speak(CASES[i][0], VOICES[i % VOICES.length]);
  clips.push({ pcm, firstAudioMs });
}
let replyTtsMs = [];
for (const mode of ['clean', 'noisy']) {
  console.log(`\n=== ${mode.toUpperCase()}${mode === 'noisy' ? ` (SNR ${SNR_DB} dB, synthetic pink noise + babble)` : ''} ===`);
  for (let i = 0; i < CASES.length; i++) {
    const [say, expected, held] = CASES[i];
    let audio = clips[i].pcm;
    if (mode === 'noisy') audio = mixNoise(audio, [clips[(i + 5) % CASES.length].pcm, clips[(i + 11) % CASES.length].pcm, clips[(i + 17) % CASES.length].pcm]);
    const tr = await transcribe(audio);
    const t1 = performance.now();
    let p = parse(tr.text); let via = 'parser'; let actions = p.actions;
    const tParse = performance.now() - t1;
    let qwenMs = 0;
    if (p.needs_llm) { const t2 = performance.now(); const q = await parseWithQwen(tr.text, { timeoutMs: 8000 }); qwenMs = performance.now() - t2; if (q.length) { actions = actions.concat(q); via = 'qwen'; } }
    const ok = matches(expected, actions);
    // reply latency: first audio of the spoken reply (clean mode, first 10 only, to save credits)
    if (mode === 'clean' && replyTtsMs.length < 10 && actions.length) {
      const l = new Ledger(); const rep = l.apply(actions).replies.join(' ');
      try { replyTtsMs.push((await speak(rep, 'Shivani')).firstAudioMs); } catch { /* ignore */ }
    }
    results.push({ mode, held: !!held, say, heard: tr.text, expected, got: actions.map((a) => ({ kind: a.kind, amount: a.amount, customer: a.customer?.name })), ok, via, parseMs: +tParse.toFixed(2), qwenMs: Math.round(qwenMs), sttAfterEndMs: tr.sttAfterEndMs });
    console.log(`${ok ? 'PASS' : 'FAIL'} [${via}] ${say}\n       heard: ${tr.text}`);
  }
}

const summarize = (mode, heldOnly = false) => {
  const r = results.filter((x) => x.mode === mode && (!heldOnly || x.held));
  const writes = r.flatMap((x) => x.expected.map((e, i) => ({ e, a: x.got[i] }))).filter((z) => z.e.amount !== undefined);
  return {
    sentences: r.length, fullyCorrect: r.filter((x) => x.ok).length,
    amountExact: writes.filter((z) => z.a && z.a.amount === z.e.amount).length, amountTotal: writes.length,
    directionRight: writes.filter((z) => z.a && z.a.kind === z.e.kind).length,
    sttAfterEndMs_p50: pct(r.map((x) => x.sttAfterEndMs).filter((v) => v != null), 0.5), sttAfterEndMs_p95: pct(r.map((x) => x.sttAfterEndMs).filter((v) => v != null), 0.95),
    parseMs_max: Math.max(...r.map((x) => x.parseMs)), qwenUsed: r.filter((x) => x.via === 'qwen').length,
  };
};
const summary = { clean: summarize('clean'), noisy: summarize('noisy'), heldOut_clean: summarize('clean', true), heldOut_noisy: summarize('noisy', true), replyFirstAudioMs_p50: pct(replyTtsMs, 0.5), replyFirstAudioMs_p95: pct(replyTtsMs, 0.95), replySamples: replyTtsMs.length, snrDb: SNR_DB, date: new Date().toISOString().slice(0, 10) };
fs.writeFileSync(new URL('../docs/benchmark.json', import.meta.url), JSON.stringify({ summary, results }, null, 2));
console.log('\nSUMMARY', JSON.stringify(summary, null, 2));
