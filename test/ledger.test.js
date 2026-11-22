import test from 'node:test';
import assert from 'node:assert/strict';
import { Ledger } from '../src/ledger.js';
import { handleTranscript } from '../src/pipeline.js';

const run = async (l, t) => (await handleTranscript(t, l, { useQwen: false }));

test('credit, payment, balance', async () => {
  const l = new Ledger();
  await run(l, 'Ramesh ka 340 udhaar, kal 200 diya tha');
  assert.equal(l.balance(1), 140);
});

test('spoken correction changes last amount', async () => {
  const l = new Ledger();
  await run(l, 'Ramesh ka 340 udhaar');
  const r = await run(l, 'nahi, 430');
  assert.equal(l.balance(1), 430);
  assert.match(r.replies[0], /430/);
});

test('two Ramesh -> asks which one, then books it', async () => {
  const l = new Ledger();
  l.customers = [{ id: 1, name: 'Ramesh Kumar' }, { id: 2, name: 'Ramesh Gupta' }];
  const q = await run(l, 'Ramesh ka 500 udhaar');
  assert.match(q.replies[0], /Kaun sa/);
  await run(l, 'Gupta');
  assert.equal(l.balance(2), 500);
  assert.equal(l.balance(1), 0);
});

test('spoken answers to queries', async () => {
  const l = new Ledger();
  await run(l, 'Ramesh ka 340 udhaar');
  assert.match((await run(l, 'Ramesh ka total?')).replies[0], /340/);
});

test('queries never create customers; "kul kitna baaki" gives overall total', async () => {
  const l = new Ledger();
  await run(l, 'Sunita ka 100 udhaar');
  const r = await run(l, 'Kul kitna baaki hai');
  assert.match(r.replies[0], /Kul baaki 100/);
  await run(l, 'Mohan ka total');
  assert.equal(l.customers.length, 1);
});

test('full names create separate customers, then first name asks which', async () => {
  const l = new Ledger();
  await run(l, 'Ramesh Kumar ka 100 udhaar');
  await run(l, 'Ramesh Gupta ka 200 udhaar');
  assert.deepEqual(l.customers.map((c) => c.name), ['Ramesh Kumar', 'Ramesh Gupta']);
  assert.equal(l.balance(2), 200);
  assert.match((await run(l, 'Ramesh ka 500 udhaar')).replies[0], /Kaun sa/);
});

test('Devanagari and Latin spellings are the same customer', async () => {
  const l = new Ledger();
  await run(l, 'Mohan ka 100 udhaar');
  await run(l, 'मोहन का दो सौ उधार।');
  assert.equal(l.customers.length, 1);
  assert.equal(l.balance(1), 300);
  await run(l, 'अनिल का पाँच सौ उधार।');
  assert.equal(l.customers[1].name, 'Anil');
});

test('phonetic names: फरीदा == Farida', async () => {
  const l = new Ledger();
  await run(l, 'Farida ka 100 udhaar');
  await run(l, 'फरीदा का दो सौ उधार।');
  assert.equal(l.customers.length, 1);
});

test('answers with punctuation still resolve "which Ramesh"', async () => {
  const l = new Ledger();
  l.customers = [{ id: 1, name: 'Ramesh Kumar' }, { id: 2, name: 'Ramesh Gupta' }];
  await run(l, 'Ramesh का पाँच सौ उधार।');
  await run(l, 'Gupta.');
  assert.equal(l.balance(2), 500);
});

test('addCustomer registers a zero-balance customer once (any spelling)', () => {
  const l = new Ledger();
  l.addCustomer('Sunita'); l.addCustomer('सुनीता');
  assert.equal(l.customers.length, 1);
  assert.equal(l.balance(1), 0);
});

test('garbled tail is joined: "... तीन सौ चालीस। के सुधार।" books a credit, never a payment', async () => {
  const l = new Ledger(); l.addCustomer('Sunita');
  const r = await run(l, 'Sunita का तीन सौ चालीस। के सुधार।');
  assert.equal(l.balance(1), 340);
  assert.match(r.replies[0], /udhaar/);
});

test('amount + customer with no direction word asks, and never guesses', async () => {
  const l = new Ledger(); l.addCustomer('Sunita');
  const q = await run(l, 'Sunita का तीन सौ चालीस।');
  assert.match(q.replies[0], /udhaar ya jama/);
  assert.equal(l.balance(1), 0);
  await run(l, 'उधार');
  assert.equal(l.balance(1), 340);
  const q2 = await run(l, 'Sunita का सौ।'); await run(l, 'jama');
  assert.equal(l.balance(1), 240);
});

test('a new sentence with an amount is not an answer to the direction question', async () => {
  const l = new Ledger(); l.addCustomer('Sunita');
  await run(l, 'Sunita का तीन सौ चालीस।');            // asks "udhaar ya jama?"
  await run(l, 'Sunita का डेढ़ सौ दिया।');             // a new command, must NOT answer the question
  assert.equal(l.balance(1), -150);                     // only the new payment; the 340 was never booked
  assert.equal(l.txns.length, 1);
});

test('zero-width characters inside Prisma text do not split words', async () => {
  const l = new Ledger(); l.addCustomer('Sunita');
  await run(l, 'Sunita का तीन सौ चालीस। सु‍धार');
  assert.equal(l.balance(1), 340);
});
