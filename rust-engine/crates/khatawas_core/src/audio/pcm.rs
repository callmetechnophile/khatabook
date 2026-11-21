/*
 * Copyright (c) 2026 KHATAWAS Project
 * Fast allocation-free PCM16 <-> Float conversions and boundary clamping.
 */

/// Convert little-endian PCM16 byte slice to raw amplitude float samples [-32768.0, 32767.0].
/// Matches existing KHATAWAS AudioPipeline amplitude scale for RNNoise and FIR processing.
pub fn pcm16_le_to_f32(bytes: &[u8], samples_out: &mut [f32]) -> usize {
    let sample_count = bytes.len() / 2;
    let count = sample_count.min(samples_out.len());

    for i in 0..count {
        let low = bytes[i * 2] as i16;
        let high = bytes[i * 2 + 1] as i16;
        let val = (high << 8) | (low & 0xFF);
        samples_out[i] = val as f32;
    }
    count
}

/// Convert slice of i16 signed shorts to raw amplitude float samples [-32768.0, 32767.0].
pub fn i16_to_f32(shorts: &[i16], samples_out: &mut [f32]) -> usize {
    let count = shorts.len().min(samples_out.len());
    for i in 0..count {
        samples_out[i] = shorts[i] as f32;
    }
    count
}

/// Convert little-endian PCM16 byte slice to normalized float samples [-1.0, 1.0].
pub fn pcm16_le_to_f32_normalized(bytes: &[u8], samples_out: &mut [f32]) -> usize {
    let sample_count = bytes.len() / 2;
    let count = sample_count.min(samples_out.len());

    for i in 0..count {
        let low = bytes[i * 2] as i16;
        let high = bytes[i * 2 + 1] as i16;
        let val = (high << 8) | (low & 0xFF);
        samples_out[i] = (val as f32) / 32768.0;
    }
    count
}

/// Convert raw amplitude float samples to little-endian PCM16 bytes with saturation clamping.
/// Saturation bounds: [-32768, 32767].
pub fn f32_to_pcm16_le(samples: &[f32], bytes_out: &mut [u8]) -> usize {
    let max_samples = bytes_out.len() / 2;
    let count = samples.len().min(max_samples);

    for (i, &sample) in samples[..count].iter().enumerate() {
        let clamped = if sample.is_nan() {
            0i16
        } else if sample >= 32767.0 {
            32767i16
        } else if sample <= -32768.0 {
            -32768i16
        } else {
            sample as i16
        };

        bytes_out[i * 2] = (clamped & 0xFF) as u8;
        bytes_out[i * 2 + 1] = ((clamped >> 8) & 0xFF) as u8;
    }
    count * 2
}

/// Convert raw amplitude float samples to i16 shorts with saturation clamping.
pub fn f32_to_i16(samples: &[f32], shorts_out: &mut [i16]) -> usize {
    let count = samples.len().min(shorts_out.len());
    for (i, &sample) in samples[..count].iter().enumerate() {
        shorts_out[i] = if sample.is_nan() {
            0i16
        } else if sample >= 32767.0 {
            32767i16
        } else if sample <= -32768.0 {
            -32768i16
        } else {
            sample as i16
        };
    }
    count
}

/// Convert normalized float samples [-1.0, 1.0] to little-endian PCM16 bytes.
pub fn f32_normalized_to_pcm16_le(samples: &[f32], bytes_out: &mut [u8]) -> usize {
    let max_samples = bytes_out.len() / 2;
    let count = samples.len().min(max_samples);

    for (i, &sample) in samples[..count].iter().enumerate() {
        let scaled = sample * 32767.0;
        let clamped = if scaled.is_nan() {
            0i16
        } else if scaled >= 32767.0 {
            32767i16
        } else if scaled <= -32768.0 {
            -32768i16
        } else {
            scaled.round() as i16
        };

        bytes_out[i * 2] = (clamped & 0xFF) as u8;
        bytes_out[i * 2 + 1] = ((clamped >> 8) & 0xFF) as u8;
    }
    count * 2
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_zero_silence() {
        let bytes = [0u8; 10];
        let mut floats = [1.0f32; 5];
        let converted = pcm16_le_to_f32(&bytes, &mut floats);
        assert_eq!(converted, 5);
        for &f in &floats {
            assert_eq!(f, 0.0);
        }

        let mut out_bytes = [1u8; 10];
        let serialized = f32_to_pcm16_le(&floats, &mut out_bytes);
        assert_eq!(serialized, 10);
        for &b in &out_bytes {
            assert_eq!(b, 0);
        }
    }

    #[test]
    fn test_max_positive_and_min_negative_pcm16() {
        let input_shorts = [-32768i16, 32767i16, 0i16, 16384i16, -16384i16];
        let mut floats = [0.0f32; 5];
        i16_to_f32(&input_shorts, &mut floats);

        assert_eq!(floats[0], -32768.0);
        assert_eq!(floats[1], 32767.0);
        assert_eq!(floats[2], 0.0);
        assert_eq!(floats[3], 16384.0);
        assert_eq!(floats[4], -16384.0);

        let mut out_bytes = [0u8; 10];
        let bytes_written = f32_to_pcm16_le(&floats, &mut out_bytes);
        assert_eq!(bytes_written, 10);

        let mut reconstructed = [0.0f32; 5];
        pcm16_le_to_f32(&out_bytes, &mut reconstructed);
        assert_eq!(floats, reconstructed);
    }

    #[test]
    fn test_saturation_clipping() {
        let extreme_floats = [50000.0f32, -60000.0f32, 32767.8f32, -32768.9f32];
        let mut shorts = [0i16; 4];
        f32_to_i16(&extreme_floats, &mut shorts);

        assert_eq!(shorts[0], 32767);
        assert_eq!(shorts[1], -32768);
        assert_eq!(shorts[2], 32767);
        assert_eq!(shorts[3], -32768);
    }

    #[test]
    fn test_nan_handling() {
        let nan_floats = [f32::NAN, 100.0f32];
        let mut shorts = [0i16; 2];
        f32_to_i16(&nan_floats, &mut shorts);
        assert_eq!(shorts[0], 0);
        assert_eq!(shorts[1], 100);
    }

    #[test]
    fn test_normalized_conversion() {
        let bytes = [0x00, 0x00, 0xFF, 0x7F, 0x00, 0x80]; // 0, 32767, -32768
        let mut norm = [0.0f32; 3];
        pcm16_le_to_f32_normalized(&bytes, &mut norm);

        assert_eq!(norm[0], 0.0);
        assert!((norm[1] - 0.999969).abs() < 1e-4);
        assert_eq!(norm[2], -1.0);
    }
}
