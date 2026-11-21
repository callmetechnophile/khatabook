# KHATAWAS — MILESTONE 5: FIVE-MODULE SOURCE AUDIT

## Phase 1 — Source Audit Summary

**Inspection Path:** `rust-engine/crates/khatawas_core/src/dsp/`  
**Git Branch:** `feat/rust-first-migration`  
**Target Hardware:** Vivo / iQOO Z9x (`I2407`, ARM64 Snapdragon 6 Gen 1, Android 16) + Android Studio Emulator (`medium_phone`, x86_64)

---

## Module Classification Table

| # | Module Name | Source File | Classification | Lines of Code | Key Public Types & Functions | Dependencies | Test Coverage |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **STFT / Inverse STFT** | `stft.rs` | **IMPLEMENTED** | 176 | `fft_radix2`, `ifft_radix2`, `compute_magnitude`, `compute_power`, `apply_spectral_gains`, `create_hann_window`, `FFT_SIZE`, `HALF_FFT` | `std::f32::consts::PI` (Zero external crates) | 2 unit tests: Roundtrip identity & unit gain reconstruction |
| **2** | **Noise Estimation** | `noise_est.rs` | **IMPLEMENTED** | 135 | `NoiseEstimator`, `NoiseEstimator::new`, `update`, `reset`, `noise_power`, `total_frames` | `crate::dsp::stft::HALF_FFT` | 2 unit tests: Noise floor convergence & speech presence protection |
| **3** | **Spectral Subtraction** | `spectral_sub.rs` | **IMPLEMENTED** | 109 | `SpectralSubtractEngine`, `SpectralSubtractEngine::new`, `compute_gains`, `reset` | `crate::dsp::stft::HALF_FFT` | 3 unit tests: Suppression, speech preservation, numerical stability |
| **4** | **Wiener Filtering** | `wiener.rs` | **IMPLEMENTED** | 104 | `WienerFilterEngine`, `WienerFilterEngine::new`, `compute_gains`, `reset` | `crate::dsp::stft::HALF_FFT` | 2 unit tests: High SNR preservation & low SNR suppression |
| **5** | **Streaming Denoising Integration** | `denoise.rs` | **IMPLEMENTED** | 201 | `RustDenoiseProcessor`, `DenoiseMode`, `process_frame`, `set_mode`, `reset`, `FRAME_SIZE_480` | Modules 1–4 internal composition | 2 unit tests: Mode switching & stationary tone attenuation |

---

## Detailed Module Audit Findings

### 1. STFT / Inverse STFT (`stft.rs`)
- **Status:** **IMPLEMENTED**
- **Algorithm:** In-place Radix-2 Cooley-Tukey FFT (`fft_radix2`) with $O(N \log N)$ complexity and in-place bit-reversal permutation (`bit_reverse_permutation`).
- **Inverse Transform:** Conjugate-property forward FFT reuse with $1/N$ scaling (`ifft_radix2`).
- **Conjugate Symmetry:** `apply_spectral_gains` computes positive bins $0..256$ and enforces $X[N-k] = X[k]^*$ on bins $257..511$ ensuring zero imaginary leakage in real time domain.
- **Windowing:** `create_hann_window` generates standard Hann coefficients.

### 2. Adaptive Noise Estimation (`noise_est.rs`)
- **Status:** **IMPLEMENTED**
- **Algorithm:** Speech-presence-aware Minima-Controlled Recursive Averaging (MCRA).
- **Adaptation Rates:** 
  - Downward tracking parameter: $\alpha_{down} = 0.85$ (rapid tracking of dropping noise floors).
  - Upward tracking parameter: $\alpha_{up} = 0.985$ (slow upward integration).
- **Speech Protection (VAD):** Freezes noise estimation in any bin where instantaneous $SNR \ge 2.0$, completely protecting voiced speech formants.

### 3. Spectral Subtraction (`spectral_sub.rs`)
- **Status:** **IMPLEMENTED**
- **Algorithm:** Non-linear power spectral subtraction: $P_{clean}[k] = \max(P_y[k] - \alpha P_n[k], \beta P_n[k])$.
- **Parameters:** Oversubtraction factor $\alpha = 1.8$, spectral floor $\beta = 0.04$ ($-14\text{ dB}$ floor), temporal smoothing $\gamma = 0.70$.
- **Artifact Control:** Gain smoothing eliminates isolated spectral peaks ("musical noise").

### 4. Wiener Filtering (`wiener.rs`)
- **Status:** **IMPLEMENTED**
- **Algorithm:** Decision-Directed (Ephraim-Malah) a priori SNR estimation ($\alpha_{dd} = 0.96$).
- **Gain Formula:** $G[k] = \frac{\xi[k]}{\xi[k] + 1}$ clamped to $G_{min} = 0.10$ ($-20\text{ dB}$ naturalness floor).
- **Numerical Guarding:** All divisors clamped with $+ 10^{-4}$ epsilon to guarantee no divide-by-zero, NaN, or infinity.

### 5. Streaming Denoiser Pipeline (`denoise.rs`)
- **Status:** **IMPLEMENTED**
- **Frame Size:** Exactly 480 samples at 48 kHz (10 ms).
- **FFT Extension:** Zero-padded to 512 points for radix-2 efficiency.
- **Overlap-Add:** 32-sample circular convolution tail stored in `ola_tail` and added to the succeeding frame.
- **Real-Time Guarantee:** Zero dynamic heap allocations during `process_frame`.
