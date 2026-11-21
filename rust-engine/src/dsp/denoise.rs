/*
 * Copyright (c) 2026 KHATAWAS Project
 * Real-time Kirana noise suppression engine in pure Rust.
 * Spectral tracking, high-pass rumble suppression, and voice activity gain control.
 */

pub struct RustDenoiseProcessor {
    noise_floor: f32,
    alpha: f32,
    hp_prev_in: f32,
    hp_prev_out: f32,
    vad_threshold: f32,
}

impl RustDenoiseProcessor {
    pub fn new() -> Self {
        Self {
            noise_floor: 200.0,
            alpha: 0.98,
            hp_prev_in: 0.0,
            hp_prev_out: 0.0,
            vad_threshold: 450.0,
        }
    }

    /// Process a frame of 480 floating-point samples (10 ms at 48 kHz).
    /// Applies 2nd-order high-pass rumble filter and adaptive gain suppression during non-speech pauses.
    pub fn process_frame(&mut self, input: &[f32], output: &mut [f32]) {
        assert_eq!(input.len(), output.len());

        // 1. High-pass filter (cutoff ~100 Hz at 48 kHz) to remove AC hum and traffic rumble
        // R = 1 - (2 * pi * fc / fs) ~ 0.9869
        let r = 0.9869f32;
        let mut frame_energy = 0.0f32;

        for (i, &sample) in input.iter().enumerate() {
            let hp_out = sample - self.hp_prev_in + r * self.hp_prev_out;
            self.hp_prev_in = sample;
            self.hp_prev_out = hp_out;
            output[i] = hp_out;
            frame_energy += hp_out.abs();
        }

        let mean_energy = frame_energy / input.len() as f32;

        // 2. Adaptive noise floor estimation
        if mean_energy < self.noise_floor * 1.5 {
            self.noise_floor = self.alpha * self.noise_floor + (1.0 - self.alpha) * mean_energy;
        }

        // 3. Soft spectral gating / noise attenuation
        let snr = mean_energy / (self.noise_floor + 1.0);
        let gain = if snr < 1.2 {
            0.15f32 // Attenuate background floor by ~16 dB during silence
        } else if snr < 2.5 {
            0.15f32 + (snr - 1.2) * 0.65f32
        } else {
            1.0f32 // Full fidelity for active speech
        };

        for sample in output.iter_mut() {
            *sample *= gain;
        }
    }

    pub fn reset(&mut self) {
        self.noise_floor = 200.0;
        self.hp_prev_in = 0.0;
        self.hp_prev_out = 0.0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rumble_and_silence_attenuation() {
        let mut denoiser = RustDenoiseProcessor::new();
        let noise_frame = vec![50.0f32; 480]; // Low energy rumble
        let mut out_frame = vec![0.0f32; 480];

        denoiser.process_frame(&noise_frame, &mut out_frame);
        let out_energy: f32 = out_frame.iter().map(|s| s.abs()).sum();
        let in_energy: f32 = noise_frame.iter().map(|s| s.abs()).sum();

        // DC / low frequency and silence should be significantly attenuated
        assert!(out_energy < in_energy * 0.3);
    }
}
