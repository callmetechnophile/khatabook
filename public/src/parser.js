// Deterministic intent parser: transcript -> list of actions. No network, runs in well under 5 ms.
import { normalizeDigits, findAmount, canon, isNumberToken } from './numbers.js';
import { nameKey } from './translit.js';
import { STEMS, NAME_SUFFIXES, POSTPOS_EXTRA, HONOR_EXTRA, STOP_EXTRA, AND_WORDS } from './lang.js';

const HONOR_BASE = ['ji', 'जी', 'bhai', 'bhaiya', 'bhabhi', 'sahab', 'sahib', 'didi', 'uncle', 'aunty', 'madam', 'sir', 'seth', 'bhaisaab'];
const HONOR = new Set([...HONOR_BASE, ...HONOR_EXTRA]);

const CREDIT = ['chadha', 'chadhao', 'chadhana', 'चढ़ा', 'चढ़ाओ', 'took', 'borrowed', 'add', 'added', 'sudhaar', 'सुधार', 'maal', 'saman', 'samaan', 'सामान', 'माल', 'udhaar', 'udhar', 'उधार', 'credit', 'baaki', 'baki', 'likh', 'likhna', 'likho', 'lena', 'liya', 'le gaya', 'le gayi', 'liye', 'उधारी', 'बाकी', 'लिखो', 'लिख', 'लिया'];
const PAYMENT = ['diya', 'diye', 'de diya', 'de diye', 'dediya', 'jama', 'paid', 'pay', 'payment', 'wapas', 'chukaya', 'chuka', 'mila', 'received', 'gave', 'de gaya', 'de gayi', 'cleared', 'settle', 'दिया', 'दिये', 'दिए', 'दी', 'दे दिए', 'दे गया', 'दे गयी', 'दे गई', 'जमा', 'वापस', 'चुकाया', 'मिला', 'दे दिया'];
const QUERY = ['kitna', 'kitni', 'kitne', 'total', 'balance', 'how much', 'batao', 'bata', 'kya hai', 'hisab', 'hisaab', 'कितना', 'कितनी', 'कितने', 'टोटल', 'बैलेंस', 'बताओ', 'बताइए', 'हिसाब'];
const CORRECTION = ['nahi', 'nahin', 'nai', 'no', 'galat', 'sorry', 'matlab', 'actually', 'correction', 'nhi', 'नहीं', 'नही', 'गलत', 'मतलब'];
// Words that hint at a ledger entry without saying credit/payment outright (e.g. "atta chadha do"): Qwen may read these.
const WEAK_CUES = ['chadha', 'chadhao', 'chadhana', 'khate', 'khata', 'ration', 'atta', 'doodh', 'चढ़ा', 'चढ़ाओ', 'खाते', 'खाता', 'राशन', 'आटा', 'दूध'];
const POSTPOS = ['ka', 'ki', 'ke', 'ko', 'se', 'ne', 'का', 'की', 'के', 'को', 'से', 'ने'];
// "don't add 500", "udhaar mat dena": the shopkeeper is saying NOT to write something. Never book those.
const NEGATE = /(^|\s)(mat|dont|don t|do not|never|na karo|na likho|मत)(\s|$)/u;
const CONTRAST = ['lekin', 'magar', 'par', 'but', 'however', 'लेकिन', 'मगर', 'पर'];
const OTHER = ['doosra', 'dusra', 'dusre', 'doosre', 'other'];
const STOP = new Set([...OTHER, ...STOP_EXTRA, 'doosra', 'dusra', 'कल', 'आज', 'परसों', 'नरसों', 'और', 'है', 'था', 'थी', 'थे', 'रुपये', 'रुपए', 'रूपये', 'टोटल', 'कितना', 'उधार', 'उधारी', 'दिया', 'जमा', 'बाकी', 'नहीं', 'नही', 'गलत', 'मतलब', 'कुल', 'सब', 'सबका', 'kul', 'sab', 'saara', 'sabka', 'aaj', 'kal', 'parso', 'narso', 'ka', 'ki', 'ke', 'ko', 'ne', 'se', 'ek', 'aur', 'and', 'ye', 'woh', 'wo', 'hai', 'tha', 'thi', 'the', 'rupaye', 'rupee', 'rupees', 'rs', 'ruppe', 'total', 'kitna', 'udhaar', 'udhar', 'diya', 'diye', 'jama', 'baaki', 'likh', 'likho', 'nahi', 'nahin', 'no', 'sorry', 'matlab', 'actually', 'galat', 'balance', 'batao', 'bata', 'how', 'much', 'the', 'is', 'of', 'for', 'to'].map(canon));

export function normalize(text) {
  return canon(normalizeDigits(text).replace(/[\u200b-\u200d\u2060\ufeff\u00ad]/g, '')) // invisible zero-width marks Prisma may emit
    .toLowerCase()
    .replace(/₹|(?<![\p{L}])rs\.?(?![\p{L}])/gu, ' ')
    .replace(/(\d),(\d{3})/g, '$1$2')
    // "2.5 hazaar" = 2500 and "250.50" = 250 rupees: a dot between digits must not end the sentence
    .replace(/(\d+)\.(\d+)\s*(hazaar|hazar|thousand|lakh|lac|हज़ार|हजार|लाख)/gu, (m, a, b, w) => `${Math.round(parseFloat(`${a}.${b}`) * (/^(lakh|lac|लाख)$/.test(w) ? 100000 : 1000))}`)
    .replace(/(\d+)\.\d+/g, '$1')
    .replace(/[।|]/g, '.')
    .replace(/[^\p{L}\p{M}\p{N}\s,.;]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function hasAny(s, words) {
  return words.some((w) => new RegExp(`(^|\\s)${canon(w)}(\\s|$)`, 'u').test(s));
}

// Prefix match for the regional languages, which glue endings onto words (ಕೊಟ್ಟರು, ಕೊಟ್ಟಿದ್ದಾರೆ ...).
const stemHit = (tok, stems) => stems.some((st) => tok.startsWith(canon(st)));
const hasStem = (s, stems) => s.split(' ').some((t) => stemHit(t, stems));
const isRegional = (lang) => !!lang && lang !== 'hi' && lang !== 'en';
const keywordTok = (t) => ['credit', 'payment', 'query', 'queryStrong', 'correction', 'other'].some((k) => stemHit(t, STEMS[k]));
// Case ending glued to a regional-language name: "रमेशला" -> "रमेश", "ರಮೇಶನಿಗೆ" -> "ರಮೇಶ"
const CANON_SUFFIXES = NAME_SUFFIXES.map(canon);
export function stripSuffix(tok) {
  if (!/[\u0900-\u0d7f]/.test(tok)) return tok;
  for (const sfx of CANON_SUFFIXES) if (tok.length - sfx.length >= 2 && tok.endsWith(sfx)) return tok.slice(0, tok.length - sfx.length);
  return tok;
}

export function relativeDate(clause, now = new Date()) {
  let off = null;
  if (/(^|\s)(narso|नरसों)(\s|$)/.test(clause)) off = -3;
  else if (/(^|\s)(parso|परसों)(\s|$)/.test(clause)) off = -2;
  else if (/(^|\s)(kal|yesterday|कल)(\s|$)/.test(clause)) off = -1;
  else if (/(^|\s)(aaj|today|abhi|आज)(\s|$)/.test(clause)) off = 0;
  if (off === null) {
    const toks = clause.split(' ');
    if (toks.some((t) => STEMS.yesterday.map(canon).includes(t))) off = -1;
    else if (toks.some((t) => STEMS.today.map(canon).includes(t))) off = 0;
  }
  if (off === null) return null;
  const d = new Date(now);
  d.setDate(d.getDate() + off);
  return d.toISOString().slice(0, 10);
}

// Known customers (hotword list) are matched first; otherwise the word before ka/ki/ke/ko.
export function findCustomer(clause, known = [], lang = 'hi') {
  const reg = isRegional(lang);
  const toks = clause.split(' ').filter((t) => !HONOR.has(t));
  const isName = (t) => t && /^\p{L}[\p{L}\p{M}]*$/u.test(t) && !STOP.has(t) && !isNumberToken(canon(t)) && !(reg && keywordTok(t));
  const strip = (t) => (reg ? stripSuffix(t) : t);
  const POST = reg ? POSTPOS.concat(POSTPOS_EXTRA.map(canon)) : POSTPOS;
  // A spoken full name ("ramesh gupta ka") that matches nobody is a NEW customer, not the first-name match.
  for (let i = 2; i < toks.length; i++) {
    if (POST.includes(toks[i]) && isName(toks[i - 1]) && isName(toks[i - 2])) {
      const full = `${toks[i - 2]} ${toks[i - 1]}`;
      const exact = known.find((k) => nameKey(k.name) === nameKey(full));
      if (exact) return { matches: [exact], name: null };
      return { matches: [], name: full };
    }
  }
  const hits = [];
  for (const k of known) {
    const first = k.name.toLowerCase().split(' ')[0];
    const fk = nameKey(first);
    if (toks.some((t) => isName(t) && (nameKey(t) === fk || nameKey(strip(t)) === fk)) || clause.includes(k.name.toLowerCase())) hits.push(k);
  }
  if (!hits.length) { // nickname / surname only: "Doodhwala ka 200 udhaar" -> Rahul Doodhwala, if exactly one customer has that word
    const nameToks = toks.filter((t) => isName(t));
    if (nameToks.length === 1 && nameToks[0].length >= 4) {
      const key = nameKey(strip(nameToks[0]));
      const bySurname = known.filter((k) => k.name.split(' ').slice(1).some((w) => nameKey(w) === key));
      if (bySurname.length) return { matches: bySurname, name: null };
    }
  }
  if (hits.length) {
    const typed = hits.filter((k) => clause.includes(k.name.toLowerCase()));
    return { matches: typed.length ? typed : hits, name: null };
  }
  for (let i = 1; i < toks.length; i++) {
    if (POST.includes(toks[i]) && isName(toks[i - 1])) {
      // keep up to two name words: "ramesh kumar ka" -> "ramesh kumar"
      const prev = toks[i - 2];
      const two = isName(prev);
      return { matches: [], name: two ? `${prev} ${strip(toks[i - 1])}` : strip(toks[i - 1]) };
    }
  }
  const first = toks.find((t) => isName(t));
  return { matches: [], name: first && toks.indexOf(first) === 0 ? strip(first) : null };
}

function titleCase(s) {
  return s ? s.replace(/(^|\s)(\p{L})/gu, (m, a, b) => a + b.toUpperCase()) : s;
}

function splitClauses(s) {
  const parts = s.split(new RegExp(`\\s*[,;.]\\s*|\\s+(?:aur|and|phir|then|और|फिर|${AND_WORDS.join('|')})\\s+`, 'u')).map((c) => c.trim()).filter(Boolean);
  // "मोहन, का सौ रुपए ..." : a comma between a name and its postposition does not start a new entry.
  for (let i = 1; i < parts.length; i++) {
    if (POSTPOS.includes(parts[i].split(' ')[0]) && parts[i - 1].split(' ').length <= 2) { parts[i] = `${parts[i - 1]} ${parts[i]}`; parts[i - 1] = ''; }
  }
  const joined = parts.filter(Boolean);
  parts.length = 0; parts.push(...joined);
  // A short garbled tail with a direction cue ("... तीन सौ चालीस। के सुधार।") belongs to the clause before it.
  for (let i = 1; i < parts.length; i++) {
    const prev = parts[i - 1];
    if (prev && parts[i].split(' ').length <= 3 && !findAmount(parts[i].split(' ')) && findAmount(prev.split(' '))
        && !hasAny(prev, CREDIT.concat(PAYMENT)) && hasAny(parts[i], CREDIT.concat(PAYMENT))) { parts[i] = `${prev} ${parts[i]}`; parts[i - 1] = ''; }
  }
  const joined2 = parts.filter(Boolean);
  parts.length = 0; parts.push(...joined2);
  // A bare correction word ("nahi, 430") belongs to the clause that follows it.
  const out = [];
  for (let i = 0; i < parts.length; i++) {
    if (CORRECTION.includes(parts[i]) && i + 1 < parts.length) { parts[i + 1] = `${parts[i]} ${parts[i + 1]}`; continue; }
    out.push(parts[i]);
  }
  return out;
}

// A sentence like "X ka 500 udhaar kal 200 diya tha" holds two entries with no comma: cut between amounts.
function splitByAmounts(clause) {
  const toks = clause.split(' ');
  const runs = [];
  for (let pos = 0; pos < toks.length;) {
    const f = findAmount(toks.slice(pos));
    if (!f) break;
    runs.push({ start: pos + f.start, end: pos + f.end });
    pos += f.end;
  }
  if (runs.length < 2) return [clause];
  // "udhaar 300 nahi 350" / "500 diye the lekin abhi sirf 300 diye": the later amount replaces the earlier one.
  const swap = new Set([...CORRECTION, ...CONTRAST].map(canon));
  for (let k = runs.length - 2; k >= 0; k--) {
    const between = toks.slice(runs[k].end, runs[k + 1].start);
    if (between.some((w) => swap.has(w)) && !CORRECTION.includes(toks[runs[k].start - 1])) {
      const drop = runs[k + 1].start - runs[k].start; toks.splice(runs[k].start, drop);
      for (let j = k + 1; j < runs.length; j++) { runs[j].start -= drop; runs[j].end -= drop; }
      runs.splice(k, 1);
    }
  }
  if (runs.length < 2) return [toks.join(' ')];
  const kw = new Set([...CREDIT, ...PAYMENT, 'diya'].flatMap((w) => canon(w).split(' ')));
  const dates = new Set(['kal', 'parso', 'narso', 'aaj', 'कल', 'परसों', 'नरसों', 'आज']);
  const out = []; let from = 0;
  for (let k = 0; k < runs.length - 1; k++) {
    let cut = -1;
    for (let i = runs[k].end; i < runs[k + 1].start; i++) if (kw.has(toks[i]) || stemHit(toks[i], STEMS.credit) || stemHit(toks[i], STEMS.payment)) cut = i + 1;
    if (cut < 0) { cut = runs[k + 1].start; if (cut > from && dates.has(toks[cut - 1])) cut -= 1; }
    out.push(toks.slice(from, cut).join(' '));
    from = cut;
  }
  out.push(toks.slice(from).join(' '));
  return out.filter(Boolean);
}

// parse(): returns { actions: [...], needs_llm: bool }
// action kinds: credit | payment | correction | query_total | query_today | query_customer | clarify
export function parse(text, { customers = [], now = new Date(), lang = 'hi' } = {}) {
  const s = normalize(text);
  if (!s) return { actions: [], needs_llm: false };
  const clauses = splitClauses(s).flatMap(splitByAmounts);
  const actions = [];
  let lastCustomer = null;
  let unknown = false;
  const unknownClauses = [];

  const dirOf = (c) => { const pay = hasAny(c, PAYMENT) || hasStem(c, STEMS.payment), cred = hasAny(c, CREDIT) || hasStem(c, STEMS.credit); return pay === cred ? null : (cred ? 'credit' : 'payment'); };
  for (let ci = 0; ci < clauses.length; ci++) {
    const clause = clauses[ci];
    const toks = clause.split(' ');
    if (!isRegional(lang) && NEGATE.test(clause)) { actions.push({ kind: 'skip' }); continue; }
    const amt = findAmount(toks);
    const date = relativeDate(clause, now);
    const corrWord = hasAny(clause, CORRECTION) || hasStem(clause, STEMS.correction);
    // "teen sau nahi": the word AFTER the amount says the amount is wrong. Do not turn it into a correction to that amount.
    if (amt && !isRegional(lang) && hasAny(clause, CORRECTION) && !toks.slice(0, amt.start).some((w) => CORRECTION.includes(w)) && toks.slice(amt.end).some((w) => CORRECTION.includes(w)) && !hasAny(clause, QUERY)) { actions.push({ kind: 'skip' }); continue; }
    const isCorrection = corrWord && amt;
    const isQuery = (hasAny(clause, QUERY) && !(amt && !hasAny(clause, ['kitna', 'how much'])))
      || (hasStem(clause, STEMS.query) && !(amt && !hasStem(clause, STEMS.queryStrong)));
    const cust = findCustomer(clause, customers, lang);
    let customerRef = cust;
    if (!cust.matches.length && !cust.name && lastCustomer) customerRef = lastCustomer;
    else if (cust.matches.length || cust.name) lastCustomer = cust;

    if (!amt && !isQuery && (hasAny(clause, OTHER) || hasStem(clause, STEMS.other)) && !hasAny(clause, CREDIT.concat(PAYMENT))) {
      actions.push({ kind: 'reassign', customer: customerRef }); // "nahi, doosra Rahul"
      continue;
    }
    if (isCorrection) {
      actions.push({ kind: 'correction', amount: amt.value, customer: customerRef });
      continue;
    }
    if (isQuery) {
      const wantsToday = /(^|\s)(aaj|today|आज)(\s|$)/.test(clause) && !(customerRef.matches.length || customerRef.name);
      actions.push(wantsToday
        ? { kind: 'query_today', direction: hasAny(clause, PAYMENT.concat(['aaya', 'wapas'])) ? 'payment' : 'credit' }
        : (customerRef.matches.length || customerRef.name)
          ? { kind: 'query_customer', customer: customerRef }
          : { kind: 'query_total' });
      continue;
    }
    if (amt) {
      const pay = hasAny(clause, PAYMENT) || hasStem(clause, STEMS.payment);
      const cred = hasAny(clause, CREDIT) || hasStem(clause, STEMS.credit);
      if (!pay && !cred) {
        // "chaar sau tees" with no verb and no name: Prisma often drops the leading "nahi", so treat it as a correction.
        if (!(customerRef.matches.length || customerRef.name)) actions.push({ kind: 'correction', amount: amt.value, customer: customerRef });
        else if (!hasAny(clause, WEAK_CUES)) {
          // "Sunita ne kal 200 aur aaj 300 diye": the verb in the next clause covers this one too.
          const nx = clauses[ci + 1], nd = nx && dirOf(nx);
          const nc = nx && findCustomer(nx, customers, lang);
          if (nd && !nc.matches.length && !nc.name) {
            actions.push({ kind: nd, amount: amt.value, customer: customerRef, date: date || now.toISOString().slice(0, 10), raw: clause }); continue;
          }
          actions.push({ kind: 'ask_direction', amount: amt.value, customer: customerRef, date: date || now.toISOString().slice(0, 10) });
        }
        else { unknownClauses.push(clause); unknown = true; }
        continue;
      }
      // "diya" with "udhaar" ("udhaar diya") means shopkeeper gave credit -> credit
      const direction = cred && !(pay && !/udhaar diya|udhar diya|credit diya/.test(clause) && (/(^|\s)(jama|wapas|chukaya|paid|mila|received)(\s|$)/.test(clause) || hasStem(clause, STEMS.strongPay))) ? 'credit' : 'payment';
      actions.push({ kind: direction, amount: amt.value, customer: customerRef, date: date || now.toISOString().slice(0, 10), raw: clause });
      continue;
    }
    if (actions.length && !isQuery) continue; // trailing filler like "likh lena", "baad me denge"
    unknownClauses.push(clause); unknown = true;
  }
  return { actions, needs_llm: unknown || actions.length === 0, unknown_clauses: unknownClauses };
}

export function hasPaymentCue(text) { return hasAny(normalize(text), PAYMENT); }
export function looksLikeQuery(text) { return hasAny(normalize(text), QUERY); }
export { titleCase };
