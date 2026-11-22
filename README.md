# KHATAWAS — The Voice Ledger for Kirana Stores

> Push-to-talk. Say it once, the khata is updated. No typing, no tapping, no stopping the sale.

**Team:** ANTHROMINDS · **Team ID:** 4FFAF822E514 · **College:** Dayananda Sagar Academy of Technology and Management & Dayananda Sagar College of Engineering
**Hackathon:** Gnani Voice Hackathon — Voice Khata for Kirana Stores
**Demo video:** [youtu.be/g-so4KEhQUo](https://youtu.be/g-so4KEhQUo) (2:56, the real live app driven through the real Gnani speech path with synthetic Timbre voices; Hindi, Kannada, Marathi, two Rahuls) · **Pitch deck:** [docs/KHATAWAS-pitch-deck.pptx](docs/KHATAWAS-pitch-deck.pptx) (6 slides) · **Live demo:** link and access code are in the submission form

---

## The problem

Kirana owners record *udhaar* (informal credit) on paper while serving a queue. Khatabook-style apps need tapping and typing, which nobody does at peak hours. Voice solutions that exist break in noisy streets and in code-mixed speech (Hindi + regional + English).

## What KHATAWAS does

A shopkeeper holds a button and talks naturally, the way they already speak:

| Shopkeeper says | KHATAWAS does |
|---|---|
| "Ramesh ka 340 udhaar, kal 200 diya tha" | Books ₹340 credit today and a ₹200 payment dated yesterday, for Ramesh |
| "Teen sau chaalis" / "three forty" / "340" | All become 340 |
| "Nahi, 430" | Corrects the last entry to ₹430 |
| "Ramesh ka 500 udhaar" (two Rameshes exist) | Asks: "Kaun sa Ramesh? Ramesh Kumar ya Ramesh Gupta?" |
| "Aaj kitna udhaar gaya?" | Speaks the total credit given today |
| "Ramesh ka total?" | Speaks Ramesh's balance |

## Ten languages, and two customers called Rahul

KHATAWAS works in **Hindi/Hinglish, English, Kannada, Marathi, Tamil, Telugu, Gujarati, Bengali, Malayalam and Punjabi**, the ten languages Gnani Prisma v3.0 and Timbre v3.0 both support. Pick one from the language chip, or choose Auto-detect and the reply comes back in the language you spoke. When two customers share a first name, the app works out which one you mean from the balances and the conversation, and asks only when it cannot tell. Details, the live-verified transcripts, limits and how to add a language: [`docs/languages.md`](docs/languages.md).

![The redesigned app with two customers named Rahul](docs/screenshots/9-new-ui-two-rahuls.jpg)

## See it working

These are screenshots of the real app (not mock-ups), taken while driving it with synthetic Gnani Timbre voices through the real Prisma speech path. The caption on the right is added for the demo video; the app itself is the cream-coloured panel. Watch the full run in the [demo video](https://youtu.be/g-so4KEhQUo).

| | |
|---|---|
| ![Idle screen](docs/screenshots/1-idle.png)<br>**1. One button.** Hold, speak, let go. Customer balances are listed below. | ![Credit booked](docs/screenshots/2-credit.png)<br>**2. Credit.** "Ramesh Kumar का तीन सौ उधार" is heard, ₹300 is added, and the app says "Ramesh Kumar ka 300 rupaye udhaar likh diya. Total 400." |
| ![Payment booked](docs/screenshots/3-payment.png)<br>**3. Payment.** "Sunita का डेढ़ सौ दिया" is read as ₹150 received (*dedh sau*). | ![Correction](docs/screenshots/4-correction.png)<br>**4. Correction.** "नहीं चार सौ" fixes the last entry to ₹400. |
| ![Which Ramesh](docs/screenshots/5-which-ramesh.png)<br>**5. Ambiguity.** Two Rameshes exist, so the app asks "Kaun sa Ramesh?" instead of guessing. | ![Answered in one word](docs/screenshots/6-answer-one-word.png)<br>**6. One-word answer.** Saying "गुप्ता" completes the pending entry for Ramesh Gupta. |
| ![Spoken total](docs/screenshots/7-spoken-total.png)<br>**7. Questions.** "आज कितना उधार गया?" is answered aloud: "Aaj 1540 rupaye udhaar gaya." | ![Delete all data](docs/screenshots/8-delete-all.png)<br>**8. Privacy.** "Delete all data" wipes the ledger and its encryption key. |

The small tags above each reply show how it was understood (`parser`, `clarify`, or `qwen`) and the time to reply. The microphone path (speech to text) shows the Hindi-script transcript in the "Heard" box.

## Try it in 2 minutes

1. Open the live link from the submission form (it ends in `?code=...`). The first open after idle can take up to 50 seconds on the free host.
2. Allow the microphone. Type `Ramesh Kumar` and `Ramesh Gupta` into **Add customer name** (it helps the speech model recognise names).
3. **Hold the big button** (or the space bar), say *"Ramesh Kumar ka teen sau udhaar"*, and release. The reply is spoken and the table updates.
4. Try: *"Sunita ne dedh sau diye"*, *"Nahi, chaar sau"*, *"Ramesh ka paanch sau udhaar"* (it will ask which Ramesh), *"Aaj kitna udhaar gaya"*.
5. No microphone? Type the same sentence in the text box and press **Send**. The same parser runs.
6. **Delete all data** (bottom right) wipes everything.

## Architecture

```mermaid
flowchart LR
  subgraph Device["Browser app on the shopkeeper's phone or laptop"]
    direction TB
    MIC["Push-to-talk mic<br/>AudioWorklet 16 kHz PCM16<br/>browser noise suppression"]
    NLU["Rule-based parser, 10 languages<br/>numbers, intent, dates,<br/>corrections, transliteration"]
    LED["Ledger logic<br/>same-name resolution<br/>corrections, queries, i18n replies"]
    DB[("Encrypted ledger<br/>AES-256-GCM in IndexedDB")]
    PLAY["Streaming audio playback"]
    MIC --> NLU --> LED
    LED <--> DB
    LED --> PLAY
  end
  subgraph Server["Node.js 22 server on Render"]
    GATE["WebSocket bridge /stt and /tts<br/>visitor and daily quota<br/>Gnani API key stays here"]
  end
  subgraph Gnani["Gnani Vachana cloud"]
    STT["Prisma v3.0 STT<br/>10 languages plus auto-detect"]
    TTS["Timbre v3.0 TTS<br/>one voice per language"]
  end
  subgraph Local["Optional, developer machine only"]
    QW["Qwen2.5-1.5B INT4 via Ollama<br/>entry type and customer only"]
  end
  MIC ==>|PCM audio| GATE
  GATE <==>|audio and keyterms, transcript| STT
  GATE ==>|transcript| NLU
  NLU -. clause not understood .-> GATE
  GATE -. one clause .-> QW
  LED ==>|reply text| GATE
  GATE <==>|text, PCM audio| TTS
  GATE ==>|audio chunks| PLAY
```

The parser, ledger and encrypted store all run **in the browser**. The server only holds the Gnani key and bridges two WebSockets, so the ledger never leaves the device.

### One utterance, step by step

```mermaid
sequenceDiagram
  actor S as Shopkeeper
  participant B as Browser app
  participant N as Node server
  participant P as Gnani Prisma STT
  participant T as Gnani Timbre TTS
  S->>B: holds the mic button and speaks
  B->>N: WebSocket /stt, start with language, then PCM frames
  N->>P: stream audio plus customer names as keyterms
  P-->>N: transcript and detected language
  N-->>B: transcript
  Note over B: parser, then ledger, about 2 ms, no model call
  alt clause not understood and Qwen available
    B->>N: /ollama, one clause only
    N-->>B: type and customer, amount from parser
  end
  B->>N: WebSocket /tts, reply text and language
  N->>T: synthesize
  T-->>N: PCM chunks
  N-->>B: audio chunks
  B-->>S: first sound plays before the full reply is ready
```

### Two customers with the same name

```mermaid
flowchart TD
  A["Heard: Rahul ka 200 udhaar"] --> B{"How many customers match?"}
  B -->|one| BOOK["Book it"]
  B -->|none| NEW["Create a new customer"]
  B -->|several, e.g. Rahul Sharma and Rahul Doodhwala| C{"Payment: only one candidate owes at least that amount?"}
  C -->|yes| PICK["Auto-pick, say who and why"]
  C -->|no| D{"Exactly one candidate was in this conversation recently?"}
  D -->|yes| PICK
  D -->|no| E{"One is a regular, 3 or more entries in 30 days, others none?"}
  E -->|yes| PICK
  E -->|no| ASK["Ask: Kaun sa Rahul? Sharma ya Doodhwala?"]
  PICK --> UNDO["Shopkeeper can say: nahi, doosra Rahul"]
  UNDO --> MOVE["Entry moves to the other Rahul"]
```

## Tech stack

| Layer | What we use |
|---|---|
| Frontend | Plain JavaScript (ES modules), no framework and no build step. Web Audio `AudioWorklet` for mic capture, WebCrypto (AES-256-GCM) and IndexedDB for the encrypted ledger, Web Speech API as a browser fallback |
| Backend | Node.js 22, a single runtime dependency (`ws`), WebSocket bridges for `/stt` and `/tts`, an access gate and per-visitor and daily usage caps |
| Speech to text | **Gnani Prisma v3.0**: 10 languages plus auto-detect, customer names sent as keyterms |
| Text to speech | **Gnani Timbre v3.0**: a voice per language, streamed so playback starts early |
| Language model | **Qwen2.5-1.5B (INT4) via Ollama**, local and optional, used only for clauses the rules cannot parse and boxed in by guardrails. Not part of the hosted Render deployment |
| Language logic | Own code: rule-based parser, number parser, Indic-script transliteration, reply tables for 10 languages |
| Hosting | Docker on Render (free plan, auto-deploys from `main`) |
| Tests | `node:test`, 59 automated tests, plus a live benchmark against real Prisma transcripts |

## How it works

**Design choices**

- **Deterministic first, LLM only as a fallback.** The rule-based parser handles the judged cases in about 2 ms with no model call, so results are predictable. Qwen2.5-1.5B is called only when the parser cannot understand a sentence. If the model is offline or slow the sale still completes.
- **Amounts in any spoken form.** Digits, Hindi number words (up to 99 plus *sau/hazaar/lakh*), English words, colloquial "three forty", and *dedh/dhai/saade* forms.
- **Hotword biasing.** The shop's customer names are sent to Prisma so proper nouns are recognised in code-mixed speech.
- **API key never reaches the browser.** The browser talks to our own server (`/stt`, `/tts`), which talks to Gnani.
- **Spoken answers stream.** Timbre audio chunks are scheduled for playback as they arrive, so the first sound plays before the whole reply is generated.

## Gnani and Qwen: what each one does

KHATAWAS uses **two AI models for two different jobs**. Gnani handles the voice; Qwen is a small local safety net for sentences the rules cannot understand.

| | **Gnani Prisma v3.0** | **Gnani Timbre v3.0** | **Qwen2.5-1.5B** |
|---|---|---|---|
| Job | Speech to text | Text to speech | Reads unclear sentences |
| Runs where | Gnani cloud (WebSocket) | Gnani cloud (WebSocket) | Locally, on the same machine as our server, via Ollama |
| Needs internet | Yes | Yes | No |
| Input | Push-to-talk audio (16 kHz PCM16), plus customer names as hotwords | The short reply sentence | Only the clause the rules could not parse |
| Output | Transcript, usually in Hindi script, with English words kept | Streaming audio, played as it arrives | JSON: entry type and customer name |
| Used on | Every utterance | Every reply | Only some utterances (2 of 32 clean and 4 of 32 noisy test runs) |
| Code | `server/gnani.js` | `server/gnani.js` | `src/llm.js` |

**Gnani settings used:** Prisma `model=prisma-v3.0`, `format=codemix`, `language_code=hi-IN`, up to 50 customer names as `keyterms`, 1024-byte PCM frames with a `commit` at the end of speech. Timbre `model=timbre-v3.0`, voice `Shivani`, language `hi-en` (Hinglish), 24 kHz raw PCM.

**How a sentence flows**

1. The shopkeeper holds the button; Prisma returns the transcript.
2. The **rule-based parser** reads it first (numbers, credit vs payment, dates, corrections). This takes about 1 ms and is fully predictable.
3. If, and only if, a clause is not understood, that clause goes to **Qwen**.
4. The ledger applies the entries and builds a short reply; **Timbre** speaks it.

**Why Qwen is boxed in.** A 1.5B model got 3 of its first 5 test sentences wrong when left unguarded (it read goods-on-credit as a payment, turned 300 into 10, and invented a question from noise). So it is only allowed to choose the *type* of entry and the *customer*. Specifically:

- **Amounts never come from Qwen.** They are read by the deterministic number parser from the same clause.
- A credit or payment with no spoken amount, or no customer, is rejected.
- A customer name Qwen returns must actually appear in the clause (it cannot invent people).
- A question answer is rejected unless the clause contains a question word.
- If Ollama is down or slow (4 s timeout), Qwen returns nothing and the app asks the shopkeeper to repeat.

**Honest status of Qwen:** tested from the command line and inside the benchmark (`scripts/benchmark.js`), where it recovered a ₹400 credit from "Geeta ke khate me chaar sau ka atta chadha do". It has also been run through the browser UI (3 of 3 typed fallback sentences booked correctly, e.g. "Imran ke khate me teen sau ka ration chadha do" -> Imran, credit 300, in about 250 ms when warm; the first call after Ollama starts took about 1.2 s while the model loaded, and gibberish was rejected with "Samajh nahi aaya"). Ollama must be running on the machine that runs `npm start`. It is a fallback, not the main path. **The hosted Render deployment runs the rules only** (its Docker image has no Ollama), so common phrases such as "atta chadha do" are now handled by the rules themselves.

No telephony, no hosted end-to-end agent platform, no voice cloning. Orchestration is custom (`src/pipeline.js`).

## Responsible AI

- **Discloses it is an AI:** the first spoken reply of every session begins "Main ek AI sahayak hoon" (Hindi), and the header also shows "Main ek AI hoon · I am an AI assistant".
- **Privacy:** the ledger is encrypted at rest with AES-256-GCM (WebCrypto). The key is a non-extractable `CryptoKey` stored in IndexedDB, so page JavaScript cannot read the raw key. Nothing is synced anywhere.
- **Delete on demand:** the **Delete all data** button wipes both the ciphertext and the key.
- **What leaves the device:** audio of the push-to-talk utterance goes to Gnani Prisma for transcription, and reply text goes to Gnani Timbre. The ledger itself never leaves the device.
- **No lending or pressure language:** the app only records, corrects and reports. There are no automated reminders.
- **Synthetic data only** for tests and demo. No real customer data.
- **Secrets:** the API key lives in a gitignored `.env`.

## Run it

Requirements: Node 20+. Optional: [Ollama](https://ollama.com) for the Qwen fallback.

```bash
git clone <this repo> && cd khatawas
npm install

# 1. Gnani credentials (gitignored)
cp .env.example .env     # then fill in GNANI_API_KEY (URLs are prefilled)

# 2. Offline fallback model (optional, ~1 GB)
ollama pull qwen2.5:1.5b

# 3. Start
npm start                # http://localhost:3000
npm test                 # parser + ledger tests
npm run live-test        # real Gnani round trip (uses your credits)
```

Open the page on a phone or laptop, hold the big button (or the space bar), speak, release. You can also type a sentence in the box to test the parser without a microphone.

**Dev mode:** if `.env` has no Gnani settings the app falls back to the browser's built-in speech recognition and voice, and the footer shows `DEV MODE`. This is for development only and is not the submission path.

## Deploy (runs without your laptop)

The repo includes a `Dockerfile` and a Render Blueprint (`render.yaml`). On Render: **New > Blueprint**, pick this repo, then set the two variables in the dashboard, `GNANI_API_KEY` and `ACCESS_CODE` (the Gnani endpoints are built-in defaults, override with `GNANI_STT_WS_URL` / `GNANI_TTS_WS_URL` if Gnani changes them) (the demo link then ends in `?code=<ACCESS_CODE>`). By default public traffic is refused without the code so strangers cannot spend Gnani credits. Setting `PUBLIC_DEMO=1` opens the app to everyone instead, and protects the paid Gnani connections with usage caps (60 push-to-talk presses per visitor per 10 minutes and 3000 per day by default, tunable with `LIMIT_IP_10MIN` and `LIMIT_DAY`). The hosted version has no Qwen (Ollama is not in the image); the rules parser still handles the judged cases and unclear sentences simply get "Samajh nahi aaya". The free Render plan sleeps when idle, so open the link once a minute before a live demo, or use the always-on plan.

## Project layout

```
src/numbers.js     spoken-number parsing (Hindi / Hinglish / English)
src/parser.js      intents, customers, relative dates, corrections
src/ledger.js      ledger logic, clarification questions, spoken replies
src/llm.js         Qwen2.5-1.5B fallback via Ollama (strict JSON output)
src/pipeline.js    transcript -> parser -> (Qwen) -> ledger -> reply
server/index.js    static files, /ollama proxy, /stt and /tts WebSockets
server/gnani.js    the only file that knows Gnani's wire protocol
public/            UI, mic capture worklet, encrypted store, speech clients
test/              node:test suites
```

## Status and honest limitations

- **Confusing-sentence test list:** [`docs/test-sentences.md`](docs/test-sentences.md) has mixed-language, negated, corrected and same-name sentences with expected results and the known limits.
- **Working and tested (59 automated tests + a live benchmark):** number parsing, credit/payment direction, relative dates, multi-entry sentences, spoken corrections, duplicate-name clarification, queries, Hindi-script input, phonetic name matching.
- **Measured with real Gnani calls** (details and caveats in [`docs/benchmark.md`](docs/benchmark.md)): on 32 synthetic test sentences, 32/32 fully correct in clean speech and every amount exact; 19/32 fully correct and 25/29 amounts exact with synthetic 5 dB shop noise. Prisma finalises a transcript about 0.3 s after speech ends; Timbre's first reply audio arrives in about 0.43 s (median). The test audio is synthetic and the parser was tuned on part of it, so these are not field results.
- **Gnani integration (verified live):** `server/gnani.js` follows the Vachana docs (Prisma v3.0 STT WebSocket with `keyterms` hotwords and `format=codemix`; Timbre v3.0 TTS WebSocket). `npm run live-test` speaks a sentence with Timbre, transcribes it with Prisma and parses it. In our runs Prisma returned the transcript about 130-300 ms after the audio was sent and Timbre's first audio arrived in about 440-750 ms. These were short synthetic sentences over a home connection, not a noisy shop, so treat them as indicative, not as a latency guarantee.
- **Prisma returns final transcripts only** (no partial/interim results), so the screen shows the text after you release the button.
- **Hindi script:** Prisma answers in Devanagari ("तीन सौ चालीस रुपये उधार"), so the parser understands both Devanagari and romanized Hinglish. Customer names heard in Devanagari that were never typed or biased as hotwords are stored as heard.
- **Qwen fallback (tested on a small set):** Qwen2.5-1.5B answers in about 260-350 ms locally. On our first 5 messy sentences, unguarded, it got 3 wrong (goods-on-credit read as payments, amount 10 instead of 300, a made-up query for gibberish). So it is guard-railed: Qwen may only choose the kind and the customer; amounts always come from the deterministic number parser; writes with no spoken amount or no customer are rejected; query answers are rejected unless the sentence contains a question word. After this and a few-shot prompt it handled the one sentence the rules could not ("Geeta ke khate me chaar sau ka atta chadha do" -> credit 400) and produced nothing for gibberish. This is a handful of sentences, not a benchmark, and Qwen is a fallback, not the main path.
- **Noise robustness:** this build uses the browser's built-in noise suppression and echo cancellation. Tested only against synthetic noise (see benchmark), not real shop recordings; under noise Prisma sometimes mishears names or inserts words, which the app cannot recover from. Native RNNoise/SpeexDSP is on the roadmap below, not shipped.
- **Platform:** a web app that runs on a low-end phone's browser. A native Android build is future work.
- **Languages:** ten, see [`docs/languages.md`](docs/languages.md). Only Hindi/Hinglish was benchmarked on noisy speech. The other nine were verified end to end with synthetic Timbre voices (all ten return the amount as digits). Their spoken replies were drafted by an AI and need a native speaker's read. Spoken number words (instead of digits) are understood for Hindi, English, Marathi and Kannada.

## Roadmap

1. Verify and tune against Gnani Prisma/Timbre with measured end-to-end latency.
2. Evaluate Qwen fallback accuracy on a synthetic noisy test set.
3. Native Android shell with RNNoise + SpeexDSP (JNI), SQLCipher and Android Keystore, as in the original design.
4. Opt-in, polite payment reminders.
5. Odia, Assamese and Urdu once Gnani supports them; field tests of the nine new languages with real shopkeepers.

## License

MIT, see `LICENSE`.
