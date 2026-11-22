import { Ledger } from '/src/ledger.js';
import { handleTranscript } from '/src/pipeline.js';
import { LANGS } from '/src/lang.js';
import { t } from '/src/i18n.js';
import { nameKey } from '/src/translit.js';
import { saveLedger, loadLedger, wipeAll } from '/store.js';
import { createListener, speak, gnaniEnabled } from '/speech.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const useGnani = await gnaniEnabled();
let ledger = Ledger.fromJSON(await loadLedger());
let listener;
let disclosed = false; // the first spoken reply of a session must say it is an AI

// ---- language (a per-viewer convenience, remembered in this browser only)
const PICK = [
  { k: 'auto', n: 'Auto-detect', s: 'Any of the ten', hint: 'Hold to talk' },
  { k: 'hi', n: 'हिन्दी · Hinglish', s: 'Hindi', hint: 'बोलने के लिए दबाए रखें' },
  { k: 'en', n: 'English', s: 'English', hint: 'Hold to talk' },
  { k: 'kn', n: 'ಕನ್ನಡ', s: 'Kannada', hint: 'ಮಾತನಾಡಲು ಒತ್ತಿ ಹಿಡಿಯಿರಿ' },
  { k: 'mr', n: 'मराठी', s: 'Marathi', hint: 'बोलण्यासाठी दाबून ठेवा' },
  { k: 'ta', n: 'தமிழ்', s: 'Tamil', hint: 'பேச அழுத்திப் பிடிக்கவும்' },
  { k: 'te', n: 'తెలుగు', s: 'Telugu', hint: 'మాట్లాడటానికి నొక్కి పట్టుకోండి' },
  { k: 'gu', n: 'ગુજરાતી', s: 'Gujarati', hint: 'બોલવા માટે દબાવી રાખો' },
  { k: 'bn', n: 'বাংলা', s: 'Bengali', hint: 'বলতে চেপে ধরুন' },
  { k: 'ml', n: 'മലയാളം', s: 'Malayalam', hint: 'സംസാരിക്കാൻ അമർത്തിപ്പിടിക്കുക' },
  { k: 'pa', n: 'ਪੰਜਾਬੀ', s: 'Punjabi', hint: 'ਬੋਲਣ ਲਈ ਦਬਾ ਕੇ ਰੱਖੋ' },
];
let lang = 'hi';
try { const v = localStorage.getItem('khatawas_lang'); if (v && PICK.some((p) => p.k === v)) lang = v; } catch { /* storage blocked: default */ }
const pickOf = () => PICK.find((p) => p.k === lang);
// Sample sentences (the non-Hindi ones are real Prisma v3.0 transcripts, see docs/languages.md)
const SAY = {
  hi: ['Ramesh ka 340 udhaar', 'Ramesh ne 200 diye', 'Ramesh ka kitna baaki hai'],
  en: ['Mohan 250 rupees credit', 'How much does Mohan owe'],
  kn: ['ರಮೇಶ್ ಅವರಿಗೆ 500 ರೂಪಾಯಿ ಸಾಲ ಬರೆಯಿರಿ.', 'ರಮೇಶ್ ಅವರ ಬಾಕಿ ಎಷ್ಟು?'],
  mr: ['रमेशला 500 रुपये उधार लिहा।', 'रमेश यांचे किती बाकी आहे?'],
  ta: ['ரமேஷுக்கு 500 ரூபாய் கடன் எழுதுங்கள்.'],
  te: ['రమేష్కి 500 రూపాయలు అప్పు రాయండి.'],
  gu: ['રમેશને 500 રૂપિયા ઉધાર લખો।'],
  bn: ['রমেশকে 500 টাকা বাকি লিখো।'],
  ml: ['രമേശിന് 500 രൂപ കടം എഴുതുക.'],
  pa: ['ਰਮੇਸ਼ ਨੂੰ 500 ਰੁਪਏ ਉਧਾਰ ਲਿਖੋ।'],
};
const replyLang = () => (lang === 'auto' ? (listener?.detected || 'hi') : lang);

function renderChips() {
  const ks = lang === 'auto' ? ['hi', 'kn', 'mr', 'ta', 'te'] : [lang];
  $('chips').innerHTML = ks.flatMap((k) => (SAY[k] || []).map((s) => `<button class="chip" data-s="${esc(s)}"><b>${esc(PICK.find((p) => p.k === k).s)}</b>${esc(s)}</button>`)).join('');
}

// ---- customer list
const COL = ['#c2410c', '#0f766e', '#6d28d9', '#b45309', '#be185d', '#1d4ed8', '#4d7c0f'];
const colorOf = (s) => COL[[...s].reduce((a, c) => a + c.charCodeAt(0), 0) % COL.length];
const fmt = (n) => '₹' + n.toLocaleString('en-IN');
let flashId = null;
function render() {
  const firsts = ledger.customers.map((c) => nameKey(c.name.split(' ')[0]));
  $('list').innerHTML = ledger.customers.map((c) => {
    const b = ledger.balance(c.id);
    const shared = firsts.filter((f) => f === nameKey(c.name.split(' ')[0])).length > 1;
    const rest = c.name.split(' ').slice(1).join(' ');
    return `<div class="row${flashId === c.id ? ' flash' : ''}"><div class="av" style="background:${colorOf(c.name)}">${esc(c.name[0] || '?')}</div>`
      + `<div class="who"><b>${esc(c.name)}</b>${shared && rest ? '<small>same first name as another customer</small>' : ''}</div>`
      + `<div class="amt ${b > 0 ? 'owes' : 'zero'}">${b === 0 ? '—' : b < 0 ? `${fmt(-b)} advance` : fmt(b)}</div></div>`;
  }).join('') || '<div class="row"><div class="who"><b>No customers yet</b><small>Say a name and an amount, or add one below.</small></div></div>';
  $('total').textContent = fmt(ledger.customers.reduce((s, c) => s + Math.max(0, ledger.balance(c.id)), 0)) + ' outstanding';
  flashId = null;
}

// ---- conversation
function showThread({ heard, reply, via, ms, note, options, direction }) {
  $('thread').innerHTML = `<div class="bubble me">${esc(heard || '—')}</div><div class="meta r"><span>Heard</span></div>`
    + (reply ? `<div class="bubble bot">${esc(reply)}</div><div class="meta"><span>${esc(via || '')}</span><span>${ms ? `${ms} ms to reply` : ''}</span></div>` : '')
    + (note ? `<div class="note"><span>${esc(note)}</span><button class="undo" id="undo">${esc(t(replyLangNow, 'other_btn'))}</button></div>` : '')
    + (options ? `<div class="opts">${options.map((o, i) => `<button class="opt" data-opt="${i}">${esc(o)}</button>`).join('')}</div>` : '')
    + (direction ? `<div class="opts"><button class="opt" data-say="udhaar">Udhaar</button><button class="opt" data-say="jama">Jama</button></div>` : '');
  $('undo')?.addEventListener('click', undoPick);
  $('thread').querySelectorAll('[data-opt]').forEach((b) => b.addEventListener('click', () => process(b.textContent)));
  $('thread').querySelectorAll('[data-say]').forEach((b) => b.addEventListener('click', () => process(b.dataset.say)));
}
let replyLangNow = 'hi';
// Nothing is pre-filled: the first screen is an empty state, not a made-up conversation.
const WELCOME = () => { $('thread').innerHTML = '<div class="empty">Hold the mic and say a name and an amount, or tap a sample below. Nothing is saved until you do.</div>'; };

async function process(text) {
  const t0 = performance.now();
  window.__lastText = text; // debug aid: raw transcript as received
  if (!text) { showThread({ heard: '—' }); return; }
  const picksBefore = ledger.log.length;
  const r = await handleTranscript(text, ledger, { lang: replyLang() });
  replyLangNow = r.lang;
  if (r.changed) await saveLedger(ledger.toJSON());
  let reply = r.replies.join(' ');
  if (!disclosed) { reply = `${t(r.lang, 'ai')} ${reply}`; disclosed = true; }
  const picked = ledger.log.length > picksBefore ? ledger.log.at(-1) : null;
  const last = ledger.txns.at(-1);
  if (last && r.changed) flashId = last.customerId;
  showThread({
    heard: text, reply, via: r.via, ms: Math.round(performance.now() - t0),
    note: picked ? `${picked.picked}: ${picked.why}.` : '',
    options: ledger.pending?.options?.map((o) => o.name),
    direction: ledger.pending?.type === 'direction',
  });
  render();
  speak(reply, { useGnani, lang: r.lang });
}
async function undoPick() { // "nahi, doosra Rahul"
  const r = ledger.apply([{ kind: 'reassign', customer: { matches: [], name: null } }]);
  await saveLedger(ledger.toJSON());
  const reply = r.replies.join(' ');
  const last = ledger.txns.at(-1); if (last) flashId = last.customerId;
  showThread({ heard: t(replyLangNow, 'other_btn'), reply, via: 'undo', ms: 0 });
  render(); speak(reply, { useGnani, lang: replyLangNow });
}

// ---- push-to-talk (hold). Pointer events cover touch + mouse; feedback starts on press, not on release.
const ptt = $('ptt');
async function down(e) {
  e.preventDefault();
  if (ptt.classList.contains('on')) return;
  ptt.classList.add('on'); $('hint').textContent = 'Listening…';
  try {
    listener ||= await createListener({
      hotwords: () => ledger.customers.map((c) => c.name), // customer names biased into STT, re-read on every press
      onPartial: (txt) => { $('thread').innerHTML = `<div class="bubble me">${esc(txt || '…')}</div>`; },
      getLang: () => lang, useGnani,
    });
    await listener.start();
  } catch (err) { $('hint').textContent = String(err.message || err); ptt.classList.remove('on'); }
}
async function up() {
  if (!ptt.classList.contains('on')) return;
  ptt.classList.remove('on'); $('hint').textContent = 'Thinking…';
  if (listener) {
    const text = await listener.stop();
    await process(text);
    if (!useGnani) listener = null;
  }
  $('hint').textContent = pickOf().hint;
}
ptt.addEventListener('pointerdown', (e) => { ptt.setPointerCapture?.(e.pointerId); down(e); });
['pointerup', 'pointercancel'].forEach((ev) => ptt.addEventListener(ev, up));
addEventListener('keydown', (e) => { if (e.code === 'Space' && e.target === document.body && !e.repeat) down(e); });
addEventListener('keyup', (e) => { if (e.code === 'Space' && e.target === document.body) up(); });

$('chips').addEventListener('click', (e) => { const c = e.target.closest('.chip'); if (c) process(c.dataset.s); });
$('typed').addEventListener('submit', (e) => { e.preventDefault(); const v = $('txt').value.trim(); $('txt').value = ''; if (v) process(v); });
$('addcust').addEventListener('submit', async (e) => {
  e.preventDefault();
  const v = $('newcust').value.trim(); $('newcust').value = '';
  if (!v) return;
  const dupe = ledger.hasCustomer(v);
  $('dup').hidden = !dupe;
  if (dupe) { $('dup').textContent = `There is already a ${v}. Add a surname or a landmark so the app can tell them apart, for example "${v.split(' ')[0]} Doodhwala".`; return; }
  const c = ledger.addCustomer(v); flashId = c.id;
  await saveLedger(ledger.toJSON()); listener = null; render(); // new listener so the name list is sent next time
});
$('wipe').addEventListener('click', async () => {
  await wipeAll(); ledger = new Ledger(); render();
  showThread({ heard: '—', reply: t(lang === 'auto' ? 'hi' : lang, 'wiped'), via: 'local', ms: 0 });
});

// ---- language sheet: follows the finger 1:1, release hands the velocity to a spring, the landing is chosen by projected momentum
const sheet = $('sheet'), scrim = $('scrim'); let y = 2000, open = false, raf = 0, drag = null;
const H = () => sheet.offsetHeight || 460;
const paint = (v) => { y = v; sheet.style.transform = `translateY(${v}px)`; scrim.style.opacity = Math.max(0, Math.min(1, 1 - v / H())); };
function springTo(target, v0 = 0, done) {
  cancelAnimationFrame(raf);
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) { paint(target); done?.(); return; }
  let pos = y, vel = v0, last = performance.now();
  const k = 260, c = 2 * Math.sqrt(k) * 0.9; // damping ratio 0.9, about a 0.35 s response
  (function step(now) {
    const dt = Math.min(0.032, (now - last) / 1000); last = now;
    vel += (-k * (pos - target) - c * vel) * dt; pos += vel * dt; paint(pos);
    if (Math.abs(vel) < 8 && Math.abs(pos - target) < 0.5) { paint(target); done?.(); return; }
    raf = requestAnimationFrame(step);
  })(last);
}
function openSheet() { sheet.style.visibility = 'visible'; scrim.style.pointerEvents = 'auto'; open = true; if (y > H()) paint(H()); springTo(0); }
function closeSheet(v = 0) { open = false; scrim.style.pointerEvents = 'none'; springTo(H() + 20, v, () => { sheet.style.visibility = 'hidden'; $('langBtn').focus(); }); }
$('langBtn').addEventListener('click', openSheet);
scrim.addEventListener('click', () => closeSheet());
addEventListener('keydown', (e) => { if (e.key === 'Escape' && open) closeSheet(); });
const rubber = (o, d, c = 0.55) => (o * d * c) / (d + c * Math.abs(o)); // resistance past the open position
// A tap must stay a tap: the pointer is only captured once it has moved ~8px, otherwise the click would be retargeted to the
// sheet and the language buttons inside it would never fire.
sheet.addEventListener('pointerdown', (e) => {
  if (e.target.closest('.grid') && $('grid').scrollTop > 0) return;
  drag = { y0: e.clientY, base: y, hist: [[e.clientY, performance.now()]], live: false, id: e.pointerId };
});
sheet.addEventListener('pointermove', (e) => {
  if (!drag) return;
  const dy = e.clientY - drag.y0;
  if (!drag.live) {
    if (Math.abs(dy) < 8) return;
    drag.live = true; drag.y0 = e.clientY; drag.base = y; cancelAnimationFrame(raf); sheet.setPointerCapture(drag.id); return;
  }
  drag.hist.push([e.clientY, performance.now()]); if (drag.hist.length > 6) drag.hist.shift();
  paint(drag.base + (dy < 0 ? -rubber(-dy, H()) : dy));
});
function endDrag() {
  if (!drag) return;
  if (!drag.live) { drag = null; return; } // it was a tap
  const h = drag.hist, a = h[0], b = h[h.length - 1];
  const v = ((b[0] - a[0]) / Math.max(1, b[1] - a[1])) * 1000; drag = null;
  const projected = y + (v / 1000) * 0.998 / (1 - 0.998); // where a flick would come to rest
  if (projected > H() * 0.45) closeSheet(v); else springTo(0, v);
}
['pointerup', 'pointercancel'].forEach((ev) => sheet.addEventListener(ev, endDrag));

function paintGrid() {
  $('grid').innerHTML = PICK.map((l) => `<button class="l${l.k === 'auto' ? ' auto' : ''}" data-k="${l.k}" aria-pressed="${l.k === lang}"><b>${esc(l.n)}</b><small>${esc(l.s)}</small></button>`).join('');
  $('langName').textContent = pickOf().n.split(' ·')[0];
  $('hint').textContent = pickOf().hint;
}
$('grid').addEventListener('click', (e) => {
  const b = e.target.closest('.l'); if (!b) return;
  lang = b.dataset.k; listener = null; // a new listener so the next press opens Prisma in the chosen language
  try { localStorage.setItem('khatawas_lang', lang); } catch { /* storage blocked: fine */ }
  paintGrid(); renderChips(); setTimeout(() => closeSheet(), 120);
});

$('mode').textContent = useGnani ? 'STT: Gnani Prisma v3 · TTS: Timbre v3' : 'DEV MODE: browser speech (Gnani not configured)';
paint(2000); paintGrid(); renderChips(); render(); WELCOME();
