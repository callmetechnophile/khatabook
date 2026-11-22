import test from 'node:test';
import assert from 'node:assert/strict';
import { Ledger } from '../src/ledger.js';
import { handleTranscript } from '../src/pipeline.js';
import { normalizeDigits, findAmount } from '../src/numbers.js';
import { nameKey, displayName } from '../src/translit.js';
import { detectLang, LANGS } from '../src/lang.js';
import { t, supportedReplyLangs } from '../src/i18n.js';

const run = (l, text, lang) => handleTranscript(text, l, { useQwen: false, lang });

// Real Prisma v3.0 `transcribe` output for each language (captured live, see docs/languages.md): digits for amounts.
const CREDIT = {
  kn: 'ರಮೇಶ್ ಅವರಿಗೆ 500 ರೂಪಾಯಿ ಸಾಲ ಬರೆಯಿರಿ.', mr: 'रमेशला 500 रुपये उधार लिहा।', ta: 'ரமேஷுக்கு 500 ரூபாய் கடன் எழுதுங்கள்.',
  te: 'రమేష్కి 500 రూపాయలు అప్పు రాయండి.', gu: 'રમેશને 500 રૂપિયા ઉધાર લખો।', bn: 'রমেশকে 500 টাকা বাকি লিখো।',
  ml: 'രമേശിന് 500 രൂപ കടം എഴുതുക.', pa: 'ਰਮੇਸ਼ ਨੂੰ 500 ਰੁਪਏ ਉਧਾਰ ਲਿਖੋ।', hi: 'रमेश का 500 रुपये उधार', en: 'Ramesh 500 rupees credit',
};
const PAYMENT = {
  kn: 'ರಮೇಶ್ ಅವರು 200 ರೂಪಾಯಿ ಜಮಾ ಮಾಡಿದರು', mr: 'रमेशने 200 रुपये जमा केले।', ta: 'ரமேஷ் 200 ரூபாய் செலுத்தினார்',
  te: 'రమేష్ 200 రూపాయలు జమ చేశారు', gu: 'રમેશે 200 રૂપિયા જમા કર્યા', bn: 'রমেশ 200 টাকা জমা দিয়েছে',
  ml: 'രമേശ് 200 രൂപ അടച്ചു', pa: 'ਰਮੇਸ਼ ਨੇ 200 ਰੁਪਏ ਜਮ੍ਹਾ ਕੀਤੇ', hi: 'रमेश ने 200 रुपये जमा किए', en: 'Ramesh paid 200 rupees',
};

for (const lang of Object.keys(CREDIT)) {
  test(`${lang}: credit then payment on a known customer, reply in ${lang}`, async () => {
    const l = new Ledger(); l.addCustomer('Ramesh');
    const a = await run(l, CREDIT[lang], lang);
    assert.equal(l.balance(1), 500, `credit not booked: ${a.replies}`);
    const b = await run(l, PAYMENT[lang], lang);
    assert.equal(l.balance(1), 300, `payment not booked: ${b.replies}`);
    if (lang !== 'hi') assert.notEqual(a.replies[0], t('hi', 'credit', { name: 'Ramesh', amt: 500, bal: 500 }));
  });
}

test('a brand-new name in a regional language becomes one customer, spelled in Roman', async () => {
  for (const [lang, text, want] of [['kn', 'ಸುನೀತಾ ಅವರಿಗೆ 300 ರೂಪಾಯಿ ಸಾಲ ಬರೆಯಿರಿ.', 'Sunita'], ['mr', 'सुनीताला 300 रुपये उधार लिहा।', 'Sunita'],
    ['te', 'సునీతకి 300 రూపాయలు అప్పు రాయండి.', 'Sunita'], ['gu', 'સુનીતાને 300 રૂપિયા ઉધાર લખો।', 'Sunita']]) {
    const l = new Ledger();
    await run(l, text, lang);
    assert.equal(l.customers.length, 1, `${lang}: ${JSON.stringify(l.customers)}`);
    assert.equal(l.customers[0].name, want, lang);
    assert.equal(l.balance(1), 300);
  }
});

test('questions in regional languages are queries, not entries', async () => {
  const l = new Ledger(); l.addCustomer('Ramesh');
  await run(l, CREDIT.kn, 'kn');
  assert.match((await run(l, 'ರಮೇಶ್ ಅವರ ಬಾಕಿ ಎಷ್ಟು?', 'kn')).replies[0], /500/);
  assert.match((await run(l, 'रमेश यांचे किती बाकी आहे?', 'mr')).replies[0], /500/);
  assert.match((await run(l, 'ಒಟ್ಟು ಬಾಕಿ ಎಷ್ಟು', 'kn')).replies[0], /500/);
  assert.equal(l.txns.length, 1);
});

test('an amount with no direction word asks, in the shopkeeper\'s language, and the answer books it', async () => {
  const l = new Ledger(); l.addCustomer('Ramesh');
  const q = await run(l, 'ರಮೇಶ್ ಅವರ 300 ರೂಪಾಯಿ', 'kn');
  assert.equal(q.replies[0], t('kn', 'ask_dir', { name: 'Ramesh', amt: 300 }));
  await run(l, 'ಸಾಲ', 'kn');
  assert.equal(l.balance(1), 300);
});

test('Marathi and Kannada spoken numbers, and digits of every Indic script', () => {
  assert.equal(findAmount(['पाचशे']).value, 500);
  assert.equal(findAmount(['तीनशे', 'चाळीस']).value, 340);
  assert.equal(findAmount(['ಮುನ್ನೂರ', 'ನಲವತ್ತು']).value, 340);
  assert.equal(findAmount(['ಐನೂರು']).value, 500);
  assert.equal(normalizeDigits('೫೦೦ ৩৪০ ५०० ௫௦ ౧౨ ૭૮ ੯੦ ൪'), '500 340 500 50 12 78 90 4');
});

test('the same name in every script is the same customer', () => {
  const k = nameKey('Ramesh');
  for (const n of ['रमेश', 'ರಮೇಶ್', 'ரமேஷ்', 'రమేష్', 'રમેશ', 'রমেশ', 'രമേശ്']) assert.equal(nameKey(n), k, n);
  assert.equal(displayName('ರಮೇಶ್'), 'Ramesh');
});

test('script decides the reply language; Devanagari follows the shopkeeper\'s choice', () => {
  assert.equal(detectLang('ರಮೇಶ್ 500', 'hi'), 'kn');
  assert.equal(detectLang('रमेश 500', 'mr'), 'mr');
  assert.equal(detectLang('रमेश 500', 'hi'), 'hi');
  assert.equal(detectLang('Ramesh ka 500', 'te'), 'te');
});

test('all ten languages have a Gnani STT/TTS config and a complete reply table', () => {
  assert.equal(Object.keys(LANGS).length, 10);
  assert.deepEqual([...supportedReplyLangs].sort(), Object.keys(LANGS).sort());
  const keys = Object.keys(t('hi', 'nope') === 'nope' ? { credit: 1, payment: 1, corrected: 1, nothing_to_fix: 1, today_credit: 1, today_payment: 1, no_account: 1, balance: 1, total: 1, which_first: 1, which: 1, or: 1, ask_dir: 1, whose: 1, not_understood: 1, ai: 1, wiped: 1, moved: 1, cant_move: 1, auto_note: 1, other_btn: 1 } : {});
  for (const L of supportedReplyLangs) for (const k of keys) if (!(L === 'en' && k === 'or')) assert.notEqual(t(L, k), k, `${L}.${k} missing`);
});
