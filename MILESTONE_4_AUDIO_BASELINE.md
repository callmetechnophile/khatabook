# KHATAWAS — MILESTONE 4: AUDIO PIPELINE BASELINE AUDIT

**Target:** Rust Audio Pipeline Core Migration  
**Audited Files:**  
- `app/src/main/java/com/khatawas/audio/AudioPipeline.kt`  
- `app/src/main/java/com/khatawas/audio/FirDecimator3.kt`  
- `app/src/main/java/com/khatawas/audio/RustFirDecimator.kt`  
- `app/src/main/java/com/khatawas/audio/FirDecimatorFactory.kt`  
- `app/src/main/java/com/khatawas/audio/RnnoiseProcessor.kt`  
- `app/src/main/java/com/khatawas/audio/GnaniSttClient.kt`  
- `rust-engine/crates/khatawas_core/src/audio/pcm.rs`  
- `rust-engine/crates/khatawas_core/src/audio/ring_buffer.rs`  
- `rust-engine/crates/khatawas_core/src/dsp/fir.rs`  
- `rust-engine/crates/khatawas_android/src/lib.rs`  
**Date:** November 21, 2026  

---

## 1. EXISTING PIPELINE ARCHITECTURE INVENTORY

### 1.1 AudioRecord Implementation & Capture Lifecycle
- **Audio Source:** `MediaRecorder.AudioSource.MIC`.
- **Sample Rate:** $48,000\text{ Hz}$ mono PCM (`AudioFormat.CHANNEL_IN_MONO`, `AudioFormat.ENCODING_PCM_16BIT`).
- **Buffer Sizing:** `bufferSize = maxOf(minBufferSize, FRAME_SIZE_480 * 4 * 2)` (at least $3,840$ bytes $= 1,920$ samples $= 40\text{ ms}$).
- **Capture Thread:** Dedicated background thread named `"KhatawasAudioCaptureThread"` with `Thread.MAX_PRIORITY`.
- **Lifecycle Control:** Explicit synchronized `start()` and `stop()`. Shutdown sequence:
  1. Sets `@Volatile isRecording = false`.
  2. Calls `AudioRecord.stop()` to unblock pending blocking reads.
  3. Joins capture thread with 2,000 ms timeout.
  4. Releases `AudioRecord` native handle only after thread join completes.

### 1.2 PCM16 Data Representation
- **Capture Format:** Signed 16-bit little-endian PCM integers stored in `ShortArray(480)`.
- **Intermediate Processing Format:** Single-precision float `FloatArray(480)` scaled directly:
  `inFloats[i] = rawShorts[i].toFloat()`.
- **Output Transport Format:** Signed 16-bit little-endian byte array `ByteArray(320)` ($160$ samples):
  - Sample range: $[-32768, +32767]$ with explicit integer clamping: `.coerceIn(-32768, 32767)`.
  - Byte 0: `(sample and 0xFF).toByte()`.
  - Byte 1: `((sample shr 8) and 0xFF).toByte()`.

### 1.3 Audio Frame Sizing & Decimation Ratio
- **Input Sample Rate:** $48,000\text{ Hz}$.
- **Input Frame Size:** $480$ samples ($10.0\text{ ms}$).
- **Decimation Factor:** $M = 3$ ($48\text{ kHz} \to 16\text{ kHz}$).
- **Output Sample Rate:** $16,000\text{ Hz}$.
- **Output Frame Size:** $160$ samples ($10.0\text{ ms}$, $320$ bytes).
- **AudioRecord Read Strategy:** Blocking sub-frame accumulation loop:
  Reads into `rawShorts` until exactly $480$ samples have been accumulated (`samplesRead < FRAME_SIZE_480`).

### 1.4 Ring Buffer & Buffer Pool Implementation
- **Current Kotlin Buffer Pool:** A circular ring of 8 pre-allocated `ByteArray(320)` arrays (`bufferPool: Array<ByteArray>`, `poolIndex: Int`). Prevents dynamic heap allocation per audio frame.
- **Current Rust Ring Buffer (`rust-engine/.../ring_buffer.rs`):** Basic single-threaded circular slice buffer with `write(&[f32])` and `read(&mut [f32])`. Needs enhancement in Milestone 4 for overflow/underflow detection, capacity bounds, and producer-consumer synchronization.

### 1.5 FIR State Ownership & JNI Interface
- **Milestone 3 Handle Architecture:** Opaque 64-bit integer handle (`jlong`) pointing to `Box<FirHandle>` in native memory.
- **Magic Verification:** Magic header `0x4B485457` (`'KHTW'`) guards against invalid pointers, dangling references, and double deallocation.
- **Ownership:** Instantiated in `AudioPipeline.processAudioLoop` via `FirDecimatorFactory.create()` (`RustFirDecimator`). Maintained across all consecutive frames of a session, and deallocated deterministically in `finally { resampler.close() }`.

### 1.6 RNNoise Integration
- **Source:** C/C++ native shared library `libkhatawas_jni.so` wrapping RNNoise.
- **Invocation:** `rnnoiseProcessor.processFrame(inFloats, outFloats)` operating on $480$-sample float buffers at $48\text{ kHz}$ prior to decimation.
- **Toggled via:** `@Volatile isRnnoiseEnabled: Boolean`.

### 1.7 Gnani STT Input Requirements
- **Endpoint:** WebSocket binary frames sent to `gnaniSttClient?.sendAudioChunk(chunk)`.
- **Format:** Linear PCM 16-bit signed, mono, little-endian at $16,000\text{ Hz}$.
- **Chunk Size:** $320$ bytes ($160$ samples $= 10\text{ ms}$ chunks).
- **Transport Queue:** Thread-safe `ArrayBlockingQueue<ByteArray>(64)` in `GnaniSttClient` ensuring network backpressure isolation.

---

## 2. ACTUAL PROCESSING SEQUENCE (PRESERVED IN RUST)

The sequence of operations per 10 ms audio frame:
```
[Microphone Hardware (48 kHz Mono PCM16)]
                   │
                   ▼ (AudioRecord blocking read)
     [Accumulate 480 rawShorts]
                   │
                   ▼ (PCM16 to Float conversion)
       [inFloats: 480 samples]
                   │
                   ▼ (RNNoise Noise Suppression at 48 kHz)
      [outFloats: 480 samples]
                   │
                   ▼ (31-tap FIR Decimator-by-3)
  [resampledFloats: 160 samples (16 kHz)]
                   │
                   ▼ (Float to PCM16 Little-Endian Clamp & Serialize)
       [targetBuffer: 320 bytes]
                   │
                   ▼ (Callback invocation)
 [Gnani STT WebSocket Client / Streaming Queue]
```

---

## 3. MILESTONE 4 ARCHITECTURAL REQUIREMENTS

In this milestone, Rust will take over the audio buffer management, frame accumulation, ring buffering, and streaming pipeline state:
1. **Rust PCM Conversion (`khatawas_core::audio::pcm`):**
   - High-throughput vectorizable conversion between `i16` / little-endian byte buffers and `f32`.
   - Explicit clamping to $[-32768, 32767]$ and boundary validation.
2. **Rust Audio Frame Manager (`khatawas_core::audio::frame`):**
   - Accumulate arbitrary chunk sizes (e.g., partial reads from `AudioRecord.read()`).
   - Extract full 480-sample frames for 48 kHz processing.
   - Extract full 160-sample frames for 16 kHz output.
   - Preserve fractional remnants across frame boundaries.
3. **Rust Bounded Ring Buffer (`khatawas_core::audio::ring_buffer`):**
   - Fixed capacity with overflow and underflow statistics.
   - Resettable and bounded memory with zero runtime allocations.
4. **Rust Streaming Pipeline State (`khatawas_core::audio::pipeline`):**
   - Unified stateful audio processor managing frame accumulation, FIR decimation, and output serialization.
   - Lifecycle handle management in `khatawas_android`:
     - `nativeAudioPipelineCreate(sampleRateIn, sampleRateOut, frameDurationMs) -> jlong`
     - `nativeAudioPipelinePushPcm16(handle, pcm16Bytes) -> jint`
     - `nativeAudioPipelineProcess(handle, pcm16In, pcm16Out) -> jint`
     - `nativeAudioPipelineReset(handle) -> jboolean`
     - `nativeAudioPipelineDestroy(handle) -> jboolean`
5. **Preservation Constraint:** RNNoise remains in C/C++ at $48\text{ kHz}$; AudioRecord lifecycle remains in Kotlin; Gnani STT client receives unchanged 320-byte PCM16 chunks.
