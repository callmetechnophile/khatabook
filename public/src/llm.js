import { findAmount, canon } from './numbers.js';
import { normalize, looksLikeQuery, hasPaymentCue } from './parser.js';
import { nameKey } from './translit.js';
// Offline fallback: Qwen2.5-1.5B (INT4 via Ollama). Only called when the deterministic parser gives up.
const env = typeof process !== 'undefined' ? process.env : {};
// In the browser we call our own server (/ollama) so there is no CORS and no direct exposure of Ollama.
const OLLAMA = typeof window !== 'undefined' ? '/ollama' : (env.OLLAMA_URL || 'http://localhost:11434');
export const QWEN_MODEL = env.QWEN_MODEL || 'qwen2.5:1.5b';

const SYSTEM = `You convert a kirana shopkeeper's Hindi/English/Hinglish sentence into JSON. Output ONLY JSON.
Schema: {"actions":[{"kind":"credit|payment|correction|query_total|query_today|query_customer","customer":"name or null","date_offset_days":0}]}
Rules:
- credit = the CUSTOMER TOOK goods or money on udhaar (saman liya, maal diya, udhaar, likh lena, baaki). The customer owes more.
- payment = the CUSTOMER PAID money back (de gaya, de diye, jama, wapas, chukaya).
- "maal diya" / "saman diya" means the shopkeeper gave goods, so it is credit.
- correction = the speaker fixes the previous amount (nahi, galat, matlab).
- If the sentence is not about a ledger entry or a ledger question, return {"actions":[]}.
Do not output amounts. kal=-1, parso=-2, aaj=0.
Examples:
"Ramesh ne paanch sau ka saman liya" -> {"actions":[{"kind":"credit","customer":"Ramesh","date_offset_days":0}]}
"Mohan ko teen sau ka maal diya" -> {"actions":[{"kind":"credit","customer":"Mohan","date_offset_days":0}]}
"Sunita bhabhi do sau de gayi" -> {"actions":[{"kind":"payment","customer":"Sunita","date_offset_days":0}]}
"Gupta ji ka hisaab kitna hai" -> {"actions":[{"kind":"query_customer","customer":"Gupta","date_offset_days":0}]}
"mausam accha hai" -> {"actions":[]}`;

export async function parseWithQwen(text, { timeoutMs = 4000, fetchImpl = fetch, fallbackCustomer = null } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetchImpl(`${OLLAMA}/api/chat`, {
      method: 'POST',
      signal: ctl.signal,
      body: JSON.stringify({
        model: QWEN_MODEL, stream: false, format: 'json', options: { temperature: 0 },
        messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: text }],
      }),
    });
    const data = await res.json();
    const parsed = JSON.parse(data.message.content);
    // Guardrails: the 1.5B model may only choose kind/customer. Amounts always come from the deterministic number parser.
    const norm = normalize(text);
    const amt = findAmount(norm.split(' ').map(canon));
    const out = [];
    for (const a of parsed.actions || []) {
      if (!a || typeof a.kind !== 'string') continue;
      const name = (a.customer && !['null', 'none', 'unknown', ''].includes(String(a.customer).trim().toLowerCase())
        ? String(a.customer).trim().toLowerCase() : null) || (['credit', 'payment'].includes(a.kind) ? fallbackCustomer : null);
      const writes = ['credit', 'payment', 'correction'].includes(a.kind);
      if (a.kind === 'payment' && !hasPaymentCue(text)) continue;        // never guess a payment: money going down needs a payment word
      if (writes && !amt) continue;                                   // no amount spoken -> nothing to write
      if ((a.kind === 'credit' || a.kind === 'payment') && !name) continue; // need a customer
      // The model may not invent people: a customer it names must really be in the sentence (or carried over from the last entry).
      if (name && name !== fallbackCustomer && !norm.split(' ').some((w) => nameKey(w) && nameKey(w) === nameKey(name.split(' ')[0]))) continue;
      if (a.kind.startsWith('query') && !looksLikeQuery(text)) continue;    // no hallucinated questions
      if (!['credit', 'payment', 'correction', 'query_total', 'query_today', 'query_customer'].includes(a.kind)) continue;
      const d = new Date(); d.setDate(d.getDate() + (Number(a.date_offset_days) || 0));
      out.push({ kind: a.kind, amount: writes ? amt.value : undefined, customer: { matches: [], name }, date: d.toISOString().slice(0, 10), source: 'qwen' });
    }
    return out;
  } catch {
    return []; // offline/slow model must never break the sale
  } finally {
    clearTimeout(timer);
  }
}
