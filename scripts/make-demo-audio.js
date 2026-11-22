// Generates the synthetic shopkeeper voice clips for the demo video (Gnani Timbre v3.0, male voice). Writes WAVs into public/_demo/ (gitignored).
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { synthesize } from '../server/gnani.js';
for (const line of fs.readFileSync(new URL('../.env', import.meta.url), 'utf8').split('\n')) {
  const m = line.match(/^\s*([A-Z_]+)\s*=\s*([^#\n]*)/); if (m && !process.env[m[1]]) process.env[m[1]] = m[2].trim();
}
const U = ['Sunita ka teen sau chaalis udhaar', 'Sunita ka dedh sau diya', 'Ramesh Kumar ka teen sau udhaar', 'Nahi, chaar sau', 'Ramesh ka paanch sau udhaar', 'Gupta', 'Aaj kitna udhaar gaya', 'Sunita ka total'];
for (let i = 0; i < U.length; i++) {
  const chunks = [];
  await synthesize(U[i], { sampleRate: 16000, voice: 'Devansh', language: 'hi-en', onAudio: (b) => chunks.push(b) });
  const raw = `/tmp/khdemo/u${i + 1}.raw`; fs.writeFileSync(raw, Buffer.concat(chunks));
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 's16le', '-ar', '16000', '-ac', '1', '-i', raw, `public/_demo/u${i + 1}.wav`]);
  console.log(`u${i + 1}`, U[i], (Buffer.concat(chunks).length / 32000).toFixed(1) + 's');
}
