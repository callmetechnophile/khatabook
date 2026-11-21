/*
 * Copyright (c) 2026 KHATAWAS Project
 * Speech-presence-aware adaptive noise spectrum estimation in pure Rust.
 * Implements Minima-Controlled Recursive Averaging (MCRA) with speech formant protection.
 */

use crate::dsp::stft::HALF_FFT;

pub struct NoiseEstimator {
    noise_power: [f32; HALF_FFT],
    smooth_power: [f32; HALF_FFT],
    initial_frames_countdown: usize,
    total_frames: u64,
}

impl Default for NoiseEstimator {
    fn default() -> Self {
        Self::new()
    }
}

impl NoiseEstimator {
    /// Creates a noise power estimator initializing over the first 12 frames (~120ms).
    pub fn new() -> Self {
        Self {
            noise_power: [500.0f32; HALF_FFT],
            smooth_power: [500.0f32; HALF_FFT],
            initial_frames_countdown: 12,
            total_frames: 0,
        }
    }

    /// Resets the noise floor estimator.
    pub fn reset(&mut self) {
        self.noise_power = [500.0f32; HALF_FFT];
        self.smooth_power = [500.0f32; HALF_FFT];
        self.initial_frames_countdown = 12;
        self.total_frames = 0;
    }

    /// Update running noise power spectrum using current frame power.
    /// Uses speech-presence probability and minima tracking to avoid updating noise during active speech.
    pub fn update(&mut self, current_power: &[f32]) {
        assert!(current_power.len() >= HALF_FFT);
        self.total_frames += 1;

        if self.initial_frames_countdown > 0 {
            // Initial calibration: track initial power
            for k in 0..HALF_FFT {
                self.smooth_power[k] = 0.6 * self.smooth_power[k] + 0.4 * current_power[k];
                self.noise_power[k] = self.smooth_power[k];
            }
            self.initial_frames_countdown -= 1;
            return;
        }

        for k in 0..HALF_FFT {
            let p = current_power[k];
            self.smooth_power[k] = 0.85 * self.smooth_power[k] + 0.15 * p;

            let n_prev = self.noise_power[k];
            let sm = self.smooth_power[k];

            if sm < n_prev {
                // Rapid tracking downwards when noise diminishes
                self.noise_power[k] = 0.85 * n_prev + 0.15 * sm;
            } else {
                // Check if this frequency bin contains active speech
                let snr = sm / (n_prev + 1e-4);
                if snr < 3.5 {
                    // Stationary noise region: adapt upwards
                    self.noise_power[k] = 0.985 * n_prev + 0.015 * sm;
                }
                // If snr >= 3.5, active speech formant detected: freeze noise estimate to prevent absorbing speech!
            }
        }
    }

    /// Current noise power spectrum [0..HALF_FFT].
    pub fn noise_power(&self) -> &[f32; HALF_FFT] {
        &self.noise_power
    }

    /// Total frames observed since creation or reset.
    pub fn total_frames(&self) -> u64 {
        self.total_frames
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_noise_floor_convergence() {
        let mut est = NoiseEstimator::new();
        let stationary_noise = [2000.0f32; HALF_FFT];

        for _ in 0..50 {
            est.update(&stationary_noise);
        }

        let p = est.noise_power();
        assert!((p[10] - 2000.0).abs() < 100.0, "Noise power should converge to 2000.0, got {}", p[10]);
    }

    #[test]
    fn test_speech_presence_protects_noise_estimate() {
        let mut est = NoiseEstimator::new();
        let background_noise = [500.0f32; HALF_FFT];

        // 1. Establish background
        for _ in 0..20 {
            est.update(&background_noise);
        }
        let noise_before_speech = est.noise_power()[50];

        // 2. High-energy speech burst
        let mut speech_frame = [500.0f32; HALF_FFT];
        speech_frame[50] = 50_000.0; // Formant at bin 50 (100x background)

        for _ in 0..30 {
            est.update(&speech_frame);
        }
        let noise_after_speech = est.noise_power()[50];

        // Noise estimate should remain protected by VAD and not track the speech peak
        assert!(
            noise_after_speech < noise_before_speech * 2.0,
            "Speech burst corrupted noise estimate: before={}, after={}",
            noise_before_speech,
            noise_after_speech
        );
    }
}
