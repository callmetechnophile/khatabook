/*
 * Copyright (c) 2026 KHATAWAS Project
 * Short-Time Fourier Transform (STFT) and Overlap-Add (OLA) reconstruction in pure Rust.
 */

use std::f32::consts::PI;

pub const FFT_SIZE: usize = 512;
pub const HALF_FFT: usize = FFT_SIZE / 2 + 1; // 257 unique frequency bins

pub fn bit_reverse_permutation(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0;
    for i in 0..n {
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
    }
}

/// In-place Radix-2 Forward Fast Fourier Transform (Cooley-Tukey).
pub fn fft_radix2(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    assert!(n.is_power_of_two(), "FFT size must be a power of two");
    assert_eq!(n, im.len(), "Real and imaginary slices must match length");

    bit_reverse_permutation(re, im);

    let mut len = 2;
    while len <= n {
        let half = len / 2;
        let angle = -2.0 * PI / (len as f32);
        let w_step_re = angle.cos();
        let w_step_im = angle.sin();

        let mut i = 0;
        while i < n {
            let mut w_re = 1.0f32;
            let mut w_im = 0.0f32;
            for j in 0..half {
                let u_re = re[i + j];
                let u_im = im[i + j];
                let v_re = re[i + j + half] * w_re - im[i + j + half] * w_im;
                let v_im = re[i + j + half] * w_im + im[i + j + half] * w_re;

                re[i + j] = u_re + v_re;
                im[i + j] = u_im + v_im;
                re[i + j + half] = u_re - v_re;
                im[i + j + half] = u_im - v_im;

                let next_w_re = w_re * w_step_re - w_im * w_step_im;
                let next_w_im = w_re * w_step_im + w_im * w_step_re;
                w_re = next_w_re;
                w_im = next_w_im;
            }
            i += len;
        }
        len <<= 1;
    }
}

/// In-place Radix-2 Inverse Fast Fourier Transform.
pub fn ifft_radix2(re: &mut [f32], im: &mut [f32]) {
    for val in im.iter_mut() {
        *val = -*val;
    }
    fft_radix2(re, im);
    let scale = 1.0 / (re.len() as f32);
    for i in 0..re.len() {
        re[i] *= scale;
        im[i] = -im[i] * scale;
    }
}

/// Compute spectral magnitude: |X[k]| = sqrt(re^2 + im^2) for positive frequencies [0..HALF_FFT].
pub fn compute_magnitude(re: &[f32], im: &[f32], mag: &mut [f32]) {
    assert!(mag.len() >= HALF_FFT);
    for k in 0..HALF_FFT {
        mag[k] = (re[k] * re[k] + im[k] * im[k]).sqrt();
    }
}

/// Compute power spectrum: P[k] = re^2 + im^2 for positive frequencies [0..HALF_FFT].
pub fn compute_power(re: &[f32], im: &[f32], power: &mut [f32]) {
    assert!(power.len() >= HALF_FFT);
    for k in 0..HALF_FFT {
        power[k] = re[k] * re[k] + im[k] * im[k];
    }
}

/// Applies frequency-domain gain vector G[k] and reconstructs conjugate symmetric spectrum.
pub fn apply_spectral_gains(re: &mut [f32], im: &mut [f32], gains: &[f32]) {
    assert!(gains.len() >= HALF_FFT);
    let n = re.len();

    // Positive frequencies
    for k in 0..HALF_FFT {
        let g = gains[k];
        re[k] *= g;
        im[k] *= g;
    }

    // Enforce conjugate symmetry for real inverse transform: X[N - k] = X[k]*
    for k in 1..HALF_FFT - 1 {
        re[n - k] = re[k];
        im[n - k] = -im[k];
    }
}

/// Hann analysis window of length N.
pub fn create_hann_window(len: usize) -> Vec<f32> {
    let mut win = Vec::with_capacity(len);
    for n in 0..len {
        win.push(0.5 * (1.0 - (2.0 * PI * (n as f32) / (len as f32)).cos()));
    }
    win
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_fft_ifft_roundtrip_identity() {
        let original: Vec<f32> = (0..FFT_SIZE)
            .map(|i| (2.0 * PI * 4.0 * (i as f32) / (FFT_SIZE as f32)).sin() * 1000.0)
            .collect();

        let mut re = original.clone();
        let mut im = vec![0.0f32; FFT_SIZE];

        fft_radix2(&mut re, &mut im);

        // Magnitude should peak at bin 4 and bin 512-4
        let mut mag = vec![0.0f32; HALF_FFT];
        compute_magnitude(&re, &im, &mut mag);
        assert!(mag[4] > 200000.0);

        ifft_radix2(&mut re, &mut im);

        for i in 0..FFT_SIZE {
            assert!(
                (re[i] - original[i]).abs() < 1e-2,
                "FFT roundtrip divergence at index {}: orig={}, rec={}",
                i,
                original[i],
                re[i]
            );
        }
    }

    #[test]
    fn test_unit_gain_reconstruction() {
        let original: Vec<f32> = (0..FFT_SIZE).map(|i| (i as f32) * 2.0).collect();
        let mut re = original.clone();
        let mut im = vec![0.0f32; FFT_SIZE];

        fft_radix2(&mut re, &mut im);

        let unit_gains = vec![1.0f32; HALF_FFT];
        apply_spectral_gains(&mut re, &mut im, &unit_gains);

        ifft_radix2(&mut re, &mut im);

        for i in 0..FFT_SIZE {
            assert!((re[i] - original[i]).abs() < 1e-2);
        }
    }
}
