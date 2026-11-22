// The only file that knows Gnani Vachana's wire protocol (verified against docs.gnani.ai):
//   STT  wss://api.vachana.ai/stt/v3/stream   Prisma v3.0. Config in upgrade headers, raw PCM16 mono frames of exactly
//        1024 bytes, text frame {"type":"commit"} closes the open utterance, replies are JSON {type:"transcript", text}.
//        There are NO partial transcripts: each VAD segment yields one final transcript.
//   TTS  wss://api.vachana.ai/api/v1/tts      Timbre v3.0. One JSON request, replies are JSON with base64 audio chunks.
// The API key stays on the server; the browser only ever talks to /stt and /tts on this server.
import WebSocket from 'ws';
import { LANGS, AUTO, fromGnani } from '../src/lang.js';

// The two endpoints are public (not secrets), so they default here; only GNANI_API_KEY must be provided.
const STT_URL = () => process.env.GNANI_STT_WS_URL || 'wss://api.vachana.ai/stt/v3/stream';
const TTS_URL = () => process.env.GNANI_TTS_WS_URL || 'wss://api.vachana.ai/api/v1/tts';
export const gnaniConfigured = () => !!process.env.GNANI_API_KEY;

const FRAME = 1024; // 512 samples x 2 bytes = 32 ms at 16 kHz

// Header values must be plain ASCII/latin1; keep only safe keyterms (Prisma accepts up to 200).
const keyterms = (list) => JSON.stringify((list || []).filter((w) => /^[\x20-\x7e]+$/.test(w)).slice(0, 50));

// Which Prisma language/format to use for the shopkeeper's choice ('hi', 'kn', ... or 'auto'). Unknown values fall back to Hindi.
// GNANI_STT_LANGUAGE / GNANI_STT_FORMAT still override the Hindi default, as before.
export function sttConfig(lang) {
  if (lang === 'auto') return AUTO;
  const c = LANGS[lang] || LANGS.hi;
  return c === LANGS.hi ? { ...c, stt: process.env.GNANI_STT_LANGUAGE || c.stt, format: process.env.GNANI_STT_FORMAT || c.format } : c;
}

export function openStt({ hotwords = [], lang = 'hi', language, format } = {}) {
  const cfg = sttConfig(lang);
  language ||= cfg.stt; format ||= cfg.format;
  const ws = new WebSocket(STT_URL(), {
    headers: {
      'x-api-key-id': process.env.GNANI_API_KEY,
      language_code: language,
      model: 'prisma-v3.0',
      'x-sample-rate': '16000',
      format,
      keyterms: keyterms(hotwords),
    },
  });
  let pending = Buffer.alloc(0);
  const api = {
    ws,
    // Accepts PCM16 of any length, forwards exact 1024-byte frames.
    sendPcm(buf) {
      pending = Buffer.concat([pending, buf]);
      while (pending.length >= FRAME && ws.readyState === WebSocket.OPEN) {
        ws.send(pending.subarray(0, FRAME));
        pending = pending.subarray(FRAME);
      }
    },
    // Flush the tail, add ~0.5 s of silence so the last word finalises, then force-close the utterance.
    finish() {
      if (ws.readyState !== WebSocket.OPEN) return;
      const tail = Buffer.concat([pending, Buffer.alloc(FRAME * 16)]);
      for (let i = 0; i + FRAME <= tail.length; i += FRAME) ws.send(tail.subarray(i, i + FRAME));
      pending = Buffer.alloc(0);
      ws.send(JSON.stringify({ type: 'commit' }));
    },
  };
  return api;
}

export function bridgeStt(client) {
  let stt = null, stopped = false, doneTimer = null, started = 0, finals = 0, sentDone = false;
  const send = (o) => client.readyState === 1 && client.send(JSON.stringify(o));
  const finish = () => { if (!sentDone) { sentDone = true; send({ type: 'done' }); } };
  const schedule = (ms) => { clearTimeout(doneTimer); doneTimer = setTimeout(finish, ms); };
  // Prisma announces every segment with `speech_start` and answers each with one `transcript`. After the shopkeeper lets go we
  // know exactly whether a segment is still on its way, so we neither cut a sentence short nor wait a fixed time for nothing.
  const settle = () => {
    if (!stopped) return;
    if (started > finals) schedule(2500);   // a segment is still being transcribed: wait for it (safety cap 2.5 s)
    else if (finals > 0) schedule(120);     // everything announced has arrived
  };

  client.on('message', (data, isBinary) => {
    if (isBinary) { stt?.sendPcm(Buffer.from(data)); return; }
    const msg = JSON.parse(data.toString());
    if (msg.type === 'start') {
      stopped = false; started = 0; finals = 0; sentDone = false;
      stt = openStt({ hotwords: msg.hotwords, lang: msg.lang });
      stt.ws.on('message', (m) => {
        let r; try { r = JSON.parse(m.toString()); } catch { return; }
        if (r.type === 'speech_start') started += 1;
        else if (r.type === 'transcript') {
          finals += 1;
          if (r.text) send({ type: 'final', text: r.text, latency: r.latency, lang: fromGnani(r.detected_language_code) });
          settle();
        } else if (r.type === 'error') send({ type: 'error', message: r.message });
      });
      stt.ws.on('unexpected-response', (_q, res) => send({ type: 'error', message: `Gnani rejected the connection (HTTP ${res.statusCode}). Check GNANI_API_KEY.` }));
      stt.ws.on('error', (e) => send({ type: 'error', message: String(e.message) }));
    } else if (msg.type === 'stop' && stt) {
      stopped = true;
      stt.finish();
      // If nothing was ever heard Prisma sends nothing; if segments are pending we wait for them. Either way never hang the sale.
      if (started > finals) schedule(2500); else if (finals > 0) schedule(250); else schedule(1500);
    }
  });
  client.on('close', () => { clearTimeout(doneTimer); stt?.ws.close(); });
}

// Synthesize via Timbre v3.0 over WebSocket. onAudio(Buffer) is called per chunk as it arrives (raw PCM16 mono).
export function synthesize(text, { sampleRate = 24000, onAudio, language = process.env.GNANI_TTS_LANGUAGE || 'hi-en', voice = process.env.GNANI_TTS_VOICE || 'Shivani' } = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(TTS_URL(), { headers: { 'X-API-Key-ID': process.env.GNANI_API_KEY } });
    let firstAt = null;
    const t0 = Date.now();
    ws.on('open', () => ws.send(JSON.stringify({
      text, model: 'timbre-v3.0', voice, language, speed: 1.0,
      audio_config: { sample_rate: sampleRate, encoding: 'linear_pcm', container: 'raw', num_channels: 1, sample_width: 2 },
    })));
    ws.on('message', (m) => {
      let r; try { r = JSON.parse(m.toString()); } catch { return; }
      if (r.type === 'audio' && r.data?.audio) {
        firstAt ??= Date.now() - t0;
        onAudio?.(Buffer.from(r.data.audio, 'base64'));
      } else if (r.type === 'complete') { ws.close(); resolve({ firstAudioMs: firstAt, totalMs: Date.now() - t0 }); }
      else if (r.type === 'error') { ws.close(); reject(new Error(r.message || 'TTS error')); }
    });
    ws.on('unexpected-response', (_q, res) => reject(new Error(`Gnani TTS rejected the connection (HTTP ${res.statusCode})`)));
    ws.on('error', reject);
  });
}

export function bridgeTts(client) {
  client.on('message', async (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.type !== 'speak') return;
    try {
      const cfg = LANGS[msg.lang] || LANGS.hi; // reply language: the app tells us which of the ten to speak
      const opts = cfg === LANGS.hi ? {} : { language: cfg.tts, voice: cfg.voice };
      await synthesize(String(msg.text || '').slice(0, 500), { ...opts, onAudio: (b) => client.readyState === 1 && client.send(b, { binary: true }) });
      client.send(JSON.stringify({ type: 'done' }));
    } catch (e) { client.send(JSON.stringify({ type: 'error', message: String(e.message) })); }
  });
}
