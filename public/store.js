// Encrypted-at-rest ledger: AES-256-GCM via WebCrypto. The key is a NON-extractable CryptoKey kept in
// IndexedDB (the browser analogue of Android Keystore), so the raw key bytes are never readable by JS.
const DB = 'khatawas', STORE = 'kv';
const open = () => new Promise((res, rej) => {
  const r = indexedDB.open(DB, 1);
  r.onupgradeneeded = () => r.result.createObjectStore(STORE);
  r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
});
const get = async (k) => { const db = await open(); return new Promise((res, rej) => { const q = db.transaction(STORE).objectStore(STORE).get(k); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); }); };
const put = async (k, v) => { const db = await open(); return new Promise((res, rej) => { const t = db.transaction(STORE, 'readwrite'); t.objectStore(STORE).put(v, k); t.oncomplete = res; t.onerror = () => rej(t.error); }); };
const del = async (k) => { const db = await open(); return new Promise((res) => { const t = db.transaction(STORE, 'readwrite'); t.objectStore(STORE).delete(k); t.oncomplete = res; }); };

async function key() {
  let k = await get('key');
  if (!k) { k = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']); await put('key', k); }
  return k;
}
export async function saveLedger(obj) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = new TextEncoder().encode(JSON.stringify(obj));
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await key(), data);
  await put('ledger', { iv, ct });
}
export async function loadLedger() {
  const rec = await get('ledger');
  if (!rec) return null;
  try { return JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: rec.iv }, await key(), rec.ct))); }
  catch { return null; }
}
// "Delete after judging": wipes both the ciphertext and the key.
export async function wipeAll() { await del('ledger'); await del('key'); }
