// transcript -> actions (parser first, Qwen only as fallback) -> ledger -> spoken reply, in the shopkeeper's language
import { parse } from './parser.js';
import { parseWithQwen } from './llm.js';
import { detectLang, langOf } from './lang.js';
import { t } from './i18n.js';

// opts.lang = the language the shopkeeper picked (or what Prisma detected); the transcript's own script has the last word.
export async function handleTranscript(text, ledger, { useQwen = true, lang = 'hi' } = {}) {
  const L = detectLang(text, langOf(lang));
  ledger.lang = L;
  const pend = ledger.resolvePending(text);
  if (pend) return { ...pend, via: 'clarify', lang: L };
  const parsed = parse(text, { customers: ledger.customerList(), lang: L });
  let actions = parsed.actions;
  let via = 'parser';
  if (parsed.needs_llm && useQwen) {
    // Qwen only sees the clauses the rules could not understand, never the whole sentence.
    const lastName = [...actions].reverse().map((a) => a.customer?.name).find(Boolean) || null;
    for (const clause of parsed.unknown_clauses.length ? parsed.unknown_clauses : [text]) {
      const q = await parseWithQwen(clause, { fallbackCustomer: lastName });
      if (q.length) { actions = actions.concat(q); via = 'qwen'; }
    }
  }
  if (!actions.length) return { replies: [t(L, 'not_understood')], changed: false, via, lang: L };
  return { ...ledger.apply(actions), via, lang: L };
}
