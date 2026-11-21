/*
 * Copyright (c) 2026 KHATAWAS Project
 * Multi-band spectral subtraction with oversubtraction, spectral flooring, and smoothing.
 */

use crate::dsp::stft::HALF_FFT;

pub struct SpectralSubtractEngine {
    alpha: f32,          // Oversubtraction factor (1.0 to 2.5)
    beta: f32,           // Spectral floor factor (0.01 to 0.05)
    smoothing_factor: f32, // Temporal gain smoothing
    prev_gains: [f32; HALF_FFT],
}

impl Default for SpectralSubtractEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl SpectralSubtractEngine {
    pub fn new() -> Self {
        Self {
            alpha: 1.8,             // Strong suppression of Indian street and fan hum
            beta: 0.04,             // -14 dB spectral floor to eliminate musical noise
            smoothing_factor: 0.70, // Prevents temporal flutter
            prev_gains: [1.0f32; HALF_FFT],
        }
    }

    pub fn reset(&mut self) {
        self.prev_gains = [1.0f32; HALF_FFT];
    }

    /// Compute spectral subtraction gains for positive frequency bins [0..HALF_FFT].
    pub fn compute_gains(
        &mut self,
        current_power: &[f32],
        noise_power: &[f32],
        gains_out: &mut [f32],
    ) {
        assert!(current_power.len() >= HALF_FFT);
        assert!(noise_power.len() >= HALF_FFT);
        assert!(gains_out.len() >= HALF_FFT);

        let min_gain = (self.beta).sqrt();

        for k in 0..HALF_FFT {
            let p_y = current_power[k];
            let p_n = noise_power[k];

            let subtracted_p = p_y - self.alpha * p_n;
            let floor_p = self.beta * p_n;
            let clean_p = subtracted_p.max(floor_p);

            let raw_gain = if p_y > 1e-4 {
                (clean_p / p_y).sqrt().min(1.0).max(min_gain)
            } else {
                min_gain
            };

            // Temporal gain smoothing to mitigate musical noise artifacts
            let smooth_gain = self.smoothing_factor * self.prev_gains[k]
                + (1.0 - self.smoothing_factor) * raw_gain;

            self.prev_gains[k] = smooth_gain;
            gains_out[k] = smooth_gain;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_spectral_subtraction_suppresses_noise() {
        let mut sub = SpectralSubtractEngine::new();
        let noise = [1000.0f32; HALF_FFT];
        let noisy_speech = [1200.0f32; HALF_FFT]; // Low SNR
        let mut gains = [0.0f32; HALF_FFT];

        for _ in 0..5 {
            sub.compute_gains(&noisy_speech, &noise, &mut gains);
        }

        // When power is dominated by noise, gain should be strongly attenuated down toward floor
        for &g in &gains {
            assert!(g < 0.45, "Gain not sufficiently attenuated: {}", g);
        }
    }

    #[test]
    fn test_high_snr_preserves_speech() {
        let mut sub = SpectralSubtractEngine::new();
        let noise = [100.0f32; HALF_FFT];
        let loud_speech = [20000.0f32; HALF_FFT]; // High SNR (+23 dB)
        let mut gains = [0.0f32; HALF_FFT];

        for _ in 0..5 {
            sub.compute_gains(&loud_speech, &noise, &mut gains);
        }

        // High SNR components should retain nearly full unity gain
        for &g in &gains {
            assert!(g > 0.90, "Speech gain attenuated unnecessarily: {}", g);
        }
    }
}
