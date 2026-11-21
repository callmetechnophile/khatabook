import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { bridgeStt, bridgeTts, gnaniConfigured } from './gnani.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// tiny .env loader (no dependency)
try {
  for (const line of fs.readFileSync(path.join(root, '.env'), 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*([^#\n]*)/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].trim();
  }
} catch { /* no .env yet */ }

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json' };
const OLLAMA = process.env.OLLAMA_URL || 'http://localhost:11434';

// Access gate for public (tunnelled) traffic. Requests straight from this machine (localhost) are always allowed.
// Tunnelled requests carry a Cloudflare header and must present ACCESS_CODE via ?code=... (then a cookie) or the cookie.
const viaTunnel = (req) => !!(req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for']);
const cookieOf = (req) => (req.headers.cookie || '').split(/;\s*/).map((c) => c.split('=')).find(([k]) => k === 'khatawas_access')?.[1];
// PUBLIC_DEMO=1 opens the app to everyone (no code), protected by usage caps on the paid Gnani sockets instead.
const PUBLIC = () => process.env.PUBLIC_DEMO === '1';
const clientIp = (req) => String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
const perIp = new Map(); let dayKey = '', dayCount = 0;
const LIMITS = { perIpPer10Min: Number(process.env.LIMIT_IP_10MIN || 60), perDay: Number(process.env.LIMIT_DAY || 3000) };
function withinQuota(req) { // each push-to-talk press opens one /stt and one /tts socket
  const today = new Date().toISOString().slice(0, 10);
  if (today !== dayKey) { dayKey = today; dayCount = 0; }
  if (dayCount >= LIMITS.perDay) return false;
  const ip = clientIp(req), now = Date.now();
  const hits = (perIp.get(ip) || []).filter((t) => now - t < 600000);
  if (hits.length >= LIMITS.perIpPer10Min) { perIp.set(ip, hits); return false; }
  hits.push(now); perIp.set(ip, hits); dayCount++;
  if (perIp.size > 5000) for (const [k, v] of perIp) if (!v.some((t) => now - t < 600000)) perIp.delete(k);
  return true;
}
function allowed(req) {
  const code = process.env.ACCESS_CODE;
  if (!viaTunnel(req)) return true;
  if (PUBLIC()) return true;
  if (!code) return false; // never serve the public without a code
  const a = Buffer.from(cookieOf(req) || ''), b = Buffer.from(code);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/healthz') { res.writeHead(200, { 'content-type': 'text/plain' }); return res.end('ok'); } // host health check (no data)
  if (viaTunnel(req) && process.env.ACCESS_CODE && url.searchParams.get('code') === process.env.ACCESS_CODE) {
    res.writeHead(302, { 'set-cookie': `khatawas_access=${process.env.ACCESS_CODE}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=604800`, location: '/' });
    return res.end();
  }
  if (!allowed(req)) { res.writeHead(401, { 'content-type': 'text/plain' }); return res.end('KHATAWAS demo: access code required. Use the link you were given.'); }
  if (url.pathname === '/api/config') {
    res.setHeader('content-type', 'application/json');
    return res.end(JSON.stringify({ gnani: gnaniConfigured() }));
  }
  if (url.pathname.startsWith('/ollama/')) {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    try {
      const up = await fetch(OLLAMA + url.pathname.slice(7), { method: req.method, body: req.method === 'GET' ? undefined : Buffer.concat(chunks) });
      res.writeHead(up.status, { 'content-type': 'application/json' });
      return res.end(Buffer.from(await up.arrayBuffer()));
    } catch { res.writeHead(502); return res.end('{}'); }
  }
  // static: /src/* from repo src, everything else from public/
  const rel = url.pathname === '/' ? 'index.html' : (url.pathname === '/app' ? 'app.html' : url.pathname.slice(1));
  const base = rel.startsWith('src/') ? root : path.join(root, 'public');
  const file = path.normalize(path.join(base, rel));
  if (!file.startsWith(base) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('not found'); }
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, sock, head) => {
  if (!allowed(req)) return sock.destroy();
  const p = new URL(req.url, 'http://x').pathname;
  if (p !== '/stt' && p !== '/tts') return sock.destroy();
  if (PUBLIC() && viaTunnel(req) && !withinQuota(req)) return sock.destroy(); // protects Gnani credits
  wss.handleUpgrade(req, sock, head, (ws) => (p === '/stt' ? bridgeStt(ws) : bridgeTts(ws)));
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`KHATAWAS on http://localhost:${port}  (Gnani ${gnaniConfigured() ? 'configured' : 'NOT configured - using browser fallback'})`));
