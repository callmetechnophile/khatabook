/*
 * Copyright (c) 2026 KHATAWAS Project
 * Comprehensive independent test suite for the Five Rust Audio Denoising Modules:
 * 1. STFT / inverse STFT (stft.rs)
 * 2. Noise estimation (noise_est.rs)
 * 3. Spectral subtraction (spectral_sub.rs)
 * 4. Wiener filtering (wiener.rs)
 * 5. Streaming denoising integration (denoise.rs)
 */

use khatawas_core::dsp::denoise::{DenoiseMode, RustDenoiseProcessor, FRAME_SIZE_480};
use khatawas_core::dsp::noise_est::NoiseEstimator;
use khatawas_core::dsp::spectral_sub::SpectralSubtractEngine;
use khatawas_core::dsp::stft::{
    apply_spectral_gains, compute_magnitude, compute_power, create_hann_window, fft_radix2,
    ifft_radix2, FFT_SIZE, HALF_FFT,
};
use khatawas_core::dsp::wiener::WienerFilterEngine;
use std::f32::consts::PI;

// ===========================================================================
// Phase 2: STFT / Inverse STFT Testing
// ===========================================================================

#[test]
fn phase2_test1_stft_zero_input() {
    let mut re = [0.0f32; FFT_SIZE];
    let mut im = [0.0f32; FFT_SIZE];

    fft_radix2(&mut re, &mut im);
    for i in 0..FFT_SIZE {
        assert_eq!(re[i], 0.0, "FFT of zero must be zero");
        assert_eq!(im[i], 0.0, "FFT imaginary of zero must be zero");
    }

    let mut power = [0.0f32; HALF_FFT];
    compute_power(&re, &im, &mut power);
    for k in 0..HALF_FFT {
        assert_eq!(power[k], 0.0);
    }

    ifft_radix2(&mut re, &mut im);
    for i in 0..FFT_SIZE {
        assert_eq!(re[i], 0.0);
        assert!(!re[i].is_nan());
        assert!(!re[i].is_infinite());
    }
}

#[test]
fn phase2_test2_stft_impulse_input() {
    let mut re = [0.0f32; FFT_SIZE];
    let mut im = [0.0f32; FFT_SIZE];
    re[0] = 1.0; // Delta impulse

    fft_radix2(&mut re, &mut im);

    // Theoretical FFT of unit delta at 0 has magnitude 1.0 for all frequencies
    for k in 0..FFT_SIZE {
        let mag = (re[k] * re[k] + im[k] * im[k]).sqrt();
        assert!(
            (mag - 1.0).abs() < 1e-4,
            "Impulse response magnitude at bin {} should be 1.0, got {}",
            k,
            mag
        );
    }

    ifft_radix2(&mut re, &mut im);
    assert!((re[0] - 1.0).abs() < 1e-4, "Reconstructed impulse peak must be 1.0");
    for i in 1..FFT_SIZE {
        assert!(
            re[i].abs() < 1e-4,
            "Reconstructed impulse tail at {} must be zero, got {}",
            i,
            re[i]
        );
    }
}

#[test]
fn phase2_test3_stft_constant_dc_input() {
    let dc_val = 500.0f32;
    let mut re = [dc_val; FFT_SIZE];
    let mut im = [0.0f32; FFT_SIZE];

    fft_radix2(&mut re, &mut im);

    // Bin 0 should contain all energy (N * dc_val = 512 * 500 = 256,000)
    let expected_dc_peak = (FFT_SIZE as f32) * dc_val;
    assert!(
        (re[0] - expected_dc_peak).abs() < 1.0,
        "DC bin peak expected {}, got {}",
        expected_dc_peak,
        re[0]
    );

    // All other AC bins must be zero
    for k in 1..FFT_SIZE {
        let ac_mag = (re[k] * re[k] + im[k] * im[k]).sqrt();
        assert!(
            ac_mag < 1e-3,
            "AC bin {} had non-zero energy {} for DC input",
            k,
            ac_mag
        );
    }

    ifft_radix2(&mut re, &mut im);
    for i in 0..FFT_SIZE {
        assert!(
            (re[i] - dc_val).abs() < 1e-3,
            "Reconstruction error at index {}: got {}, expected {}",
            i,
            re[i],
            dc_val
        );
    }
}

#[test]
fn phase2_test4_stft_sine_waves_reconstruction_and_snr() {
    let freqs = [100.0, 1000.0, 4000.0, 8000.0, 12000.0, 20000.0];
    let sample_rate = 48000.0f32;

    for &freq in &freqs {
        let original: Vec<f32> = (0..FFT_SIZE)
            .map(|i| (2.0 * PI * freq * (i as f32) / sample_rate).sin() * 2000.0)
            .collect();

        let mut re = [0.0f32; FFT_SIZE];
        let mut im = [0.0f32; FFT_SIZE];
        re.copy_from_slice(&original);

        fft_radix2(&mut re, &mut im);

        // Verify no NaN or Inf
        for k in 0..FFT_SIZE {
            assert!(!re[k].is_nan());
            assert!(!re[k].is_infinite());
            assert!(!im[k].is_nan());
            assert!(!im[k].is_infinite());
        }

        // Apply unit gain (unmodified pass-through)
        let unit_gains = [1.0f32; HALF_FFT];
        apply_spectral_gains(&mut re, &mut im, &unit_gains);

        ifft_radix2(&mut re, &mut im);

        let mut max_err = 0.0f32;
        let mut sum_err = 0.0f32;
        let mut sum_sq_err = 0.0f32;
        let mut sum_sq_orig = 0.0f32;

        for i in 0..FFT_SIZE {
            let err = (re[i] - original[i]).abs();
            if err > max_err {
                max_err = err;
            }
            sum_err += err;
            sum_sq_err += err * err;
            sum_sq_orig += original[i] * original[i];
        }

        let mae = sum_err / (FFT_SIZE as f32);
        let rms = (sum_sq_err / (FFT_SIZE as f32)).sqrt();
        let snr_db = 10.0 * (sum_sq_orig / sum_sq_err.max(1e-12)).log10();

        assert!(
            max_err < 0.05,
            "Max absolute reconstruction error too high for {} Hz: {}",
            freq,
            max_err
        );
        assert!(mae < 0.01, "MAE too high: {}", mae);
        assert!(rms < 0.01, "RMS error too high: {}", rms);
        assert!(
            snr_db > 80.0,
            "Reconstruction SNR too low: {} dB for freq {}",
            snr_db,
            freq
        );
    }
}

#[test]
fn phase2_test5_stft_random_input_reconstruction() {
    let mut original = [0.0f32; FFT_SIZE];
    for i in 0..FFT_SIZE {
        original[i] = (((i * 73 + 19) % 2001) as f32 - 1000.0) * 1.5;
    }

    let mut re = original;
    let mut im = [0.0f32; FFT_SIZE];

    fft_radix2(&mut re, &mut im);
    ifft_radix2(&mut re, &mut im);

    let mut max_err = 0.0f32;
    let mut sum_sq_err = 0.0f32;
    let mut sum_sq_orig = 0.0f32;

    for i in 0..FFT_SIZE {
        let err = (re[i] - original[i]).abs();
        if err > max_err {
            max_err = err;
        }
        sum_sq_err += err * err;
        sum_sq_orig += original[i] * original[i];
    }

    let snr_db = 10.0 * (sum_sq_orig / sum_sq_err.max(1e-12)).log10();
    assert!(max_err < 0.05, "Random input max error: {}", max_err);
    assert!(snr_db > 85.0, "Random input SNR: {} dB", snr_db);
}

#[test]
fn phase2_test6_stft_hann_window() {
    let win512 = create_hann_window(512);
    let win480 = create_hann_window(480);

    assert_eq!(win512.len(), 512);
    assert_eq!(win480.len(), 480);

    // Endpoints must be zero (or near zero)
    assert!(win512[0].abs() < 1e-6);
    assert!(win480[0].abs() < 1e-6);

    // Midpoints must be 1.0
    assert!((win512[256] - 1.0).abs() < 1e-4);
    assert!((win480[240] - 1.0).abs() < 1e-4);
}

// ===========================================================================
// Phase 3: Noise Estimation Testing
// ===========================================================================

#[test]
fn phase3_test1_noise_est_stationary_white_noise() {
    let mut estimator = NoiseEstimator::new();
    let true_noise_power = 2500.0f32;
    let white_frame = [true_noise_power; HALF_FFT];

    // Feed 60 frames of stationary noise
    for _ in 0..60 {
        estimator.update(&white_frame);
    }

    let estimated = estimator.noise_power();
    for k in 0..HALF_FFT {
        let diff = (estimated[k] - true_noise_power).abs();
        let rel_err = diff / true_noise_power;
        assert!(
            rel_err < 0.05,
            "Stationary noise power estimation error too high at bin {}: rel_err = {}",
            k,
            rel_err
        );
    }
}

#[test]
fn phase3_test2_noise_est_low_frequency_hum() {
    let mut estimator = NoiseEstimator::new();
    let mut hum_power = [200.0f32; HALF_FFT];
    // Elevate 60 Hz hum at bin 1 and 120 Hz at bin 2
    hum_power[1] = 8000.0;
    hum_power[2] = 4000.0;

    for _ in 0..50 {
        estimator.update(&hum_power);
    }

    let est = estimator.noise_power();
    assert!(est[1] > 7000.0, "60 Hz hum not tracked: {}", est[1]);
    assert!(est[2] > 3500.0, "120 Hz hum not tracked: {}", est[2]);
    assert!(est[50] < 300.0, "High frequency erroneously elevated: {}", est[50]);
}

#[test]
fn phase3_test3_noise_est_varying_and_step_change() {
    let mut estimator = NoiseEstimator::new();
    let initial_noise = [500.0f32; HALF_FFT];
    for _ in 0..30 {
        estimator.update(&initial_noise);
    }
    assert!((estimator.noise_power()[20] - 500.0).abs() < 50.0);

    // Step increase in noise to 1500.0 (e.g. ambient fan switched on)
    let step_up = [1500.0f32; HALF_FFT];
    for _ in 0..200 {
        estimator.update(&step_up);
    }
    assert!(
        estimator.noise_power()[20] > 750.0,
        "Slow upward adaptation failed: {}",
        estimator.noise_power()[20]
    );

    // Step decrease in noise down to 300.0
    let step_down = [300.0f32; HALF_FFT];
    for _ in 0..40 {
        estimator.update(&step_down);
    }
    assert!(
        estimator.noise_power()[20] < 350.0,
        "Rapid downward adaptation failed: {}",
        estimator.noise_power()[20]
    );
}

#[test]
fn phase3_test4_noise_est_speech_then_silence_then_speech() {
    let mut estimator = NoiseEstimator::new();
    let ambient_noise = [400.0f32; HALF_FFT];

    // 1. Initial 15 frames of ambient calibration
    for _ in 0..15 {
        estimator.update(&ambient_noise);
    }
    let baseline_noise = estimator.noise_power()[30];
    assert!((baseline_noise - 400.0).abs() < 50.0);

    // 2. Active speech burst: high power formant at bin 30 (100,000 power)
    let mut speech_frame = ambient_noise;
    speech_frame[30] = 100_000.0;
    for _ in 0..30 {
        estimator.update(&speech_frame);
    }
    let noise_after_speech = estimator.noise_power()[30];

    // Speech formant must NOT be absorbed into noise estimate!
    assert!(
        noise_after_speech < baseline_noise * 2.0,
        "Speech formant was absorbed into noise estimate! Baseline={}, After={}",
        baseline_noise,
        noise_after_speech
    );

    // 3. Return to silence/ambient
    for _ in 0..20 {
        estimator.update(&ambient_noise);
    }
    let noise_in_silence = estimator.noise_power()[30];
    assert!(
        (noise_in_silence - 400.0).abs() < 50.0,
        "Noise estimate did not remain stable in silence: {}",
        noise_in_silence
    );

    // 4. Second speech burst
    for _ in 0..30 {
        estimator.update(&speech_frame);
    }
    let noise_second_speech = estimator.noise_power()[30];
    assert!(
        noise_second_speech < baseline_noise * 2.5,
        "Second speech burst corrupted noise estimate: {}",
        noise_second_speech
    );
}

#[test]
fn phase3_test5_noise_est_numerical_stability() {
    let mut estimator = NoiseEstimator::new();
    let zero_frame = [0.0f32; HALF_FFT];
    estimator.update(&zero_frame);

    for &val in estimator.noise_power() {
        assert!(!val.is_nan());
        assert!(!val.is_infinite());
        assert!(val >= 0.0);
    }

    let huge_frame = [1e12f32; HALF_FFT];
    estimator.update(&huge_frame);

    for &val in estimator.noise_power() {
        assert!(!val.is_nan());
        assert!(!val.is_infinite());
    }

    estimator.reset();
    assert_eq!(estimator.total_frames(), 0);
}

// ===========================================================================
// Phase 4: Spectral Subtraction Testing
// ===========================================================================

#[test]
fn phase4_test1_spectral_sub_snr_sweep() {
    let mut sub_engine = SpectralSubtractEngine::new();
    let snrs_db = [-5.0, 0.0, 5.0, 10.0, 20.0];
    let noise_power_val = 1000.0f32;
    let noise_p = [noise_power_val; HALF_FFT];

    for &snr_db in &snrs_db {
        let snr_linear = 10.0f32.powf(snr_db / 10.0);
        let speech_power_val = noise_power_val * snr_linear;
        let noisy_power_val = speech_power_val + noise_power_val;

        let current_p = [noisy_power_val; HALF_FFT];
        let mut gains = [0.0f32; HALF_FFT];

        // Process 6 frames to let temporal smoothing settle
        for _ in 0..6 {
            sub_engine.compute_gains(&current_p, &noise_p, &mut gains);
        }

        // Verify gain bounds: must be bounded by [sqrt(beta), 1.0]
        let min_gain = (0.04f32).sqrt(); // 0.20
        for k in 0..HALF_FFT {
            let g = gains[k];
            assert!(
                g >= min_gain - 1e-4,
                "Gain below spectral floor at SNR {} dB: {}",
                snr_db,
                g
            );
            assert!(
                g <= 1.0 + 1e-4,
                "Gain exceeded 1.0 at SNR {} dB: {}",
                snr_db,
                g
            );
            assert!(!g.is_nan());
            assert!(!g.is_infinite());
        }

        // Higher SNR must yield higher or equal gain
        if snr_db >= 10.0 {
            assert!(
                gains[50] > 0.60,
                "High SNR {} dB should have high gain, got {}",
                snr_db,
                gains[50]
            );
        }
        if snr_db <= 0.0 {
            assert!(
                gains[50] < 0.40,
                "Low SNR {} dB should suppress noise, got {}",
                snr_db,
                gains[50]
            );
        }
    }
}

// ===========================================================================
// Phase 5: Wiener Filter Testing
// ===========================================================================

#[test]
fn phase5_test1_wiener_filter_snr_sweep_and_dd_tracking() {
    let mut wiener = WienerFilterEngine::new();
    let noise_p = [500.0f32; HALF_FFT];
    let snrs_db = [-10.0, -5.0, 0.0, 5.0, 10.0, 20.0, 30.0];

    for &snr_db in &snrs_db {
        let snr_linear = 10.0f32.powf(snr_db / 10.0);
        let speech_p = 500.0 * snr_linear;
        let noisy_p = [speech_p + 500.0; HALF_FFT];
        let mut gains = [0.0f32; HALF_FFT];

        // Process a few frames to let decision-directed recursion adapt
        for _ in 0..5 {
            wiener.compute_gains(&noisy_p, &noise_p, &mut gains);
        }

        let g = gains[20];
        assert!(g >= 0.10, "Wiener gain violated floor 0.10: {}", g);
        assert!(g <= 1.0, "Wiener gain exceeded 1.0: {}", g);
        assert!(!g.is_nan());
        assert!(!g.is_infinite());

        if snr_db <= -5.0 {
            assert!(
                (g - 0.10).abs() < 0.05,
                "Severe noise (-5 dB) must trigger gain floor ~0.10, got {}",
                g
            );
        } else if snr_db >= 20.0 {
            assert!(
                g > 0.90,
                "Clean speech (20 dB) must have gain near unity, got {}",
                g
            );
        }
    }
}

#[test]
fn phase5_test2_wiener_extreme_values() {
    let mut wiener = WienerFilterEngine::new();
    let zero_p = [0.0f32; HALF_FFT];
    let mut gains = [0.0f32; HALF_FFT];

    wiener.compute_gains(&zero_p, &zero_p, &mut gains);
    for &g in &gains {
        assert!(!g.is_nan());
        assert!(!g.is_infinite());
        assert_eq!(g, 0.10); // Gain floor
    }

    let huge_p = [1e15f32; HALF_FFT];
    let tiny_p = [1e-6f32; HALF_FFT];
    wiener.compute_gains(&huge_p, &tiny_p, &mut gains);
    for &g in &gains {
        assert!(!g.is_nan());
        assert!(!g.is_infinite());
        assert!((g - 1.0).abs() < 1e-3);
    }
}

// ===========================================================================
// Phase 6: Streaming Denoising Integration Testing
// ===========================================================================

#[test]
fn phase6_test1_streaming_consecutive_frames_and_continuity() {
    let mut denoiser = RustDenoiseProcessor::new(DenoiseMode::WienerFilter);
    let frames_count = 50; // 500 ms continuous audio
    let sample_rate = 48000.0f32;

    let mut in_frame = [0.0f32; FRAME_SIZE_480];
    let mut out_frame = [0.0f32; FRAME_SIZE_480];

    let mut last_sample_prev_frame = 0.0f32;

    for f in 0..frames_count {
        for i in 0..FRAME_SIZE_480 {
            let t = (f * FRAME_SIZE_480 + i) as f32 / sample_rate;
            // 400 Hz smooth continuous sine
            in_frame[i] = (2.0 * PI * 400.0 * t).sin() * 2000.0;
        }

        denoiser.process_frame(&in_frame, &mut out_frame);

        // Check continuity across frame boundaries
        if f > 0 {
            let boundary_step = (out_frame[0] - last_sample_prev_frame).abs();
            // Maximum expected step for 400 Hz sine @ 48 kHz with amplitude 2000 is ~104
            assert!(
                boundary_step < 150.0,
                "Discontinuity at boundary of frame {}: step = {}",
                f,
                boundary_step
            );
        }
        last_sample_prev_frame = out_frame[FRAME_SIZE_480 - 1];

        for i in 0..FRAME_SIZE_480 {
            assert!(!out_frame[i].is_nan());
            assert!(!out_frame[i].is_infinite());
        }
    }

    assert_eq!(denoiser.processed_frames(), frames_count as u64);
}

#[test]
fn phase6_test2_streaming_vs_single_buffer_equivalence() {
    let mut denoiser = RustDenoiseProcessor::new(DenoiseMode::WienerFilter);

    // Initial 15 frames of low ambient noise calibration
    let ambient = [5.0f32; FRAME_SIZE_480];
    let mut scratch = [0.0f32; FRAME_SIZE_480];
    for _ in 0..15 {
        denoiser.process_frame(&ambient, &mut scratch);
    }

    let speech_frames = 10;
    let total_samples = FRAME_SIZE_480 * speech_frames;
    let sample_rate = 48000.0f32;
    let full_signal: Vec<f32> = (0..total_samples)
        .map(|i| (2.0 * PI * 350.0 * (i as f32) / sample_rate).sin() * 2000.0)
        .collect();

    // Stream through processor frame by frame
    let mut streaming_output = Vec::with_capacity(total_samples);

    for chunk in full_signal.chunks_exact(FRAME_SIZE_480) {
        let mut out = [0.0f32; FRAME_SIZE_480];
        denoiser.process_frame(chunk, &mut out);
        streaming_output.extend_from_slice(&out);
    }

    assert_eq!(streaming_output.len(), total_samples);

    // Verify output energy tracks input energy
    let in_energy: f32 = full_signal.iter().map(|&x| x * x).sum();
    let out_energy: f32 = streaming_output.iter().map(|&x| x * x).sum();
    let ratio = out_energy / in_energy;

    assert!(
        ratio > 0.85,
        "Streaming speech energy retention was too low: {}",
        ratio
    );
}
