import test from 'node:test';
import assert from 'node:assert/strict';
import { Ledger } from '../src/ledger.js';
import { handleTranscript } from '../src/pipeline.js';

const run = (l, t, lang = 'hi') => handleTranscript(t, l, { useQwen: false, lang });
const rahuls = () => {
  const l = new Ledger(); let clock = 1e12; l.now = () => clock; l.tick = (ms) => { clock += ms; };
  l.customers = [{ id: 1, name: 'Rahul Sharma' }, { id: 2, name: 'Rahul Doodhwala' }]; l.nextId = 1;
  return l;
};

test('a payment goes to the only Rahul who owes that much, with no question', async () => {
  const l = rahuls();
  await run(l, 'Rahul Sharma ka 800 udhaar'); l.tick(10 * 60000);
  await run(l, 'Rahul Doodhwala ka 150 udhaar'); l.tick(10 * 60000);
  const r = await run(l, 'Rahul ne 500 diye');
  assert.equal(l.balance(1), 300);
  assert.equal(l.balance(2), 150);
  assert.match(r.replies[0], /Rahul Sharma/); // the full name is spoken back so a wrong guess is caught at once
  assert.equal(l.log.at(-1).why, 'only one of them owes that much');
});

test('a credit right after talking about one Rahul is for that Rahul; otherwise it asks', async () => {
  const l = rahuls();
  await run(l, 'Rahul Doodhwala ka 100 udhaar'); l.tick(30000);
  await run(l, 'Rahul ka 50 udhaar');
  assert.equal(l.balance(2), 150);
  l.tick(10 * 60000); // conversation went cold
  const q = await run(l, 'Rahul ka 70 udhaar');
  assert.match(q.replies[0], /Kaun sa Rahul\? Rahul Sharma ya Rahul Doodhwala\?/);
  assert.equal(l.balance(1), 0);
  await run(l, 'Sharma');
  assert.equal(l.balance(1), 70);
});

test('both Rahuls were just discussed: do not guess, ask', async () => {
  const l = rahuls();
  await run(l, 'Rahul Sharma ka 100 udhaar'); await run(l, 'Rahul Doodhwala ka 100 udhaar');
  const q = await run(l, 'Rahul ka 40 udhaar');
  assert.match(q.replies[0], /Kaun sa Rahul/);
  assert.equal(l.txns.length, 2);
});

test('"nahi, doosra Rahul" moves the last entry to the other Rahul', async () => {
  const l = rahuls();
  await run(l, 'Rahul Sharma ka 100 udhaar'); l.tick(10 * 60000);
  await run(l, 'Rahul ka 60 udhaar'); // auto-picked Sharma (only one touched in the last 2 min? none) -> asks
  await run(l, 'Doodhwala');
  assert.equal(l.balance(2), 60);
  const r = await run(l, 'nahi, doosra Rahul');
  assert.equal(l.balance(2), 0);
  assert.equal(l.balance(1), 160);
  assert.match(r.replies[0], /Rahul Sharma/);
});

test('a regular customer wins over someone with no history', async () => {
  const l = rahuls();
  for (let i = 0; i < 3; i++) l.txns.push({ id: i + 1, customerId: 2, kind: 'credit', amount: 10, date: new Date(l.now()).toISOString().slice(0, 10) });
  l.nextId = 4;
  await run(l, 'Rahul ka 25 udhaar');
  assert.equal(l.balance(2), 55);
  assert.equal(l.log.at(-1).why, 'regular customer');
});

test('replies to a duplicate-name question come in the shopkeeper\'s language', async () => {
  const l = rahuls();
  const q = await run(l, 'ರಾಹುಲ್ ಅವರಿಗೆ 70 ರೂಪಾಯಿ ಸಾಲ ಬರೆಯಿರಿ', 'kn');
  assert.match(q.replies[0], /ಯಾವ Rahul\?/);
});
