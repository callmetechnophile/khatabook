/*
 * Copyright (c) 2026 KHATAWAS Project
 * Fast allocation-free PCM16 <-> Float conversions and buffer normalization.
 */

/// Convert little-endian PCM16 byte slice to normalized floating-point samples.
pub fn pcm16_le_to_f32(bytes: &[u8], samples_out: &mut [f32]) -> usize {
    let sample_count = bytes.len() / 2;
    assert!(samples_out.len() >= sample_count, "Output slice too small");

    for i in 0..sample_count {
        let low = bytes[i * 2] as i16;
        let high = bytes[i * 2 + 1] as i16;
        let val = (high << 8) | (low & 0xFF);
        samples_out[i] = val as f32;
    }
    sample_count
}

/// Convert floating-point samples back to little-endian PCM16 bytes with saturation clamping.
pub fn f32_to_pcm16_le(samples: &[f32], bytes_out: &mut [u8]) -> usize {
    let required_bytes = samples.len() * 2;
    assert!(bytes_out.len() >= required_bytes, "Output byte slice too small");

    for (i, &sample) in samples.iter().enumerate() {
        // Clamp to signed 16-bit range
        let clamped = if sample > 32767.0 {
            32767i16
        } else if sample < -32768.0 {
            -32768i16
        } else {
            sample as i16
        };

        bytes_out[i * 2] = (clamped & 0xFF) as u8;
        bytes_out[i * 2 + 1] = ((clamped >> 8) & 0xFF) as u8;
    }
    required_bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pcm16_roundtrip() {
        let original_samples: [f32; 4] = [0.0, 1000.0, -1000.0, 32767.0];
        let mut bytes = [0u8; 8];
        f32_to_pcm16_le(&original_samples, &mut bytes);

        let mut reconstructed = [0.0f32; 4];
        pcm16_le_to_f32(&bytes, &mut reconstructed);

        for (orig, rec) in original_samples.iter().zip(reconstructed.iter()) {
            assert_eq!(*orig, *rec);
        }
    }
}
