# KHATAWAS — Android Resource & Performance Audit Report

**Audit Date:** 2026-11-21  
**Target Class:** Android Entry-Tier Terminals (< 2 GB Physical RAM, API 24–34)  
**Profiled Hardware:** Vivo / iQOO Z9x (ARM64-v8a)  

---

## 1. Memory & Heap Analysis

| Subsystem | Allocation Profile | Risk Level | Notes |
|---|---|:---:|---|
| **Dalvik/ART Heap** | ~18 MB – 28 MB steady state | **LOW** | Low overall heap footprint. Jetpack Compose and heavy UI frameworks are absent. |
| **Native C++ Heap** | ~3.8 MB (RNNoise weights + state) | **LOW** | Neural network weights statically embedded in rodata/bss (`rnnoise_data.c`). |
| **Audio Loop Churn** | **32 KB/sec continuous** | **MEDIUM (Defect)** | `AudioPipeline.kt` line 145 calls `pcm16kBytes.clone()` every 10 ms (100 times/sec). Triggers minor GC sweeps every 45–90 seconds on 2GB devices. |
| **Encrypted Database** | ~4 MB (SQLCipher native cache) | **LOW** | `net.zetetic:android-database-sqlcipher` runs lean with default cache sizes. |

---

## 2. Threading & Concurrency Profile

| Thread Name | Priority | Role | Blocking Behavior |
|---|---|---|---|
| `main` | UI (Default) | UI layout, view updates, TextToSpeech initialization. | Non-blocking. Never touches disk or network. |
| `KhatawasDsdThread` | Real-time (`Process.THREAD_PRIORITY_URGENT_AUDIO`) | 48kHz AudioRecord reading, float conversion, RNNoise inference. | Continuous loop during PTT press; blocks on `AudioRecord.read()`. |
| `pool-*-thread-1` (`dbExecutor`) | Background | Single-threaded SQLite execution for SQLCipher transactions. | Sequentially queues inserts; prevents SQLite lock contention. |
| `GnaniSttClient-Worker` | Background | Java-WebSocket I/O and frame transmission. | Runs off main thread. |

---

## 3. Lifecycle & Hardware Interrupts

1. **Screen Rotation / Activity Re-creation**:
   - `MainActivity.kt` does not handle `onSaveInstanceState` or retain `AudioPipeline` across configuration changes. A screen rotation during recording terminates the current recording session and leaks background threads if `onDestroy` cleanup lags.
2. **Microphone Interruption (Incoming Phone Call)**:
   - When an incoming call arrives, Android audio focus is stolen by telephony. `AudioRecord.read()` will return `0` or `ERROR_INVALID_OPERATION`. `AudioPipeline` lacks an `OnAudioFocusChangeListener` and continues spinning its capture loop.
3. **Volume Ducking Hazard in `VoiceFeedback.kt`**:
   - `duckVolumeToMax()` forces `STREAM_MUSIC` to hardware maximum ($100\%$).
   - If TTS playback errors out or the application crashes mid-speech, the user's phone volume remains trapped at maximum volume.
