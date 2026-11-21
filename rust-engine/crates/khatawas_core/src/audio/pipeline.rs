/*
 * Copyright (c) 2026 KHATAWAS Project
 * Comprehensive stateful streaming audio pipeline coordinator in Rust.
 * Manages frame accumulation, FIR 48k->16k decimation, and PCM16 serialization.
 */

use crate::audio::frame::AudioFrameManager;
use crate::audio::pcm::{f32_to_pcm16_le, pcm16_le_to_f32};
use crate::dsp::fir::FirDecimator3;

pub const INPUT_FRAME_SAMPLES_48K: usize = 480;  // 10ms at 48 kHz
pub const OUTPUT_FRAME_SAMPLES_16K: usize = 160; // 10ms at 16 kHz
pub const OUTPUT_FRAME_BYTES_16K: usize = 320;   // 160 samples * 2 bytes/sample

#[derive(Debug)]
pub struct AudioPipelineState {
    frame_manager: AudioFrameManager,
    fir_decimator: FirDecimator3,
    scratch_in: [f32; INPUT_FRAME_SAMPLES_48K],
    scratch_out: [f32; OUTPUT_FRAME_SAMPLES_16K],
    processed_frames: u64,
    total_input_samples: u64,
    total_output_bytes: u64,
}

impl Default for AudioPipelineState {
    fn default() -> Self {
        Self::new()
    }
}

impl AudioPipelineState {
    /// Creates a stateful audio pipeline configured for 48 kHz -> 16 kHz streaming.
    pub fn new() -> Self {
        Self {
            frame_manager: AudioFrameManager::new(INPUT_FRAME_SAMPLES_48K),
            fir_decimator: FirDecimator3::new(),
            scratch_in: [0.0f32; INPUT_FRAME_SAMPLES_48K],
            scratch_out: [0.0f32; OUTPUT_FRAME_SAMPLES_16K],
            processed_frames: 0,
            total_input_samples: 0,
            total_output_bytes: 0,
        }
    }

    /// Number of full 10ms frames successfully processed through the pipeline.
    pub fn processed_frames(&self) -> u64 {
        self.processed_frames
    }

    /// Total count of 48 kHz input samples ingested.
    pub fn total_input_samples(&self) -> u64 {
        self.total_input_samples
    }

    /// Total count of 16 kHz PCM16 bytes generated for STT streaming.
    pub fn total_output_bytes(&self) -> u64 {
        self.total_output_bytes
    }

    /// Resets frame accumulator and FIR filter history to zero.
    pub fn reset(&mut self) {
        self.frame_manager.clear();
        self.fir_decimator.reset();
        self.scratch_in = [0.0f32; INPUT_FRAME_SAMPLES_48K];
        self.scratch_out = [0.0f32; OUTPUT_FRAME_SAMPLES_16K];
    }

    /// Direct 10 ms frame processing:
    /// Takes exactly 480 float samples (e.g., after RNNoise has run at 48 kHz),
    /// applies 31-tap FIR decimation, and serializes into little-endian PCM16 bytes.
    ///
    /// Requires `output_bytes` to have at least 320 bytes capacity.
    /// Returns the number of bytes written (320 on success, or 0 if output too small).
    pub fn process_float_frame_to_pcm16(
        &mut self,
        input_floats: &[f32],
        output_bytes: &mut [u8],
    ) -> usize {
        assert_eq!(
            input_floats.len(),
            INPUT_FRAME_SAMPLES_48K,
            "Input float frame must be exactly 480 samples"
        );
        if output_bytes.len() < OUTPUT_FRAME_BYTES_16K {
            return 0;
        }

        let decimated_count = self.fir_decimator.process(input_floats, &mut self.scratch_out);
        assert_eq!(decimated_count, OUTPUT_FRAME_SAMPLES_16K);

        let bytes_written = f32_to_pcm16_le(&self.scratch_out, output_bytes);

        self.processed_frames += 1;
        self.total_input_samples += INPUT_FRAME_SAMPLES_48K as u64;
        self.total_output_bytes += bytes_written as u64;

        bytes_written
    }

    /// Streaming PCM16 ingestion:
    /// Ingests arbitrary-sized incoming PCM16 byte chunks (from AudioRecord),
    /// converts them to float, extracts complete 480-sample frames, decimates them,
    /// and appends the 16 kHz PCM16 output into `output_bytes`.
    ///
    /// Preserves sub-frame remnants in the frame manager across chunk boundaries.
    /// Returns the total number of 16 kHz PCM16 bytes written into `output_bytes`.
    pub fn push_and_process_pcm16(
        &mut self,
        input_pcm16_bytes: &[u8],
        output_bytes: &mut [u8],
    ) -> usize {
        let sample_count = input_pcm16_bytes.len() / 2;
        if sample_count == 0 {
            return 0;
        }

        // Convert chunk into scratch floats in stack-bounded batches
        let mut float_scratch = [0.0f32; 512];
        let mut offset = 0;
        while offset < input_pcm16_bytes.len() {
            let chunk_bytes = (input_pcm16_bytes.len() - offset).min(1024);
            let converted = pcm16_le_to_f32(
                &input_pcm16_bytes[offset..(offset + chunk_bytes)],
                &mut float_scratch,
            );
            self.frame_manager.push_samples(&float_scratch[..converted]);
            offset += chunk_bytes;
        }

        let mut total_written_bytes = 0;

        // Process all complete 480-sample frames accumulated
        while self.frame_manager.has_frame() {
            if (output_bytes.len() - total_written_bytes) < OUTPUT_FRAME_BYTES_16K {
                break; // Output buffer full
            }

            self.frame_manager.pop_frame(&mut self.scratch_in);
            let decimated = self.fir_decimator.process(&self.scratch_in, &mut self.scratch_out);
            assert_eq!(decimated, OUTPUT_FRAME_SAMPLES_16K);

            let bytes_written = f32_to_pcm16_le(
                &self.scratch_out,
                &mut output_bytes[total_written_bytes..],
            );
            total_written_bytes += bytes_written;

            self.processed_frames += 1;
            self.total_input_samples += INPUT_FRAME_SAMPLES_48K as u64;
            self.total_output_bytes += bytes_written as u64;
        }

        total_written_bytes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_exact_480_sample_frame() {
        let mut pipeline = AudioPipelineState::new();
        let input_floats = vec![1000.0f32; INPUT_FRAME_SAMPLES_48K];
        let mut out_bytes = [0u8; OUTPUT_FRAME_BYTES_16K];

        let written = pipeline.process_float_frame_to_pcm16(&input_floats, &mut out_bytes);
        assert_eq!(written, OUTPUT_FRAME_BYTES_16K);
        assert_eq!(pipeline.processed_frames(), 1);
        assert_eq!(pipeline.total_input_samples(), 480);
        assert_eq!(pipeline.total_output_bytes(), 320);
    }

    #[test]
    fn test_streaming_partial_pcm16_chunks() {
        let mut pipeline = AudioPipelineState::new();
        let mut out_bytes = [0u8; 1024];

        // Send 300 samples (600 bytes) -> insufficient for 480-sample frame
        let chunk1 = vec![0x10u8; 600];
        let written1 = pipeline.push_and_process_pcm16(&chunk1, &mut out_bytes);
        assert_eq!(written1, 0); // No full frame yet
        assert_eq!(pipeline.processed_frames(), 0);

        // Send another 200 samples (400 bytes) -> total 500 samples -> 1 full frame (480) + 20 remainder
        let chunk2 = vec![0x10u8; 400];
        let written2 = pipeline.push_and_process_pcm16(&chunk2, &mut out_bytes);
        assert_eq!(written2, OUTPUT_FRAME_BYTES_16K); // Exactly 320 bytes output
        assert_eq!(pipeline.processed_frames(), 1);
    }

    #[test]
    fn test_pipeline_reset() {
        let mut pipeline = AudioPipelineState::new();
        let input = vec![500.0f32; INPUT_FRAME_SAMPLES_48K];
        let mut out1 = [0u8; OUTPUT_FRAME_BYTES_16K];
        pipeline.process_float_frame_to_pcm16(&input, &mut out1);

        pipeline.reset();

        let mut out2 = [0u8; OUTPUT_FRAME_BYTES_16K];
        pipeline.process_float_frame_to_pcm16(&input, &mut out2);

        assert_eq!(out1, out2);
    }
}
