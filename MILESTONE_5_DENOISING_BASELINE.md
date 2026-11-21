# KHATAWAS — MILESTONE 5: AUDIO DENOISING BASELINE AUDIT

**Target:** Rust-Native Audio Denoising Engine Evaluation vs. RNNoise Baseline  
**Audited Subsystems:**  
- `app/src/main/cpp/rnnoise_wrapper.cpp`  
- `app/src/main/cpp/rnnoise/` (RNN model, GRU cells, Bark frequency bank, pitch estimator)  
- `app/src/main/java/com/khatawas/audio/RnnoiseProcessor.kt`  
- `app/src/main/java/com/khatawas/audio/AudioPipeline.kt`  
- `rust-engine/crates/khatawas_core/src/dsp/denoise.rs`  
**Date:** November 21, 2026  

---

## 1. RNNOISE REFERENCE BASELINE INVENTORY

### 1.1 Architecture & Neural Topology
The reference noise suppressor is RNNoise (Valin, 2018), a hybrid classical-DSP and deep-recurrent neural network:
- **Audio Framing:** Exactly $480$ samples at $48,000\text{ Hz}$ ($10.0\text{ ms}$ frame size).
- **Analysis Filterbank:** 22 triangular Bark-scale critical frequency bands matching human auditory frequency resolution ($0\text{ Hz}$ to $20\text{ kHz}$).
- **Feature Extraction:** 22 Bark energy bands + 6 pitch/cepstral correlation coefficients (total 42 input features per 10 ms frame).
- **Recurrent Neural Network Architecture:**
  - Dense layer (42 $\to$ 24 units, `tanh`)
  - 3 Gated Recurrent Unit (GRU) layers:
    - VAD GRU: 24 units (outputs frame speech probability $[0.0, 1.0]$)
    - Noise GRU: 48 units
    - Denoise GRU: 96 units
  - Output dense layer (64 units $\to$ 22 band gains via sigmoid activation)
- **Spectral Gain Synthesis:** Interpolates 22 Bark gains to 240 FFT bins and applies spectral synthesis + pitch filtering.

### 1.2 Input and Output Sample Formats
- **Sample Rate:** $48,000\text{ Hz}$ mono PCM.
- **Data Type:** 32-bit floating point (`f32` / `float`).
- **Input Amplitude Range:** Raw unnormalized 16-bit PCM amplitudes $[-32768.0, +32767.0]$.
- **Output Amplitude Range:** Noise-suppressed amplitude floats $[-32768.0, +32767.0]$.
- **Sanitization:** `rnnoise_wrapper.cpp` sanitizes inputs against `NaN` and $\pm\infty$ by replacing non-finite values with $0.0\text{f}$.

### 1.3 Lifecycle & Memory Ownership
- **Native Instance:** `DenoiseState*` allocated via `rnnoise_create(NULL)` in C++.
- **JNI Handle:** Opaque 64-bit integer (`jlong`) returned to Kotlin `RnnoiseProcessor.nativeHandle`.
- **Thread Safety:**
  - JNI `processFrame` allocates stack buffers `float inFrame[480]` and `float outFrame[480]` per invocation.
  - Kotlin `RnnoiseProcessor` uses `@Synchronized` on `processFrame()` and `destroy()`.
- **Destruction:** `rnnoise_destroy(state)` frees all internal GRU state buffers.

### 1.4 Current Pipeline Processing Sequence
In `AudioPipeline.kt`:
1. `AudioRecord.read(rawShorts, ...)` captures 480 PCM16 shorts.
2. `rawShorts` converted to `inFloats: FloatArray(480)`.
3. **RNNoise Step:** `rnnoiseProcessor.processFrame(inFloats, outFloats)` executes at $48\text{ kHz}$.
4. **Rust FIR & Audio Core:** Decimates 480 clean floats at $48\text{ kHz} \to 160$ samples at $16\text{ kHz}$, serializes to 320 bytes PCM16.
5. **Gnani STT Client:** Streams 320-byte chunks via WebSocket to cloud ASR.

---

## 2. EXPERIMENTAL RUST DENOISING ENGINE REQUIREMENTS

To evaluate replacing or augmenting RNNoise with pure Rust, the engine must implement classical statistical and psychoacoustic spectral processing without external heavy dependencies:

### Module A — Short-Time Fourier Transform (STFT)
- Frame length $N = 512$ or $960$ points ($10\text{ ms}$ or $20\text{ ms}$).
- Hann / Square-root Hann window for constant overlap-add (COLA) property.
- Radix-2 / Cooley-Tukey FFT & IFFT with bit-reversal permutation.
- Overlap-add (OLA) buffer state preservation between consecutive frames.
- Perfect reconstruction verification: $\text{output} \approx \text{input}$ when gain $= 1.0$ (error $< 10^{-5}$).

### Module B — Noise Power Spectrum Estimation
- Initial noise floor acquisition during first $N$ frames ($100\text{ ms} - 200\text{ ms}$).
- Recursive averaging: $P_{\text{noise}}(k, t) = \alpha_n P_{\text{noise}}(k, t-1) + (1 - \alpha_n) |Y(k, t)|^2$.
- Voice Activity Detection (VAD) / Speech Presence Probability (SPP) gating:
  Halts or slows noise updates when frame energy / SNR exceeds speech threshold to avoid voice cancellation.

### Module C — Multi-Band Spectral Subtraction
- Subtraction formula: $|S(k)|^2 = \max\left(|Y(k)|^2 - \alpha P_{\text{noise}}(k),\ \beta P_{\text{noise}}(k)\right)$.
- Oversubtraction factor $\alpha \in [1.0, 2.5]$ based on sectional SNR.
- Spectral floor parameter $\beta \in [0.01, 0.05]$ (prevents negative power and reduces musical-noise spikes).
- Phase preservation: $\hat{S}(k) = |\hat{S}(k)| \cdot e^{j \angle Y(k)}$.

### Module D — Decision-Directed Wiener Filtering
- A posteriori SNR: $\gamma(k, t) = \frac{|Y(k, t)|^2}{P_{\text{noise}}(k, t)}$.
- A priori SNR (Ephraim-Malah decision-directed):
  $\xi(k, t) = \alpha_{\text{dd}} \frac{|\hat{S}(k, t-1)|^2}{P_{\text{noise}}(k, t)} + (1 - \alpha_{\text{dd}}) \max(\gamma(k, t) - 1, 0)$.
- Wiener Gain: $G(k, t) = \frac{\xi(k, t)}{\xi(k, t) + 1}$.
- Gain flooring ($G_{\min} = 0.10 \approx -20\text{ dB}$).
- Temporal smoothing: prevents musical chirp noise artifacts.

### Module E — Streaming Pipeline Integration
- Ingests 480 samples at 48 kHz.
- Operates inside `khatawas_core::dsp::denoise` with zero per-frame heap allocations.
- Controlled JNI handles for comparative benchmarks.

---

## 3. COMPARATIVE EVALUATION STRATEGY

Both RNNoise and the experimental Rust denoiser must process identical, standardized audio files representative of Kirana store environments (Hindi, Kannada, English speech mixed with fan, AC, traffic, and barcode scanner noise).

Quality metrics to record:
- **SNR Improvement ($\Delta\text{SNR}$ dB)**
- **Speech Distortion / Attenuation Index**
- **Residual Noise Reduction (dB)**
- **Musical Noise Artifact Count**
- **Processing Latency (Median & p95 $\mu\text{s}$ per 10 ms frame)**
- **CPU & Memory Footprint on Android Studio x86_64 Emulator & ARM64 Target**
- **Formal Retain/Replace Recommendation** based on objective thresholds.
