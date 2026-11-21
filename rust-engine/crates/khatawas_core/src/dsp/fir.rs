/*
 * Copyright (c) 2026 KHATAWAS Project
 * Stateful 48 kHz to 16 kHz FIR decimator in Rust.
 * Preserves the exact 31-tap coefficient design from Kotlin reference.
 */

pub const TAPS: usize = 31;
pub const DECIMATION: usize = 3;

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
    pub fn new() -> Self {
        Self {
            history: [0.0; TAPS],
            write_index: 0,
            input_sample_count: 0,
        }
    }

    pub fn reset(&mut self) {
        self.history = [0.0; TAPS];
        self.write_index = 0;
        self.input_sample_count = 0;
    }

    /// Process input samples and write decimated samples into output slice.
    /// Returns the number of decimated samples written.
    pub fn process(&mut self, input: &[f32], output: &mut [f32]) -> usize {
        let expected_out = (input.len() + DECIMATION - 1) / DECIMATION;
        assert!(output.len() >= expected_out, "Output buffer is too small");

        let mut output_count = 0;
        for &sample in input {
            self.history[self.write_index] = sample as f64;
            self.write_index = (self.write_index + 1) % TAPS;
            self.input_sample_count += 1;

            if self.input_sample_count % (DECIMATION as u64) == 0 {
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
        output_count
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_impulse_response() {
        let mut fir = FirDecimator3::new();
        let mut input = vec![0.0f32; 93];
        input[0] = 1.0f32; // delta impulse
        let mut output = vec![0.0f32; 31];

        let count = fir.process(&input, &mut output);
        assert_eq!(count, 31);
        // Peak of impulse response should align with center coefficient
        let max_val = output.iter().cloned().fold(0.0f32, f32::max);
        assert!(max_val > 0.25f32);
    }

    #[test]
    fn test_dc_gain() {
        let mut fir = FirDecimator3::new();
        let input = vec![1.0f32; 300];
        let mut output = vec![0.0f32; 100];

        fir.process(&input, &mut output);
        // After filter fills up, steady state DC output should be close to sum of coefficients (~1.0)
        let last_samples = &output[40..100];
        let avg: f32 = last_samples.iter().sum::<f32>() / last_samples.len() as f32;
        assert!((avg - 1.0).abs() < 0.02, "DC gain divergence: {}", avg);
    }
}
