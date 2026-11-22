// Any Indic script -> rough Roman, and a phonetic "skeleton" so "मोहन", "ರಮೇಶ್" and "Mohan"/"Ramesh" are the same customer.
// The Indic Unicode blocks share one layout, so every script is shifted onto Devanagari and romanised from there.
const IND = { 'अ': 'A', 'आ': 'a', 'इ': 'i', 'ई': 'i', 'उ': 'u', 'ऊ': 'u', 'ए': 'e', 'ऐ': 'ai', 'ओ': 'o', 'औ': 'au', 'ऑ': 'o', 'ऋ': 'ri', 'ऎ': 'e', 'ऒ': 'o' };
const MAT = { 'ा': 'a', 'ि': 'i', 'ी': 'i', 'ु': 'u', 'ू': 'u', 'े': 'e', 'ै': 'ai', 'ो': 'o', 'ौ': 'au', 'ॉ': 'o', 'ृ': 'ri', 'ॆ': 'e', 'ॊ': 'o' };
const CON = { 'क': 'k', 'ख': 'kh', 'ग': 'g', 'घ': 'gh', 'ङ': 'n', 'च': 'ch', 'छ': 'chh', 'ज': 'j', 'झ': 'jh', 'ञ': 'n', 'ट': 't', 'ठ': 'th', 'ड': 'd', 'ढ': 'dh', 'ण': 'n', 'त': 't', 'थ': 'th', 'द': 'd', 'ध': 'dh', 'न': 'n', 'प': 'p', 'फ': 'ph', 'ब': 'b', 'भ': 'bh', 'म': 'm', 'य': 'y', 'र': 'r', 'ल': 'l', 'व': 'v', 'श': 'sh', 'ष': 'sh', 'स': 's', 'ह': 'h', 'ळ': 'l', 'ऩ': 'n', 'ऱ': 'r', 'ऴ': 'l' };

const SHIFT = [[0x980, 0x9ff, 0x80], [0xa00, 0xa7f, 0x100], [0xa80, 0xaff, 0x180], [0xb00, 0xb7f, 0x200], [0xb80, 0xbff, 0x280], [0xc00, 0xc7f, 0x300], [0xc80, 0xcff, 0x380], [0xd00, 0xd7f, 0x400]];
// Malayalam "chillu" letters (ൻ ർ ൽ ൾ ൺ ൿ) are single code points with no Devanagari twin: spell them as consonant + virama first.
const CHILLU = { 'ൻ': 'ന്', 'ർ': 'ര്', 'ൽ': 'ല്', 'ൾ': 'ള്', 'ൺ': 'ണ്', 'ൿ': 'ക്' };
export function toDeva(word) {
  return [...word.normalize('NFC').replace(/[\u0d7a-\u0d7f]/g, (c) => CHILLU[c] || c)].map((c) => {
    const cp = c.codePointAt(0), r = SHIFT.find(([a, b]) => cp >= a && cp <= b);
    return r ? String.fromCodePoint(cp - r[2]) : c;
  }).join('');
}

export function toRoman(word) {
  const southern = /[\u0c00-\u0c7f\u0c80-\u0cff\u0d00-\u0d7f]/.test(word); // Telugu, Kannada, Malayalam pronounce the final "a" (Sunita, not Sunit)
  const w = toDeva(word).replace(/़/g, '');
  let out = '';
  const ch = [...w];
  for (let i = 0; i < ch.length; i++) {
    const c = ch[i];
    if (CON[c]) {
      out += CON[c];
      const n = ch[i + 1];
      if (n && MAT[n]) { out += MAT[n]; i++; } else if (n === '्') i++; else out += '\u0001'; // inherent vowel marker
    } else if (IND[c]) out += IND[c] === 'A' ? 'a' : IND[c];
    else if (c === 'ं' || c === 'ँ') out += 'n';
    else if (c === 'ः') out += 'h';
    else if (!/[\u0900-\u097f]/.test(c)) out += c.toLowerCase();
  }
  out = out.replace(/\u0001$/, southern ? 'a' : '').replace(/\u0001/g, 'a'); // drop word-final schwa (Hindi style)
  return out;
}
export const isDevanagari = (s) => /[\u0900-\u0d7f]/.test(s); // any Indic script (name kept for callers)

export function skeleton(word) {
  return toRoman(word).toLowerCase().replace(/ph/g, 'f').replace(/w/g, 'v').replace(/[aeiouyh]/g, '').replace(/(.)\1+/g, '$1');
}
export const nameKey = (name) => name.toLowerCase().split(/\s+/).filter(Boolean).map(skeleton).join(' ');
export function displayName(name) {
  return name.split(/\s+/).map((w) => { const r = isDevanagari(w) ? toRoman(w) : w; return r.charAt(0).toUpperCase() + r.slice(1); }).join(' ');
}
