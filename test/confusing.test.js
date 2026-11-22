// Regression tests for mixed / confusing sentences found by stress-testing (see docs/test-sentences.md).
import test from 'node:test';
import assert from 'node:assert/strict';
import { Ledger } from '../src/ledger.js';
import { handleTranscript } from '../src/pipeline.js';
import { parse } from '../src/parser.js';
import { findAmount } from '../src/numbers.js';
import { nameKey } from '../src/translit.js';

const NOW = new Date('2026-11-21T10:00:00Z');
const shop = (...names) => { const l = new Ledger(); names.forEach((n) => l.addCustomer(n)); return l; };
const say = (l, s, lang = 'hi') => handleTranscript(s, l, { useQwen: false, lang });
const amt = (s) => findAmount(s.split(' '))?.value;
const owner = (l, tx) => l.customers.find((c) => c.id === tx.customerId).name;

test('"do not write it" is never booked', async () => {
  for (const [s, lang] of [['Sunita ko 500 rupaye udhaar mat dena', 'hi'], ["don't add 500 for Sunita", 'en'], ['never write 500 for Sunita', 'en']]) {
    const l = shop('Sunita'); const r = await say(l, s, lang);
    assert.equal(l.txns.length, 0, s);
    assert.equal(r.replies.length, 1);
  }
});

test('sava / paune / decimals', () => {
  assert.equal(amt('sava sau'), 125);
  assert.equal(amt('paune do sau'), 175);
  assert.equal(amt('sava do sau'), 225);
  assert.equal(amt('sava hazaar'), 1250);
  assert.equal(parse('Imran ne 2.5 hazaar diye', { now: NOW }).actions[0].amount, 2500);
  assert.equal(parse('Imran ne 1.5 lakh diye', { now: NOW }).actions[0].amount, 150000);
  assert.equal(parse('Imran ne 250.50 rupaye diye', { now: NOW }).actions.length, 1); // paise do not become a second entry
});

test('"rs" is only a currency word, never cut out of "parso"', () => {
  const r = parse('parso Imran ne sau diye', { now: NOW }).actions[0];
  assert.equal(r.customer.name, 'imran');
  assert.equal(r.date, '2026-11-19');
  assert.equal(parse('Imran ka rs 500 udhaar', { now: NOW }).actions[0].amount, 500);
  assert.equal(parse('Imran ka rs.500 udhaar', { now: NOW }).actions[0].amount, 500);
  assert.equal(parse('Imran ka 500rs udhaar', { now: NOW }).actions[0].amount, 500);
});

test('pronouns and English verbs never become customers', async () => {
  const l = shop('Sunita');
  await say(l, 'Sunita owes me nothing, she paid 500', 'en');
  assert.equal(l.customers.length, 1);
  assert.equal(owner(l, l.txns[0]), 'Sunita');
  const g = shop('Geeta');
  await say(g, 'Geeta paid 300 yesterday and took 200 today', 'en');
  assert.equal(g.customers.length, 1);
  assert.deepEqual(g.txns.map((x) => [x.kind, x.amount]), [['payment', 300], ['credit', 200]]);
});

test('a surname or nickname alone finds the customer (no ghost duplicate)', async () => {
  const l = shop('Rahul Sharma', 'Rahul Doodhwala');
  await say(l, 'Doodhwala ka 200 udhaar');
  assert.equal(l.customers.length, 2);
  assert.equal(owner(l, l.txns[0]), 'Rahul Doodhwala');
});

test('Tamil and Malayalam names match the same person as Hindi (final n / chillu)', () => {
  assert.equal(nameKey('இம்ரான்'), nameKey('Imran'));
  assert.equal(nameKey('ഇമ്രാൻ'), nameKey('Imran'));
  assert.equal(nameKey('ഇമ്രാൻ'), nameKey('इमरान'));
});

test('corrections inside one sentence', async () => {
  let l = shop('Imran');
  await say(l, 'Imran ka udhaar 300 nahi 350');
  assert.deepEqual(l.txns.map((x) => [x.kind, x.amount]), [['credit', 350]]);
  l = shop('Imran');
  await say(l, 'Imran ka 300 udhaar do hazaar nahi'); // "not 2000": the 2000 must be dropped, not applied
  assert.deepEqual(l.txns.map((x) => [x.kind, x.amount]), [['credit', 300]]);
  l = shop('Imran');
  await say(l, 'Imran ne paanch sau diye the lekin abhi sirf teen sau diye');
  assert.deepEqual(l.txns.map((x) => [x.kind, x.amount]), [['payment', 300]]);
});

test('one verb covers two amounts: "kal 200 aur aaj 300 diye"', async () => {
  const l = shop('Sunita');
  await say(l, 'Sunita ne kal do sau aur aaj teen sau diye');
  assert.deepEqual(l.txns.map((x) => [x.kind, x.amount]), [['payment', 200], ['payment', 300]]);
  assert.notEqual(l.txns[0].date, l.txns[1].date);
});

test('"atta chadha do" is understood by the rules alone (the live site has no Qwen)', async () => {
  const l = shop('Geeta');
  const r = await say(l, 'Geeta ke khate me teen sau ka atta chadha do');
  assert.equal(r.via, 'parser');
  assert.deepEqual(l.txns.map((x) => [x.kind, x.amount]), [['credit', 300]]);
});
