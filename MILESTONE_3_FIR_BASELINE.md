# KHATAWAS — MILESTONE 3: FIR DECIMATOR BASELINE AUDIT

**Target:** Stateful 48 kHz to 16 kHz FIR Decimator Migration  
**Source Implementation:** `app/src/main/java/com/khatawas/audio/FirDecimator3.kt`  
**Pipeline Consumer:** `app/src/main/java/com/khatawas/audio/AudioPipeline.kt`  
**Existing Test Suite:** `app/src/test/java/com/khatawas/audio/FirDecimator3Test.kt`  
**Date:** November 21, 2026  

---

## 1. COEFFICIENT AUDIT

The existing filter in `FirDecimator3.kt` is a 31-tap causal, linear-phase (symmetric Type I) FIR low-pass filter designed for decimation-by-3 from 48,000 Hz to 16,000 Hz.
The design cutoff frequency is $f_c = 7,200\text{ Hz}$ ($0.15\text{ cycles/sample}$ at $48\text{ kHz}$), placed safely below the $8\text{ kHz}$ Nyquist folding frequency of the decimated $16\text{ kHz}$ output rate.

### Exact 31 Filter Coefficients ($h[0..30]$)
```
h[ 0] = +0.001694864290
h[ 1] = +0.001201491530
h[ 2] = -0.000904732020
h[ 3] = -0.004227545336
h[ 4] = -0.005427042574
h[ 5] =  0.000000000000
h[ 6] = +0.011365095290
h[ 7] = +0.018584217910
h[ 8] = +0.008250100127
h[ 9] = -0.021236460303
h[10] = -0.048939206360
h[11] = -0.039590259461
h[12] = +0.029858125147
h[13] = +0.145106949851
h[14] = +0.254510780636
h[15] = +0.299507242544   <-- Center tap (group delay = 15 input samples = 312.5 microseconds)
h[16] = +0.254510780636
h[17] = +0.145106949851
h[18] = +0.029858125147
h[19] = -0.039590259461
h[20] = -0.048939206360
h[21] = -0.021236460303
h[22] = +0.008250100127
h[23] = +0.018584217910
h[24] = +0.011365095290
h[25] =  0.000000000000
h[26] = -0.005427042574
h[27] = -0.004227545336
h[28] = -0.000904732020
h[29] = +0.001201491530
h[30] = +0.001694864290
```

### Symmetry & Properties
- $\sum_{k=0}^{30} h[k] = 0.999999999999 \approx 1.000000$ (Unity DC passband gain).
- Perfect even symmetry: $h[k] = h[30 - k]$ for all $0 \le k \le 30$.
- Zero-crossings at taps $k = 5$ and $k = 25$.
- Group delay: $\tau = \frac{N - 1}{2} = 15$ input samples ($312.5\ \mu\text{s}$ at $48\text{ kHz}$).

---

## 2. ARITHMETIC & DATA FORMAT SPECIFICATION

1. **Input Sample Format:**
   - Single-precision float array `FloatArray` (`f32` in Rust).
   - In `AudioPipeline.kt`, input values represent unnormalized PCM16 amplitudes converted directly from 16-bit signed shorts (range $[-32768.0, +32767.0]$).
2. **Output Sample Format:**
   - Single-precision float array `FloatArray` (`f32` in Rust).
   - Each decimated sample is converted to signed 16-bit integer with clamping in `AudioPipeline.kt`:
     `resampledFloats[i].toInt().coerceIn(-32768, 32767)`.
3. **Internal Arithmetic Precision:**
   - Double precision (`f64` / `Double` in Kotlin).
   - Stored in circular history buffer: `history[writeIndex] = sample.toDouble()`.
4. **Accumulator Precision:**
   - 64-bit IEEE 754 floating point (`Double` / `f64`).
   - Accumulator initialization: `var accumulator = 0.0`.
   - Cast to single-precision float only on final output assignment: `output[outputCount++] = accumulator.toFloat()`.
5. **Decimation Factor ($M$):**
   - $M = 3$ ($48,000\text{ Hz} \to 16,000\text{ Hz}$).
   - Downsampling ratio: exactly $3:1$.
6. **Decimation Phase & Timing:**
   - Governed by cumulative `inputSampleCount` (`Long` / `u64`).
   - Decimation occurs when `inputSampleCount % 3 == 0L`.
   - For an initial state starting at count 0:
     - Sample 1: count=1, no output
     - Sample 2: count=2, no output
     - Sample 3: count=3, downsample triggers -> Output sample 0 computed over input samples $[3, 2, 1, 0, \dots]$.
   - Because $480 \pmod 3 = 0$, every full 480-sample input frame produces exactly $\frac{480}{3} = 160$ output samples, maintaining phase alignment perfectly from frame to frame.

---

## 3. STATE REPRESENTATION & BOUNDARY BEHAVIOR

1. **State Elements:**
   - `history: DoubleArray(31)` (Circular ring buffer holding the most recent 31 input samples).
   - `writeIndex: Int` ($0 \le \text{writeIndex} < 31$, pointer to next insertion slot).
   - `inputSampleCount: Long` (Monotonically increasing cumulative input count).
2. **Convolution Traversal:**
   - Circular backward traversal from current write pointer:
     ```kotlin
     var index = writeIndex
     for (tap in 0 until TAPS) {
         index = if (index == 0) TAPS - 1 else index - 1
         accumulator += COEFFICIENTS[tap] * history[index]
     }
     ```
   - Tap 0 multiplies the newest sample (`history[writeIndex - 1]`).
   - Tap 30 multiplies the oldest sample in history (`history[writeIndex - 31]`).
3. **Frame Boundary Continuity:**
   - The filter instance is instantiated once when `AudioPipeline` starts recording.
   - Consecutive 480-sample frames (10 ms chunks) stream into `process()`.
   - The 31-sample history buffer and the `inputSampleCount` modulo phase carry across frame boundaries without reset, ensuring zero phase discontinuities, zero click artifacts, and continuous filter delay.
4. **Buffer Ownership & Allocation Strategy:**
   - In steady-state, `AudioPipeline.kt` preallocates input and output buffers (`FRAME_SIZE_480` and `SAMPLES_16K`).
   - `FirDecimator3.process()` performs **zero dynamic heap allocations** per frame.

---

## 4. EXISTING TEST SUITE SUMMARY

`FirDecimator3Test.kt` verifies 6 distinct DSP invariants:
1. `producesExactlyOneThirdOutputSamples`: 480 inputs $\to$ 160 outputs.
2. `preservesDcGainAfterFilterStartup`: DC gain within $0.16\%$ of unity ($1200.0 \pm 2.0$).
3. `processesDigitalSilenceWithoutDrift`: All zeros $\to$ exact zero outputs ($\le 10^{-6}$).
4. `attenuatesFrequenciesAboveOutputNyquist`:
   - $1\text{ kHz}$ passband tone: $> 0.60$ RMS.
   - $7\text{ kHz}$ edge tone: $> 0.30$ RMS.
   - $8\text{ kHz}$ Nyquist tone: $< 70\%$ of $1\text{ kHz}$.
   - $12\text{ kHz}$ stopband tone: $< 10\%$ of $1\text{ kHz}$.
   - $16\text{ kHz}$ stopband tone: $< 5\%$ of $1\text{ kHz}$.
5. `preservesFilterStateAcrossFrameBoundaries`: Consecutive $480$-sample sub-buffers match a continuous $960$-sample run within $0.0001$.
6. `verifiesImpulseResponse`: Energy of impulse response is bounded and non-zero ($0.0 < E < 2.0$).

---

## 5. MIGRATION DESIGN & LIFECYCLE REQUIREMENTS FOR RUST (PHASES 2-3)

To replace `FirDecimator3.kt` safely in Rust without global mutable state or memory leaks:
1. **`khatawas_core::dsp::fir::FirDecimator3`:**
   - Must mirror the exact coefficients, circular buffer logic, and 64-bit float accumulation.
   - Provide `new()`, `reset()`, and `process(&mut self, input: &[f32], output: &mut [f32]) -> usize`.
   - Zero heap allocation in `process()`.
   - Safe validation of buffer sizes and edge cases (empty slice, irregular lengths).
2. **Lifecycle-Safe JNI Handle Architecture in `khatawas_android`:**
   - No global static mutable filter state (prohibited by design rules).
   - Opaque 64-bit integer handle (`jlong`) pattern:
     - `nativeFirInit() -> jlong`: Allocates a `Box<FirDecimator3>` and returns `Box::into_raw(...) as jlong`.
     - `nativeFirProcess(handle: jlong, in: JFloatArray, out: JFloatArray) -> jint`: Safely casts handle, validates non-null, processes in-place using direct array regions, returns sample count.
     - `nativeFirReset(handle: jlong) -> jboolean`: Resets history and counter for stream restart.
     - `nativeFirDestroy(handle: jlong)`: Reconstitutes `Box::from_raw(...)` to deallocate cleanly on audio shutdown.
3. **Kotlin Wrapper & Fallback Architecture:**
   - Retain `FirDecimator3` in Kotlin as `KotlinFirDecimator`.
   - Implement common interface `IFirDecimator` with `process(input: FloatArray, output: FloatArray): Int`, `reset()`, `close()`.
   - Provide controlled toggle (e.g. `useRustFir = true/false`) to enable instant rollback if needed.
