# KHATAWAS — Gnani STT & TTS Integration Audit Report

**Audit Date:** 2026-11-21  
**Lead Auditor:** Speech AI & Network Systems Engineer  
**Configured Endpoints:**
- STT: `BuildConfig.GNANI_STT_WS_URL` (`wss://asr.gnani.ai/stt/v3/stream`)
- TTS: `BuildConfig.GNANI_TTS_WS_URL` (`wss://asr.gnani.ai/api/v1/tts`)
- API Key: `BuildConfig.GNANI_API_KEY` (injected from `local.properties`)

---

## 1. Protocol & Handshake Verification

### 1.1 Authentication & Request Headers
In `GnaniSttClient.kt` and `GnaniTtsClient.kt`, the following HTTP handshake headers are supplied:
```kotlin
headers["Authorization"] = "Bearer $apiKey"
headers["X-API-Key-ID"] = apiKey
headers["token"] = apiKey
```
- **Security Check**: Verified that credentials are NOT committed to git or exposed in source code. `local.properties` is listed in `.gitignore`.

### 1.2 Initial Session Configuration (STT)
Upon `onOpen`, the client transmits:
```json
{"token":"...","format":"pcm16","rate":16000,"encoding":"pcm","sample_rate":16000,"channels":1,"language":"hi-IN","lang":"hi-IN"}
```
- Specifies 16kHz, 16-bit PCM, Mono, target language `hi-IN`.

---

## 2. Integration Defect Analysis (CRITICAL)

### Defect 1: Audio Frame Loss During Handshake (P0)
- **Mechanism**:
  1. User presses PTT (`ACTION_DOWN`).
  2. `MainActivity.kt` executes:
     ```kotlin
     val client = GnaniSttClient()
     gnaniSttClient = client
     client.connectOnBackgroundThread()
     audioPipeline.start()
     ```
  3. `audioPipeline` begins capturing 48kHz audio immediately and dispatches frames every 10 ms.
  4. In `GnaniSttClient.kt`:
     ```kotlin
     fun sendAudioChunk(pcm16k: ByteArray) {
         if (!isOpen) {
             return // Frame silently dropped!
         }
         ...
     }
     ```
  5. The TLS connection and WebSocket upgrade handshake take between **150 ms and 600 ms** over cellular/Wi-Fi networks.
  6. **Consequence**: The first 15 to 60 audio frames (150 ms – 600 ms of speech) are discarded. When a user naturally speaks immediately upon pressing the button (*"Ramesh ka..."*), the customer name is truncated.

### Defect 2: Truncation of Final Transcript on PTT Release (P0)
- **Mechanism**:
  1. User finishes speaking and releases the button (`ACTION_UP`).
  2. `MainActivity.kt` executes:
     ```kotlin
     private fun stopListening() {
         audioPipeline.stop()
         try {
             gnaniSttClient?.close()
         } ...
     }
     ```
  3. Closing the socket immediately sends a TCP FIN frame to the server.
  4. Cloud STT engines require **300 ms to 800 ms** to process residual audio buffers and compute the final ASR output.
  5. **Consequence**: The connection terminates before the Gnani server can transmit the final transcript JSON. `onClose` is received instead of `onMessage`.

### Defect 3: Lack of Interim vs Final Transcript Differentiation (P1)
- `GnaniSttClient.kt` parses any message matching regex `"(?:transcript|text|result|asr_output)"`.
- Streaming STT servers emit frequent *interim/partial* hypothesis messages followed by a *final* committed transcript message.
- Because the client does not check `is_final` or equivalent status flags, an interim partial hypothesis (e.g., *"Ramesh"*) can trigger `AppOrchestrator.processRealTransaction` prematurely before the full amount is parsed.

### Defect 4: Orphaned Gnani TTS Client (P2)
- `GnaniTtsClient.kt` was constructed with an `AudioTrack` streaming player for 16kHz PCM audio, but `AppOrchestrator.kt` remains wired strictly to `VoiceFeedback.kt` (Android native `TextToSpeech`).
- `GnaniTtsClient.kt` is currently unused in the main recording/feedback flow.
