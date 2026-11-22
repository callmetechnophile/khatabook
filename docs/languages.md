# Languages

KHATAWAS speaks and understands **ten languages**: Hindi (and Hinglish), English, Kannada, Marathi, Tamil, Telugu, Gujarati, Bengali, Malayalam and Punjabi. These are exactly the ten languages Gnani lists for both Prisma v3.0 (speech to text) and Timbre v3.0 (text to speech) in [docs.gnani.ai](https://docs.gnani.ai). Gnani lists no Odia, Assamese or Urdu, so those cannot be added until Gnani adds them.

## How a language works

| Piece | What it does | Where |
| --- | --- | --- |
| Speech to text | Prisma runs with the chosen `language_code` (or `indic-auto`, which detects it). Hindi keeps `format=codemix`; every other language uses `format=transcribe`, which returns amounts as digits ("500 रुपये"), so money never depends on parsing a spoken number word. | `server/gnani.js`, `src/lang.js` |
| Understanding | One parser for all languages. Credit, payment, query and correction words are matched by stem (these languages glue endings onto words). Case endings glued to names ("रमेशला", "ರಮೇಶನಿಗೆ") are stripped. Digits of every Indic script are read. | `src/parser.js`, `src/lang.js` |
| Names | Any script is romanised through one shared table, so "रमेश", "ರಮೇಶ್", "ரமேஷ்" and "Ramesh" are the same customer. | `src/translit.js` |
| Replies | Short spoken sentences per language, in that language's own script (Hindi stays Roman Hinglish, as before). | `src/i18n.js` |
| Text to speech | Timbre with the same language code and a recommended voice from Gnani's catalogue. | `server/gnani.js`, `src/lang.js` |

The shopkeeper picks a language from the chip at the top right, or "Auto-detect": Prisma reports which language it heard and the reply comes back in it.

## What was verified live (Prisma v3.0 `transcribe`)

Each sentence was spoken by Timbre, sent to Prisma in the same language, and parsed. All ten returned a transcript with the amount as digits and the right language code.

| Language | Prisma heard |
| --- | --- |
| Kannada | ರಮೇಶ್ ಅವರಿಗೆ 500 ರೂಪಾಯಿ ಸಾಲ ಬರೆಯಿರಿ. |
| Marathi | रमेशला 500 रुपये उधार लिहा। |
| Tamil | ரமேஷுக்கு 500 ரூபாய் கடன் எழுதுங்கள். |
| Telugu | రమేష్కి 500 రూపాయలు అప్పు రాయండి. |
| Gujarati | રમેશને 500 રૂપિયા ઉધાર લખો। |
| Bengali | রমেশকে 500 টাকা বাকি লিখো। |
| Malayalam | രമേശിന് 500 രൂപ കടം എഴുതുക. |
| Punjabi | ਰਮੇਸ਼ ਨੂੰ 500 ਰੁਪਏ ਉਧਾਰ ਲਿਖੋ। |

The automated tests (`test/languages.test.js`) run these exact transcripts through the parser and ledger.

## Honest limits

- The test voices are synthetic (Timbre), spoken one sentence at a time. Real shop speech, accents and noise were **not** tested outside Hindi/Hinglish.
- The reply sentences in the nine non-Hinglish languages were drafted by an AI. A native speaker should read them once before a real store relies on them.
- Spoken number words (instead of digits) are understood for Hindi, English, Marathi and Kannada only. The other languages rely on Prisma returning digits, which it did in every run.
- Tamil writes "ka" and "ha" with the same letter, so a name like "Rahul" can be heard as "Rakul" and fail to match until it is added once by hand.
- Prisma may still return a name in the other script than the one you typed. Matching is phonetic, but an unusual spelling can create a second customer.

## Adding a language

1. If Gnani supports it, add one line to `LANGS` in `src/lang.js` (`stt`, `format`, `tts`, `voice`).
2. Add its credit, payment, query, correction words (stems) to `STEMS`, and its case endings to `NAME_SUFFIXES`.
3. Add its reply table to `src/i18n.js`.
4. Add the language to the picker in `public/app.js`, capture one real Prisma transcript, and add it to `test/languages.test.js`.

## Two customers with the same name

"Rahul ne 500 diye" must not make the shopkeeper do the work. The ledger decides in this order and asks only if all three fail:

1. **A payment can only come from someone who owes at least that much.** If one Rahul owes 800 and the other 150, a 500 payment is the first one's.
2. **Who was just discussed.** If exactly one of the Rahuls had an entry in this conversation (2 minutes for a credit, 5 for a payment), it is that one. If both did, it asks.
3. **A regular customer** (3 or more entries in 30 days) against someone with none.

Otherwise it asks one short question in the shopkeeper's language ("Kaun sa Rahul? Rahul Sharma ya Rahul Doodhwala?") and the answer can be a single word. Every automatic pick is spoken back with the full name and shown with the reason, and "nahi, doosra Rahul" (or the button) moves the entry to the other one. Credits use a shorter window than payments because a wrong credit invents a debt. A name that already exists exactly cannot be added twice; the app asks for a surname or landmark.
