/*
 * Copyright (c) 2026 KHATAWAS Project
 * Decision-Directed (Ephraim-Malah) Wiener filter for speech enhancement.
 */

use crate::dsp::stft::HALF_FFT;

pub struct WienerFilterEngine {
    alpha_dd: f32, // Decision-directed smoothing factor (typically 0.96 - 0.98)
    min_gain: f32, // Gain floor to preserve speech naturalness (-20 dB)
    prev_clean_power: [f32; HALF_FFT],
}

impl Default for WienerFilterEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl WienerFilterEngine {
    pub fn new() -> Self {
        Self {
            alpha_dd: 0.96,
            min_gain: 0.10, // -20 dB floor
            prev_clean_power: [0.0f32; HALF_FFT],
        }
    }

    pub fn reset(&mut self) {
        self.prev_clean_power = [0.0f32; HALF_FFT];
    }

    /// Compute Wiener gains G[k] for each positive frequency bin.
    pub fn compute_gains(
        &mut self,
        current_power: &[f32],
        noise_power: &[f32],
        gains_out: &mut [f32],
    ) {
        assert!(current_power.len() >= HALF_FFT);
        assert!(noise_power.len() >= HALF_FFT);
        assert!(gains_out.len() >= HALF_FFT);

        for k in 0..HALF_FFT {
            let p_y = current_power[k];
            let p_n = (noise_power[k]).max(1e-4);

            // A posteriori SNR: gamma = P_y / P_n
            let gamma = (p_y / p_n).max(1e-4);

            // A priori SNR via Decision-Directed approach:
            // xi(t) = alpha * (P_clean_prev / P_n) + (1 - alpha) * max(gamma - 1, 0)
            let prev_snr = self.prev_clean_power[k] / p_n;
            let inst_snr = (gamma - 1.0).max(0.0);
            let xi = (self.alpha_dd * prev_snr + (1.0 - self.alpha_dd) * inst_snr).max(1e-4);

            // Wiener Gain: G = xi / (xi + 1)
            let raw_gain = xi / (xi + 1.0);
            let clamped_gain = raw_gain.max(self.min_gain).min(1.0);

            // Update clean power estimate for next frame's recursion
            self.prev_clean_power[k] = (clamped_gain * clamped_gain) * p_y;

            gains_out[k] = clamped_gain;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_wiener_suppresses_low_snr() {
        let mut wiener = WienerFilterEngine::new();
        let noise = [1000.0f32; HALF_FFT];
        let noisy = [1010.0f32; HALF_FFT]; // Very low SNR (~0 dB)
        let mut gains = [0.0f32; HALF_FFT];

        for _ in 0..5 {
            wiener.compute_gains(&noisy, &noise, &mut gains);
        }

        for &g in &gains {
            assert!(g < 0.25, "Wiener gain failed to attenuate noise: {}", g);
        }
    }

    #[test]
    fn test_wiener_preserves_high_snr() {
        let mut wiener = WienerFilterEngine::new();
        let noise = [50.0f32; HALF_FFT];
        let loud_voice = [10000.0f32; HALF_FFT]; // SNR > 20 dB
        let mut gains = [0.0f32; HALF_FFT];

        for _ in 0..5 {
            wiener.compute_gains(&loud_voice, &noise, &mut gains);
        }

        for &g in &gains {
            assert!(g > 0.85, "Wiener gain degraded high SNR speech: {}", g);
        }
    }
}
