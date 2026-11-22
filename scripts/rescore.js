// Re-scores a saved benchmark run from the stored Prisma transcripts, with the CURRENT parser. No Gnani calls, no credits.
import fs from 'node:fs';
import { parse } from '../src/parser.js';
import { parseWithQwen } from '../src/llm.js';
import { nameKey } from '../src/translit.js';

const file = new URL('../docs/benchmark.json', import.meta.url);
const { summary: old, results } = JSON.parse(fs.readFileSync(file, 'utf8'));
const ok = (exp, acts) => exp.length === acts.length && exp.every((e, i) => acts[i].kind === e.kind && (e.amount === undefined || acts[i].amount === e.amount)
  && (!e.customer || (acts[i].customer?.name || '').split(' ').some((w) => nameKey(w) === nameKey(e.customer))));
for (const r of results) {
  const p = parse(r.heard); let acts = p.actions;
  if (p.needs_llm) for (const c of p.unknown_clauses.length ? p.unknown_clauses : [r.heard]) acts = acts.concat(await parseWithQwen(c, { timeoutMs: 8000, fallbackCustomer: acts.at(-1)?.customer?.name || null }));
  r.got = acts.map((a) => ({ kind: a.kind, amount: a.amount, customer: a.customer?.name })); r.ok = ok(r.expected, acts);
}
const sum = (mode, held) => {
  const r = results.filter((x) => x.mode === mode && (held === undefined || x.held === held));
  const w = r.flatMap((x) => x.expected.map((e, i) => ({ e, a: x.got[i] }))).filter((z) => z.e.amount !== undefined);
  return { sentences: r.length, fullyCorrect: r.filter((x) => x.ok).length, amountExact: w.filter((z) => z.a?.amount === z.e.amount).length, amountTotal: w.length, directionRight: w.filter((z) => z.a?.kind === z.e.kind).length };
};
const summary = { ...old, clean: sum('clean'), noisy: sum('noisy'), heldOut_clean: sum('clean', true), heldOut_noisy: sum('noisy', true), rescored: true };
fs.writeFileSync(file, JSON.stringify({ summary, results }, null, 2));
console.log(JSON.stringify({ clean: summary.clean, noisy: summary.noisy, heldOut_clean: summary.heldOut_clean, heldOut_noisy: summary.heldOut_noisy }, null, 1));
for (const r of results.filter((x) => !x.ok)) console.log(r.mode, r.held ? '[held]' : '', '|', r.say, '| heard:', r.heard, '| got:', JSON.stringify(r.got.map((a) => [a.kind, a.amount, a.customer])));
