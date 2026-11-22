// STT + TTS clients. Gnani Prisma/Timbre via our server when configured; browser fallbacks otherwise (dev only).
const wsUrl = (p) => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${p}`;

export async function gnaniEnabled() {
  try { return (await (await fetch('/api/config')).json()).gnani; } catch { return false; }
}

const SPEECH = { hi: 'hi-IN', en: 'en-IN', kn: 'kn-IN', mr: 'mr-IN', ta: 'ta-IN', te: 'te-IN', gu: 'gu-IN', bn: 'bn-IN', ml: 'ml-IN', pa: 'pa-IN' };
// --- STT: push-to-talk. start() begins streaming; stop() resolves with the final transcript.
export async function createListener({ hotwords = [], onPartial = () => {}, useGnani, getLang = () => 'hi' }) {
  if (!useGnani) return webSpeechListener(onPartial, getLang);
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { noiseSuppression: true, echoCancellation: true, autoGainControl: true, channelCount: 1 },
  });
  const ctx = new AudioContext();
  await ctx.audioWorklet.addModule('/pcm-worklet.js');
  const src = ctx.createMediaStreamSource(stream);
  const node = new AudioWorkletNode(ctx, 'pcm');
  src.connect(node);
  let ws, finalText = '', resolveFinal;
  return {
    detected: null, // language Prisma heard on the last press (our short code), used by Auto-detect
    async start() {
      finalText = ''; this.detected = null;
      const self = this;
      const mine = new WebSocket(wsUrl('/stt'));
      ws = mine;
      mine.binaryType = 'arraybuffer';
      await new Promise((r) => { mine.onopen = r; });
      mine.send(JSON.stringify({ type: 'start', lang: getLang(), hotwords: (typeof hotwords === 'function' ? hotwords() : hotwords).slice(0, 50) })); // read fresh each press
      mine.onmessage = (e) => {
        if (ws !== mine) return; // a late message from an older press must never leak into this one
        const m = JSON.parse(e.data);
        if (m.type === 'partial') onPartial(m.text);
        if (m.type === 'final') { finalText = (finalText + ' ' + m.text).trim(); if (m.lang) self.detected = m.lang; onPartial(finalText); }
        if (m.type === 'done') resolveFinal?.(finalText);
        if (m.type === 'error') onPartial(m.message);
      };
      node.port.onmessage = (e) => ws === mine && mine.readyState === 1 && mine.send(e.data);
    },
    stop() {
      const mine = ws;
      return new Promise((res) => {
        const finish = (t) => { res(t); try { mine.close(); } catch { /* already closed */ } }; // one WebSocket per press, closed afterwards
        resolveFinal = finish;
        mine.send(JSON.stringify({ type: 'stop' }));
        setTimeout(() => finish(finalText), 4000); // safety net: never hang the sale
      });
    },
  };
}

function webSpeechListener(onPartial, getLang) {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) throw new Error('No speech recognition available. Configure Gnani in .env or use Chrome.');
  let rec, text = '', done;
  return {
    async start() {
      text = ''; rec = new SR(); rec.lang = SPEECH[getLang()] || 'hi-IN'; rec.interimResults = true; rec.continuous = true;
      rec.onresult = (e) => { text = [...e.results].map((r) => r[0].transcript).join(' '); onPartial(text); };
      rec.onend = () => done?.(text);
      rec.start();
    },
    stop() { return new Promise((res) => { done = res; rec.stop(); }); },
  };
}

// --- TTS
let playCtx;
export async function speak(text, { useGnani, lang = 'hi' }) {
  if (!useGnani) {
    const u = new SpeechSynthesisUtterance(text); u.lang = SPEECH[lang] || 'hi-IN';
    speechSynthesis.cancel(); speechSynthesis.speak(u);
    return;
  }
  playCtx ||= new AudioContext({ sampleRate: 24000 });
  const ws = new WebSocket(wsUrl('/tts'));
  ws.binaryType = 'arraybuffer';
  let t = playCtx.currentTime + 0.05;
  ws.onopen = () => ws.send(JSON.stringify({ type: 'speak', text, lang }));
  ws.onmessage = (e) => {
    if (typeof e.data === 'string') { if (JSON.parse(e.data).type === 'done') ws.close(); return; }
    const pcm = new Int16Array(e.data);
    const buf = playCtx.createBuffer(1, pcm.length, 24000);
    const ch = buf.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) ch[i] = pcm[i] / 0x8000;
    const s = playCtx.createBufferSource(); s.buffer = buf; s.connect(playCtx.destination);
    s.start(t); t += buf.duration; // schedule gaplessly so first audio plays as soon as the first chunk lands
  };
}
