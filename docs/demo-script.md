# KHATAWAS — 2:30 demo video script

**Format:** screen recording of the app on a phone (or phone-sized browser window), with the shopkeeper's voice live and a short voice-over. Target length 2:30, hard limit 3:00.
**Language:** Hinglish, as a real shopkeeper speaks. Subtitles in English.

## Before you record (checklist)
- [ ] `npm start`, `.env` filled in, footer reads **STT: Gnani Prisma v3 · TTS: Timbre v3** (not DEV MODE).
- [ ] Tap **Delete all data** so the ledger is empty. Synthetic names only.
- [ ] Type **Sunita** into the **Add customer** box and press Add (so Prisma knows the name before you say it; without this it once heard "Sunita" as "Punita").
- [ ] Do the silent seed (typed, off camera, in this order): `Ramesh Kumar ka 100 udhaar` and `Ramesh Gupta ka 200 udhaar`. This gives the app two customers called Ramesh. The numbers quoted in the scenes below were verified by running this exact sequence (scenes 2–7) through the engine.
- [ ] Hold the phone near a speaker playing street/shop noise for scene 4.
- [ ] Screen recording on, audio from the phone speaker captured so the spoken replies are heard.

## Scenes

### 1. Hook (0:00–0:15)
**Visual:** a paper khata, then a queue of customers. Cut to the KHATAWAS screen.
**Voice-over:** "Every day, millions of kirana owners write udhaar on paper while a queue waits. Khata apps need typing. Nobody types at peak hour. KHATAWAS is a khata you just talk to."
**On screen:** the line "Main ek AI hoon · I am an AI assistant" is visible in the header. Say it: "The app tells you up front it is an AI."

### 2. One sentence, one entry (0:15–0:40)
**Shopkeeper (hold button):** "Sunita ka teen sau chaalis udhaar."
**App replies (spoken, on screen):** "Sunita ka 340 rupaye udhaar likh diya. Total 340."
**Voice-over:** "Spoken number words become the right amount. Teen sau chaalis is 340. The table updates instantly."

### 3. Amount in any form (0:40–1:00)
**Shopkeeper:** "Sunita ka dedh sau diya."
**App:** "Sunita ka 150 rupaye jama likh diya. Total 190."
**Voice-over:** "Dedh sau is 150. Diya means she paid, so the balance goes down. Credit and payment are told apart by what the shopkeeper says."
**Quick montage (typed or spoken, text on screen):** "three forty" → 340, "dhai sau" → 250, "saade teen sau" → 350.

### 4. Shop noise (1:00–1:20)
**Visual:** noise playing near the phone.
**Shopkeeper:** "Ramesh Kumar ka paanch sau udhaar."
**Voice-over:** "Same thing with street noise around. Only the press-and-hold utterance is sent, and the shop's customer names are sent to Gnani Prisma as hotwords so names are recognised."
> Only claim robustness if the take really worked. If it did not, cut this scene rather than fake it.

### 5. Spoken correction (1:20–1:40)
**Shopkeeper:** "Ramesh Kumar ka teen sau udhaar." Then immediately: "Nahi, chaar sau."
**App:** "Theek hai, Ramesh Kumar ka 400 kar diya. Total 1000."
**Voice-over:** "People correct themselves mid-sale. 'Nahi, chaar sau' fixes the last entry. No screen touched."

### 6. Two customers, one name (1:40–2:00)
**Shopkeeper:** "Ramesh ka paanch sau udhaar."
**App:** "Kaun sa Ramesh? Ramesh Kumar ya Ramesh Gupta?"
**Shopkeeper:** "Gupta."
**App:** "Ramesh Gupta ka 500 rupaye udhaar likh diya. Total 700."
**Voice-over:** "When two customers share a name, it asks one short question instead of guessing."

### 7. Spoken answers (2:00–2:15)
**Shopkeeper:** "Aaj kitna udhaar gaya?" then "Sunita ka total?"
**App:** speaks "Aaj 2040 rupaye udhaar gaya.", then "Sunita ka total 190 rupaye baaki hai." Point at the "ms to reply" chip on screen.
**Voice-over:** "Answers come back as speech, so the owner never looks at the screen. Replies are generated in under 5 milliseconds locally; speech time depends on Gnani." Quote the real measured number from the chip, not this sentence, if you have one.

### 8. Privacy and close (2:15–2:30)
**Visual:** footer "Ledger encrypted on this device (AES-256-GCM)", tap **Delete all data**, ledger empties.
**Voice-over:** "The ledger is encrypted on the device, never synced, and can be wiped in one tap. KHATAWAS: zero-friction, voice-first khata for the backbone of Indian retail."
**End card:** Team ANTHROMINDS · GitHub link · live link.

## Things NOT to say (not true of this build)
- "Works offline" — speech needs Gnani over the network. Only the parser, the ledger and Qwen run locally.
- "RNNoise / SpeexDSP / SQLCipher / Android Keystore" — those are the native Android plan, not this web build.
- "Sub-2-second" — say it only after you have measured it and can show the number.

## If something goes wrong on camera
- Gnani drops: say "Gnani is down, falling back" only if you actually want to show dev mode; otherwise re-take.
- Wrong amount: do not cut it if it is a correction scene, a visible fix is a good demo of the correction feature.
