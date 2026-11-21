/*
 * Copyright (c) 2026 KHATAWAS Project
 * Pure Rust audio denoising engine combining STFT, Noise Estimation, Spectral Subtraction, and Wiener Filtering.
 */

use crate::dsp::noise_est::NoiseEstimator;
use crate::dsp::spectral_sub::SpectralSubtractEngine;
use crate::dsp::stft::{
    apply_spectral_gains, compute_power, fft_radix2, ifft_radix2, FFT_SIZE,
    HALF_FFT,
};
use crate::dsp::wiener::WienerFilterEngine;

pub const FRAME_SIZE_480: usize = 480;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DenoiseMode {
    SpectralSubtraction,
    WienerFilter,
    Combined,
}

pub struct RustDenoiseProcessor {
    mode: DenoiseMode,
    noise_estimator: NoiseEstimator,
    spectral_subtract: SpectralSubtractEngine,
    wiener_filter: WienerFilterEngine,
    re: [f32; FFT_SIZE],
    im: [f32; FFT_SIZE],
    power: [f32; HALF_FFT],
    gains: [f32; HALF_FFT],
    ola_tail: [f32; FFT_SIZE - FRAME_SIZE_480], // 32 samples overlap tail
    processed_frames: u64,
}

impl Default for RustDenoiseProcessor {
    fn default() -> Self {
        Self::new(DenoiseMode::WienerFilter)
    }
}

impl RustDenoiseProcessor {
    /// Creates a streaming denoiser configured with the specified enhancement mode.
    pub fn new(mode: DenoiseMode) -> Self {
        Self {
            mode,
            noise_estimator: NoiseEstimator::new(),
            spectral_subtract: SpectralSubtractEngine::new(),
            wiener_filter: WienerFilterEngine::new(),
            re: [0.0f32; FFT_SIZE],
            im: [0.0f32; FFT_SIZE],
            power: [0.0f32; HALF_FFT],
            gains: [1.0f32; HALF_FFT],
            ola_tail: [0.0f32; FFT_SIZE - FRAME_SIZE_480],
            processed_frames: 0,
        }
    }

    pub fn mode(&self) -> DenoiseMode {
        self.mode
    }

    pub fn set_mode(&mut self, mode: DenoiseMode) {
        self.mode = mode;
    }

    pub fn processed_frames(&self) -> u64 {
        self.processed_frames
    }

    pub fn reset(&mut self) {
        self.noise_estimator.reset();
        self.spectral_subtract.reset();
        self.wiener_filter.reset();
        self.re.fill(0.0);
        self.im.fill(0.0);
        self.power.fill(0.0);
        self.gains.fill(1.0);
        self.ola_tail.fill(0.0);
        self.processed_frames = 0;
    }

    /// Process a 480-sample audio frame at 48 kHz in-place or into separate output slice.
    /// Performs zero runtime heap allocations.
    pub fn process_frame(&mut self, input: &[f32], output: &mut [f32]) {
        assert_eq!(input.len(), FRAME_SIZE_480, "Input frame must be exactly 480 samples");
        assert_eq!(output.len(), FRAME_SIZE_480, "Output frame must be exactly 480 samples");

        // 1. Copy 480 samples and zero-pad to 512 points for radix-2 FFT
        for i in 0..FRAME_SIZE_480 {
            self.re[i] = input[i];
            self.im[i] = 0.0;
        }
        for i in FRAME_SIZE_480..FFT_SIZE {
            self.re[i] = 0.0;
            self.im[i] = 0.0;
        }

        // 2. Forward FFT
        fft_radix2(&mut self.re, &mut self.im);

        // 3. Compute Power Spectrum
        compute_power(&self.re, &self.im, &mut self.power);

        // 4. Update adaptive noise floor estimate
        self.noise_estimator.update(&self.power);
        let noise_p = self.noise_estimator.noise_power();

        // 5. Compute suppression gains according to selected mode
        match self.mode {
            DenoiseMode::SpectralSubtraction => {
                self.spectral_subtract.compute_gains(&self.power, noise_p, &mut self.gains);
            }
            DenoiseMode::WienerFilter => {
                self.wiener_filter.compute_gains(&self.power, noise_p, &mut self.gains);
            }
            DenoiseMode::Combined => {
                let mut sub_gains = [0.0f32; HALF_FFT];
                let mut wiener_gains = [0.0f32; HALF_FFT];
                self.spectral_subtract.compute_gains(&self.power, noise_p, &mut sub_gains);
                self.wiener_filter.compute_gains(&self.power, noise_p, &mut wiener_gains);
                for k in 0..HALF_FFT {
                    // Geometric mean combination
                    self.gains[k] = (sub_gains[k] * wiener_gains[k]).sqrt();
                }
            }
        }

        // 6. Apply spectral gains
        apply_spectral_gains(&mut self.re, &mut self.im, &self.gains);

        // 7. Inverse FFT
        ifft_radix2(&mut self.re, &mut self.im);

        // 8. Overlap-Add reconstruction:
        // Add previous frame's tail (32 samples) to the beginning of current frame
        let tail_len = self.ola_tail.len();
        for i in 0..tail_len {
            output[i] = self.re[i] + self.ola_tail[i];
        }
        for i in tail_len..FRAME_SIZE_480 {
            output[i] = self.re[i];
        }

        // Store circular convolution tail (samples 480..512) for the next frame
        for i in 0..tail_len {
            self.ola_tail[i] = self.re[FRAME_SIZE_480 + i];
        }

        self.processed_frames += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;

    #[test]
    fn test_denoiser_modes_execute_cleanly() {
        let mut denoiser = RustDenoiseProcessor::new(DenoiseMode::WienerFilter);
        let input = vec![100.0f32; FRAME_SIZE_480];
        let mut output = vec![0.0f32; FRAME_SIZE_480];

        denoiser.process_frame(&input, &mut output);
        assert_eq!(denoiser.processed_frames(), 1);

        denoiser.set_mode(DenoiseMode::SpectralSubtraction);
        denoiser.process_frame(&input, &mut output);
        assert_eq!(denoiser.processed_frames(), 2);

        denoiser.set_mode(DenoiseMode::Combined);
        denoiser.process_frame(&input, &mut output);
        assert_eq!(denoiser.processed_frames(), 3);

        denoiser.reset();
        assert_eq!(denoiser.processed_frames(), 0);
    }

    #[test]
    fn test_denoiser_attenuates_stationary_noise() {
        let mut denoiser = RustDenoiseProcessor::new(DenoiseMode::WienerFilter);
        let mut input = vec![0.0f32; FRAME_SIZE_480];
        let mut output = vec![0.0f32; FRAME_SIZE_480];

        // 100 Hz stationary tone representing electrical hum
        for i in 0..FRAME_SIZE_480 {
            input[i] = (2.0 * PI * 100.0 * (i as f32) / 48000.0).sin() * 500.0;
        }

        // Let noise estimator adapt over 30 frames
        for _ in 0..30 {
            denoiser.process_frame(&input, &mut output);
        }

        let in_power: f32 = input.iter().map(|&x| x * x).sum::<f32>() / (FRAME_SIZE_480 as f32);
        let out_power: f32 = output.iter().map(|&x| x * x).sum::<f32>() / (FRAME_SIZE_480 as f32);

        assert!(
            out_power < in_power * 0.25,
            "Stationary tone not suppressed: in_power={}, out_power={}",
            in_power,
            out_power
        );
    }
}
