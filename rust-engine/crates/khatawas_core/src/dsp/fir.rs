/*
 * Copyright (c) 2026 KHATAWAS Project
 * Stateful 48 kHz to 16 kHz FIR decimator in Rust.
 * Preserves the exact 31-tap coefficient design from Kotlin reference.
 */

pub const TAPS: usize = 31;
pub const DECIMATION: usize = 3;

/// Exact 31 filter coefficients matching Kotlin FirDecimator3 reference.
pub static COEFFICIENTS: [f64; TAPS] = [
    0.001694864290, 0.001201491530, -0.000904732020, -0.004227545336,
    -0.005427042574, 0.0, 0.011365095290, 0.018584217910,
    0.008250100127, -0.021236460303, -0.048939206360, -0.039590259461,
    0.029858125147, 0.145106949851, 0.254510780636, 0.299507242544,
    0.254510780636, 0.145106949851, 0.029858125147, -0.039590259461,
    -0.048939206360, -0.021236460303, 0.008250100127, 0.018584217910,
    0.011365095290, 0.0, -0.005427042574, -0.004227545336,
    -0.000904732020, 0.001201491530, 0.001694864290
];

#[derive(Debug, Clone)]
pub struct FirDecimator3 {
    history: [f64; TAPS],
    write_index: usize,
    input_sample_count: u64,
}

impl Default for FirDecimator3 {
    fn default() -> Self {
        Self::new()
    }
}

impl FirDecimator3 {
    /// Creates a new, zero-initialized stateful FIR decimator.
    pub fn new() -> Self {
        Self {
            history: [0.0; TAPS],
            write_index: 0,
            input_sample_count: 0,
        }
    }

    /// Resets filter history and sample count to initial zero state.
    pub fn reset(&mut self) {
        self.history = [0.0; TAPS];
        self.write_index = 0;
        self.input_sample_count = 0;
    }

    /// Current cumulative input sample count.
    pub fn sample_count(&self) -> u64 {
        self.input_sample_count
    }

    /// Calculate required output capacity for a given input length and current decimation phase.
    pub fn required_output_capacity(&self, input_len: usize) -> usize {
        if input_len == 0 {
            return 0;
        }
        let current_phase = (self.input_sample_count % (DECIMATION as u64)) as usize;
        let samples_to_first = if current_phase == 0 {
            0
        } else {
            DECIMATION - current_phase
        };

        if input_len < samples_to_first {
            0
        } else {
            1 + (input_len - samples_to_first) / DECIMATION
        }
    }

    /// Process streaming input samples and write decimated samples into output slice.
    ///
    /// Preserves internal filter history and decimation phase across consecutive invocations.
    /// Performs zero dynamic allocations.
    ///
    /// Returns the number of decimated samples written into `output`.
    pub fn process(&mut self, input: &[f32], output: &mut [f32]) -> usize {
        if input.is_empty() {
            return 0;
        }

        let mut output_count = 0;
        for &sample in input {
            self.history[self.write_index] = sample as f64;
            self.write_index = (self.write_index + 1) % TAPS;
            self.input_sample_count += 1;

            if self.input_sample_count % (DECIMATION as u64) == 0 {
                if output_count < output.len() {
                    let mut accumulator = 0.0;
                    let mut index = self.write_index;
                    for tap in 0..TAPS {
                        index = if index == 0 { TAPS - 1 } else { index - 1 };
                        accumulator += COEFFICIENTS[tap] * self.history[index];
                    }
                    output[output_count] = accumulator as f32;
                    output_count += 1;
                }
            }
        }
        output_count
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    #[test]
    fn test_empty_input() {
        let mut fir = FirDecimator3::new();
        let mut output = [0.0f32; 10];
        let written = fir.process(&[], &mut output);
        assert_eq!(written, 0);
    }

    #[test]
    fn test_ratio_480_to_160() {
        let mut fir = FirDecimator3::new();
        let input = vec![1000.0f32; 480];
        let mut output = vec![0.0f32; 160];
        let written = fir.process(&input, &mut output);
        assert_eq!(written, 160);
    }

    #[test]
    fn test_silence_preservation() {
        let mut fir = FirDecimator3::new();
        let input = vec![0.0f32; 480 * 4];
        let mut output = vec![0.0f32; 160 * 4];
        let written = fir.process(&input, &mut output);
        assert_eq!(written, 640);
        for &s in &output {
            assert_eq!(s, 0.0);
        }
    }

    #[test]
    fn test_dc_gain_unity() {
        let mut fir = FirDecimator3::new();
        let input = vec![1200.0f32; 480 * 8];
        let mut output = vec![0.0f32; 160 * 8];
        let written = fir.process(&input, &mut output);
        assert_eq!(written, 1280);

        let steady = &output[20..written];
        let avg: f64 = steady.iter().map(|&x| x as f64).sum::<f64>() / steady.len() as f64;
        assert!((avg - 1200.0).abs() < 2.0, "DC gain out of spec: {}", avg);
    }

    #[test]
    fn test_impulse_response_and_center_tap() {
        let mut fir = FirDecimator3::new();
        let mut input = vec![0.0f32; 480];
        input[0] = 1.0; // delta impulse
        let mut output = vec![0.0f32; 160];
        let written = fir.process(&input, &mut output);
        assert_eq!(written, 160);

        let energy: f64 = output.iter().map(|&x| (x * x) as f64).sum();
        assert!(energy > 0.0 && energy < 2.0, "Energy out of bounds: {}", energy);
    }

    #[test]
    fn test_state_continuity_across_frames() {
        let total_samples = 960;
        let mut input = Vec::with_capacity(total_samples);
        for i in 0..total_samples {
            input.push((2.0 * PI * 1000.0 * (i as f64) / 48000.0).sin() as f32);
        }

        // Single continuous run
        let mut single_output = vec![0.0f32; 320];
        let mut fir_single = FirDecimator3::new();
        fir_single.process(&input, &mut single_output);

        // Chunked run
        let mut chunk_output1 = vec![0.0f32; 160];
        let mut chunk_output2 = vec![0.0f32; 160];
        let mut fir_chunked = FirDecimator3::new();
        fir_chunked.process(&input[0..480], &mut chunk_output1);
        fir_chunked.process(&input[480..960], &mut chunk_output2);

        for i in 0..160 {
            assert_eq!(chunk_output1[i], single_output[i]);
            assert_eq!(chunk_output2[i], single_output[160 + i]);
        }
    }

    #[test]
    fn test_reset_behavior() {
        let mut fir = FirDecimator3::new();
        let input = vec![100.0f32; 480];
        let mut out1 = vec![0.0f32; 160];
        fir.process(&input, &mut out1);

        fir.reset();
        assert_eq!(fir.sample_count(), 0);

        let mut out2 = vec![0.0f32; 160];
        fir.process(&input, &mut out2);

        // Output after reset must match initial output from fresh instance
        for i in 0..160 {
            assert_eq!(out1[i], out2[i]);
        }
    }

    #[test]
    fn test_frequency_attenuation() {
        fn rms_for_freq(freq_hz: f64) -> f64 {
            let mut fir = FirDecimator3::new();
            let mut input = Vec::with_capacity(480 * 20);
            for i in 0..(480 * 20) {
                input.push((2.0 * PI * freq_hz * (i as f64) / 48000.0).sin() as f32);
            }
            let mut output = vec![0.0f32; input.len() / 3];
            let written = fir.process(&input, &mut output);
            let steady = &output[20..written];
            let sum_sq: f64 = steady.iter().map(|&x| (x * x) as f64).sum();
            (sum_sq / steady.len() as f64).sqrt()
        }

        let pass1k = rms_for_freq(1000.0);
        let pass7k = rms_for_freq(7000.0);
        let stop8k = rms_for_freq(8000.0);
        let stop12k = rms_for_freq(12000.0);
        let stop16k = rms_for_freq(16000.0);

        assert!(pass1k > 0.60, "1 kHz passband tone attenuated: {}", pass1k);
        assert!(pass7k > 0.30, "7 kHz edge tone attenuated: {}", pass7k);
        assert!(stop8k < pass1k * 0.70, "8 kHz Nyquist not attenuated: {}", stop8k);
        assert!(stop12k < pass1k * 0.10, "12 kHz stopband not attenuated: {}", stop12k);
        assert!(stop16k < pass1k * 0.05, "16 kHz stopband not attenuated: {}", stop16k);
    }
}
