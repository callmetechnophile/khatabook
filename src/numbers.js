// Spoken-number parsing for Hindi / Hinglish / English. Returns an integer or null.
const SMALL = {};
const hi = ['ek','do','teen','chaar','paanch','chhe','saat','aath','nau','das','gyarah','barah','terah','chaudah','pandrah','solah','satrah','atharah','unnis','bees',
'ikkis','baais','teis','chaubis','pachchis','chhabbis','sattais','atthais','untis','tees',
'ikattis','battis','taintis','chauntis','paintis','chhattis','saintis','adtis','untalis','chaalis',
'iktalis','bayalis','taintalis','chauwalis','paintalis','chiyalis','saintalis','adtalis','unchas','pachas',
'ikyavan','bavan','trepan','chauvan','pachpan','chappan','sattavan','atthavan','unsath','saath',
'iksath','basath','tresath','chausath','painsath','chhiyasath','sarsath','arsath','unhattar','sattar',
'ikhattar','bahattar','tihattar','chauhattar','pachhattar','chhihattar','satattar','athattar','unasi','assi',
'ikyasi','bayasi','tirasi','chauraasi','pachasi','chhiyasi','sattasi','atthasi','navasi','nabbe',
'ikyanve','bayanve','tiranve','chauranve','pachanve','chhiyanve','sattanve','atthanve','ninyanve'];
hi.forEach((w, i) => { SMALL[w] = i + 1; });
Object.assign(SMALL, {
  teen3: 3, tin: 3, char: 4, panch: 5, chhah: 6, che: 6, chha: 6, sath: 7, ath: 8, no: null,
  chalis: 40, chaalees: 40, pachaas: 50, saath_: 60, sattar_: 70, assi_: 80, nabbe_: 90,
  bis: 20, bees_: 20, tis: 30, tees_: 30, pachees: 25, pacchis: 25, pachis: 25,
  // English
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17,
  eighteen: 18, nineteen: 19, twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70,
  eighty: 80, ninety: 90,
});
delete SMALL.no; delete SMALL.saath_; delete SMALL.sattar_; delete SMALL.assi_; delete SMALL.nabbe_;
delete SMALL.bees_; delete SMALL.tees_; delete SMALL.teen3;
const MULT = { [canon('सौ')]: 100, [canon('हज़ार')]: 1000, [canon('हजार')]: 1000, [canon('लाख')]: 100000, sau: 100, sao: 100, hundred: 100, hazaar: 1000, hazar: 1000, thousand: 1000, lakh: 100000, lac: 100000 };
const FRAC = { dedh: 150, dhai: 250, dhaai: 250, [canon('डेढ़')]: 150, [canon('डेढ')]: 150, [canon('ढाई')]: 250 }; // dedh sau = 150, dhai sau = 250
const SAADE = { saade: 0.5, [canon('सादे')]: 0.5, [canon('साडे')]: 0.5, [canon('साढ़े')]: 0.5, [canon('साढे')]: 0.5 }; // saade teen sau = 350

const ADJ = { sava: 0.25, [canon('सवा')]: 0.25, paune: -0.25, [canon('पौने')]: -0.25, [canon('पौन')]: -0.25 }; // sava sau = 125, paune do sau = 175

const DEV = '०१२३४५६७८९';

// Canonical form for matching Devanagari: NFC, drop nukta, chandrabindu -> anusvara.
export function canon(t) {
  return t.normalize('NFC').normalize('NFD').replace(/\u093c/g, '').normalize('NFC').replace(/\u0901/g, '\u0902');
}
const DEV_NUM = ('एक दो तीन चार पांच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस ' +
 'इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस चालीस ' +
 'इकतालीस बयालीस तैंतालीस चौवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास पचास इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ साठ ' +
 'इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उनासी अस्सी ' +
 'इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे इक्यानवे बानवे तिरानवे चौरानवे पचानवे छियानवे सत्तानवे अट्ठानवे निन्यानवे').split(' ');
// Digit blocks of every Indic script Prisma can return (Devanagari, Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam)
const DIGIT_BLOCKS = [0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6, 0xc66, 0xce6, 0xd66];
export function normalizeDigits(s) {
  return s.replace(/[\u0966-\u096f\u09e6-\u09ef\u0a66-\u0a6f\u0ae6-\u0aef\u0b66-\u0b6f\u0be6-\u0bef\u0c66-\u0c6f\u0ce6-\u0cef\u0d66-\u0d6f]/g, (d) => {
    const cp = d.codePointAt(0);
    const base = DIGIT_BLOCKS.find((b) => cp >= b && cp <= b + 9);
    return String(cp - base);
  });
}

export function isNumberToken(t) {
  return /^\d+$/.test(t) || t in SMALL || t in MULT || t in FRAC || t in SAADE || t in ADJ;
}

// Parse a run of number tokens into a value. Handles "teen sau chaalis", "three forty",
// "do hazaar paanch sau", "saade teen sau", "dedh sau", "1 lakh".
export function parseNumberRun(tokens) {
  let total = 0, cur = 0, sawMult = false, saade = false, adj = 0, lastSmall = null;
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (/^\d+$/.test(t)) {
      const v = Number(t);
      cur += v; lastSmall = v; continue;
    }
    if (t in SAADE) { saade = true; continue; }
    if (t in ADJ) { adj = ADJ[t]; continue; }
    if (t in FRAC) {
      const nx = tokens[i + 1];
      if (nx && nx in MULT && MULT[nx] >= 1000) { total += (FRAC[t] / 100) * MULT[nx]; cur = 0; sawMult = false; i++; continue; } // dedh hazaar = 1500
      cur = FRAC[t]; sawMult = true; if (nx === 'sau' || nx === canon('सौ')) i++; continue;
    }
    if (t in MULT) {
      const m = MULT[t];
      if (saade) { cur = (cur + 0.5) * m; saade = false; sawMult = true; continue; }
      if (adj) { cur = ((cur || 1) + adj) * m; adj = 0; sawMult = true; continue; }
      if (m === 100) { cur = (cur || 1) * 100; sawMult = true; }
      else { total += (cur || 1) * m; cur = 0; sawMult = false; }
      continue;
    }
    if (t in SMALL) {
      const v = SMALL[t];
      // colloquial "three forty" => 340 (single digit followed by 20..99, no multiplier yet)
      if (!sawMult && cur >= 1 && cur <= 9 && v >= 20 && lastSmall !== null && lastSmall <= 9) {
        cur = cur * 100 + v;
      } else cur += v;
      lastSmall = v; continue;
    }
  }
  const out = total + cur;
  return Number.isFinite(out) && out > 0 ? Math.round(out) : null;
}

// Find the first number in a token list; returns { value, start, end } or null.
const DO_VERBS = new Set(['kar', 'karo', 'de', 'dedo', 'chadha', 'chadhao', 'likh', 'likho', 'rakh', 'rakho', 'daal', 'dal', 'lagao', 'lga', 'कर', 'करो', 'दे', 'चढ़ा', 'चढ़ाओ', 'लिख', 'लिखो', 'रख', 'डाल', 'लगा', 'लगाओ'].map(canon));
const isDo = (t) => t === 'do' || t === canon('दो');
export function findAmount(tokens) {
  tokens = tokens.map(canon);
  const num = (i) => isNumberToken(tokens[i]) && !(isDo(tokens[i]) && DO_VERBS.has(tokens[i - 1])); // "kar do" != 2
  for (let i = 0; i < tokens.length; i++) {
    if (!num(i)) continue;
    let j = i;
    while (j < tokens.length && num(j)) j++;
    const value = parseNumberRun(tokens.slice(i, j));
    if (value) return { value, start: i, end: j };
    i = j - 1;
  }
  return null;
}

// Devanagari entries (added last: DEV_NUM is defined above this point in the file)
DEV_NUM.forEach((w, i) => { SMALL[canon(w)] = i + 1; });
Object.assign(SMALL, { [canon('पाँच')]: 5, [canon('छः')]: 6, [canon('छे')]: 6, [canon('पन्द्रह')]: 15, [canon('पंद्रह')]: 15, [canon('छ')]: 6 });

// Marathi and Kannada spoken numbers. Prisma's `transcribe` format already returns digits, so these only matter for typed
// input and for the rare transcript that still spells a number out. Round numbers are added as plain values and summed.
const mk = (obj) => Object.entries(obj).forEach(([w, v]) => { SMALL[canon(w)] = v; });
mk({ // Marathi
  'एक': 1, 'दोन': 2, 'तीन': 3, 'चार': 4, 'पाच': 5, 'सहा': 6, 'सात': 7, 'आठ': 8, 'नऊ': 9, 'दहा': 10, 'अकरा': 11, 'बारा': 12, 'पंधरा': 15, 'वीस': 20, 'पंचवीस': 25, 'तीस': 30, 'चाळीस': 40, 'पन्नास': 50, 'साठ': 60, 'सत्तर': 70, 'ऐंशी': 80, 'नव्वद': 90,
  'शंभर': 100, 'दोनशे': 200, 'तीनशे': 300, 'चारशे': 400, 'पाचशे': 500, 'सहाशे': 600, 'सातशे': 700, 'आठशे': 800, 'नऊशे': 900,
});
mk({ // Kannada
  'ಒಂದು': 1, 'ಎರಡು': 2, 'ಮೂರು': 3, 'ನಾಲ್ಕು': 4, 'ಐದು': 5, 'ಆರು': 6, 'ಏಳು': 7, 'ಎಂಟು': 8, 'ಒಂಬತ್ತು': 9, 'ಹತ್ತು': 10, 'ಹನ್ನೆರಡು': 12, 'ಹದಿನೈದು': 15, 'ಇಪ್ಪತ್ತು': 20, 'ಇಪ್ಪತ್ತೈದು': 25, 'ಮೂವತ್ತು': 30, 'ನಲವತ್ತು': 40, 'ಐವತ್ತು': 50, 'ಅರವತ್ತು': 60, 'ಎಪ್ಪತ್ತು': 70, 'ಎಂಬತ್ತು': 80, 'ತೊಂಬತ್ತು': 90,
  'ನೂರು': 100, 'ಇನ್ನೂರು': 200, 'ಇನ್ನೂರ': 200, 'ಮುನ್ನೂರು': 300, 'ಮುನ್ನೂರ': 300, 'ನಾನೂರು': 400, 'ನಾನೂರ': 400, 'ಐನೂರು': 500, 'ಐನೂರ': 500, 'ಆರುನೂರು': 600, 'ಏಳುನೂರು': 700, 'ಎಂಟುನೂರು': 800, 'ಒಂಬೈನೂರು': 900,
});
Object.assign(MULT, { 'हजार': 1000, 'लाख': 100000, 'ಸಾವಿರ': 1000, 'ಲಕ್ಷ': 100000 });
