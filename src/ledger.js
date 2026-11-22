// Ledger logic: applies parsed actions, resolves "which Rahul?" on its own when the books make it obvious (and asks one short
// question when they do not), handles corrections, answers queries. Replies are spoken in the shopkeeper's language.
import { nameKey, displayName } from './translit.js';
import { findAmount } from './numbers.js';
import { t } from './i18n.js';
import { STEMS } from './lang.js';

const firstOf = (name) => name.split(' ')[0];

export class Ledger {
  constructor() {
    this.customers = []; this.txns = []; this.nextId = 1; this.pending = null;
    this.lang = 'hi';
    this.now = () => Date.now();      // injectable clock (tests)
    this.touched = new Map();          // customer id -> time of the last entry in this session (not persisted)
    this.log = [];                     // why we auto-picked someone (audit trail, shown in the UI)
  }

  hasCustomer(name) { const k = nameKey(name); return !!k && this.customers.some((x) => nameKey(x.name) === k); }

  // Register a customer with no entry, so their name is known to Prisma as a keyterm before it is spoken.
  // Two people can share a first name ("Rahul Sharma", "Rahul Doodhwala"); only an identical full name is the same person.
  addCustomer(name) {
    const key = nameKey(name);
    if (!key) return null;
    let c = this.customers.find((x) => nameKey(x.name) === key);
    if (!c) { c = { id: this.customers.length + 1, name: displayName(name.trim()) }; this.customers.push(c); }
    return c;
  }

  customerList() { return this.customers.map((c) => ({ id: c.id, name: c.name })); }

  _resolve(ref, create = true) {
    if (ref.matches?.length === 1) return { customer: this.customers.find((c) => c.id === ref.matches[0].id) };
    if (ref.matches?.length > 1) return { ambiguous: ref.matches };
    if (!ref.name) return { missing: true };
    const key = nameKey(ref.name);
    let c = this.customers.find((x) => nameKey(x.name) === key);
    if (!c && !create) return { notFound: true };
    if (!c) { c = { id: this.customers.length + 1, name: displayName(ref.name) }; this.customers.push(c); }
    return { customer: c };
  }

  balance(customerId) {
    return this.txns.filter((t) => t.customerId === customerId)
      .reduce((s, t) => s + (t.kind === 'credit' ? t.amount : -t.amount), 0);
  }

  // "Which Rahul?" The shopkeeper should not have to manage this, so use what the books already say:
  //  1. a payment can only come from someone who owes at least that much;
  //  2. whoever we just wrote an entry for is the likely subject of the next one (payments: 5 min, credits: 2 min, because a
  //     wrong credit invents a debt);
  //  3. a regular customer (3+ entries in 30 days) against people with none.
  // Anything less clear returns null and the caller asks a one-line question. Every auto-pick is spoken back with the full
  // name and can be undone with "nahi, doosra".
  _disambiguate(options, a) {
    let cand = options.map((o) => this.customers.find((c) => c.id === o.id)).filter(Boolean);
    if (a.kind === 'payment') {
      const fits = cand.filter((c) => this.balance(c.id) >= a.amount);
      if (fits.length === 1) return { pick: fits[0], why: 'only one of them owes that much' };
      if (fits.length > 1) cand = fits;
    }
    // Only when exactly ONE of the candidates was in the conversation recently; if both were, it really is ambiguous.
    const window = a.kind === 'payment' ? 300000 : 120000;
    const recent = cand.filter((c) => this.now() - (this.touched.get(c.id) ?? -Infinity) < window);
    if (recent.length === 1) return { pick: recent[0], why: 'was the only one in this conversation' };
    const cutoff = new Date(this.now() - 30 * 86400000).toISOString().slice(0, 10);
    const freq = cand.map((c) => ({ c, n: this.txns.filter((x) => x.customerId === c.id && x.date >= cutoff).length })).sort((x, y) => y.n - x.n);
    if (freq[0].n >= 3 && freq.slice(1).every((f) => f.n === 0)) return { pick: freq[0].c, why: 'regular customer' };
    return null;
  }

  _book(a, customer, today, note) {
    const tx = { id: this.nextId++, customerId: customer.id, kind: a.kind, amount: a.amount, date: a.date || today };
    this.txns.push(tx);
    this.touched.set(customer.id, this.now());
    const bal = this.balance(customer.id);
    return t(this.lang, a.kind, { name: customer.name, amt: a.amount, bal }) + (note ? ' ' + note : '');
  }

  // Returns { replies: [string], changed: bool }. Replies are short, speakable sentences.
  apply(actions, today = new Date().toISOString().slice(0, 10)) {
    const L = this.lang;
    const replies = [];
    if (actions.length) this.pending = null; // a new request replaces any unanswered question
    for (const a of actions) {
      if (a.kind === 'skip') {
        replies.push(t(L, 'skipped')); // "don't add..." / "300 nahi": nothing is written, and we say so
      } else if (a.kind === 'ask_direction') {
        const nm = a.customer.matches?.length === 1 ? a.customer.matches[0].name : (a.customer.matches?.length > 1 ? firstOf(a.customer.matches[0].name) : displayName(a.customer.name || ''));
        this.pending = { type: 'direction', action: a };
        replies.push(t(L, 'ask_dir', { name: nm, amt: a.amount }));
      } else if (a.kind === 'credit' || a.kind === 'payment') {
        const r = this._resolve(a.customer);
        if (r.ambiguous) {
          const d = this._disambiguate(r.ambiguous, a);
          if (d) {
            this.log.push({ at: this.now(), picked: d.pick.name, why: d.why });
            replies.push(this._book(a, d.pick, today, t(L, 'auto_note', { first: firstOf(d.pick.name) })));
            continue;
          }
          this.pending = { action: a, options: r.ambiguous };
          replies.push(t(L, 'which_first', { first: firstOf(a.customer.matches[0].name), opts: r.ambiguous.map((o) => o.name).join(` ${t(L, 'or')} `) }));
          continue;
        }
        if (r.missing) { replies.push(t(L, 'whose')); continue; }
        replies.push(this._book(a, r.customer, today));
      } else if (a.kind === 'reassign') {
        const last = this.txns[this.txns.length - 1];
        const from = last && this.customers.find((c) => c.id === last.customerId);
        const others = from ? this.customers.filter((c) => c.id !== from.id && nameKey(firstOf(c.name)) === nameKey(firstOf(from.name))) : [];
        if (!last) replies.push(t(L, 'nothing_to_fix'));
        else if (others.length !== 1) replies.push(t(L, 'cant_move', { first: firstOf(from.name) }));
        else {
          last.customerId = others[0].id;
          this.touched.set(others[0].id, this.now());
          replies.push(t(L, 'moved', { amt: last.amount, name: others[0].name, bal: this.balance(others[0].id) }));
        }
      } else if (a.kind === 'correction') {
        const last = this.txns[this.txns.length - 1];
        if (!last) { replies.push(t(L, 'nothing_to_fix')); continue; }
        last.amount = a.amount;
        const c = this.customers.find((x) => x.id === last.customerId);
        replies.push(t(L, 'corrected', { name: c.name, amt: a.amount, bal: this.balance(c.id) }));
      } else if (a.kind === 'query_today') {
        const kind = a.direction === 'payment' ? 'payment' : 'credit';
        const sum = this.txns.filter((x) => x.date === today && x.kind === kind).reduce((s, x) => s + x.amount, 0);
        replies.push(t(L, kind === 'credit' ? 'today_credit' : 'today_payment', { sum }));
      } else if (a.kind === 'query_customer') {
        const r = this._resolve(a.customer, false);
        if (r.notFound) replies.push(t(L, 'no_account'));
        else if (r.customer) replies.push(t(L, 'balance', { name: r.customer.name, bal: this.balance(r.customer.id) }));
        else if (r.ambiguous) replies.push(t(L, 'which', { opts: r.ambiguous.map((o) => o.name).join(` ${t(L, 'or')} `) }));
      } else if (a.kind === 'query_total') {
        replies.push(t(L, 'total', { sum: this.customers.reduce((s, c) => s + this.balance(c.id), 0) }));
      }
    }
    return { replies, changed: actions.length > 0 };
  }

  // Answer to a "which Ramesh?" / "udhaar ya jama?" question
  resolvePending(text) {
    if (!this.pending) return null;
    if (this.pending.type === 'direction') {
      const t0 = text.toLowerCase().replace(/[.,।!?;:]/g, ' ');
      const toks = t0.split(/\s+/).filter(Boolean);
      const hit = (stems) => toks.some((x) => stems.some((s) => x.startsWith(s.normalize('NFC'))));
      const cred = /(udhaar|udhar|उधार|credit|likh|baaki|बाकी|sudhaar|सुधार)/.test(t0) || hit(STEMS.credit);
      const pay = /(jama|जमा|diya|दिया|paid|wapas|वापस|payment|chukaya)/.test(t0) || hit(STEMS.payment);
      if (cred === pay) return null; // neither (or both): not an answer
      if (findAmount(toks) || toks.length > 4) return null; // a new sentence with an amount is not an answer
      const a = { ...this.pending.action, kind: cred ? 'credit' : 'payment' };
      return this.apply([a]);
    }
    const w = text.toLowerCase().replace(/[.,।!?;:]/g, ' ').replace(/\s+/g, ' ').trim(); // Prisma adds punctuation ("Gupta.")
    const keys = w.split(/\s+/).map((x) => nameKey(x));
    const pick = this.pending.options.find((o) => keys.includes(nameKey(o.name.split(' ').pop())) || w.includes(o.name.toLowerCase()));
    if (!pick) return null;
    const a = { ...this.pending.action, customer: { matches: [pick], name: null } };
    this.pending = null;
    return this.apply([a]);
  }
}

Ledger.prototype.toJSON = function () {
  return { customers: this.customers, txns: this.txns, nextId: this.nextId };
};
Ledger.fromJSON = function (o) {
  const l = new Ledger();
  if (o) { l.customers = o.customers || []; l.txns = o.txns || []; l.nextId = o.nextId || 1; }
  return l;
};
