/*
 * Copyright (c) 2026 KHATAWAS Project
 * Audio frame manager for accumulating streaming chunks into fixed-size processing frames.
 */

#[derive(Debug, Clone)]
pub struct AudioFrameManager {
    frame_size: usize,
    accumulator: Vec<f32>,
    read_pos: usize,
}

impl AudioFrameManager {
    /// Creates a frame manager targeting a fixed frame size (e.g., 480 samples for 10ms at 48kHz).
    pub fn new(frame_size: usize) -> Self {
        assert!(frame_size > 0, "Frame size must be greater than zero");
        Self {
            frame_size,
            accumulator: Vec::with_capacity(frame_size * 4),
            read_pos: 0,
        }
    }

    /// Target frame size in samples.
    pub fn frame_size(&self) -> usize {
        self.frame_size
    }

    /// Current number of accumulated samples waiting to form a complete frame.
    pub fn buffered_samples(&self) -> usize {
        self.accumulator.len() - self.read_pos
    }

    /// Number of full frames ready for immediate extraction.
    pub fn ready_frames_count(&self) -> usize {
        self.buffered_samples() / self.frame_size
    }

    /// Push incoming partial or full sample slice into the accumulator.
    pub fn push_samples(&mut self, samples: &[f32]) {
        if samples.is_empty() {
            return;
        }

        // Compact remaining unread samples to start of vector if read_pos grew large
        if self.read_pos > self.frame_size * 2 {
            self.accumulator.drain(0..self.read_pos);
            self.read_pos = 0;
        }

        self.accumulator.extend_from_slice(samples);
    }

    /// Check if at least one complete frame is ready.
    pub fn has_frame(&self) -> bool {
        self.buffered_samples() >= self.frame_size
    }

    /// Pop one complete fixed-size frame into `frame_out`.
    /// Returns true if a complete frame was extracted, or false if insufficient samples.
    pub fn pop_frame(&mut self, frame_out: &mut [f32]) -> bool {
        assert!(frame_out.len() >= self.frame_size, "Output slice smaller than frame size");

        if !self.has_frame() {
            return false;
        }

        frame_out[..self.frame_size].copy_from_slice(
            &self.accumulator[self.read_pos..(self.read_pos + self.frame_size)],
        );
        self.read_pos += self.frame_size;
        true
    }

    /// Clear all accumulated samples and reset read position.
    pub fn clear(&mut self) {
        self.accumulator.clear();
        self.read_pos = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_exact_frame_input() {
        let mut mgr = AudioFrameManager::new(480);
        let input = vec![1.0f32; 480];
        mgr.push_samples(&input);

        assert!(mgr.has_frame());
        assert_eq!(mgr.ready_frames_count(), 1);

        let mut out = vec![0.0f32; 480];
        assert!(mgr.pop_frame(&mut out));
        assert_eq!(out, input);
        assert!(!mgr.has_frame());
    }

    #[test]
    fn test_partial_frame_accumulation() {
        let mut mgr = AudioFrameManager::new(480);
        let chunk1 = vec![2.0f32; 200];
        let chunk2 = vec![3.0f32; 280];

        mgr.push_samples(&chunk1);
        assert!(!mgr.has_frame());
        assert_eq!(mgr.buffered_samples(), 200);

        mgr.push_samples(&chunk2);
        assert!(mgr.has_frame());
        assert_eq!(mgr.buffered_samples(), 480);

        let mut out = vec![0.0f32; 480];
        assert!(mgr.pop_frame(&mut out));
        assert_eq!(&out[..200], &chunk1[..]);
        assert_eq!(&out[200..], &chunk2[..]);
        assert!(!mgr.has_frame());
    }

    #[test]
    fn test_multiple_frames_in_single_input() {
        let mut mgr = AudioFrameManager::new(480);
        let input = vec![5.0f32; 480 * 3 + 100]; // 3 full frames + 100 remainder
        mgr.push_samples(&input);

        assert_eq!(mgr.ready_frames_count(), 3);
        let mut out = vec![0.0f32; 480];

        for _ in 0..3 {
            assert!(mgr.pop_frame(&mut out));
        }

        assert!(!mgr.has_frame());
        assert_eq!(mgr.buffered_samples(), 100);
    }

    #[test]
    fn test_irregular_input_sizes() {
        let mut mgr = AudioFrameManager::new(10);
        let sizes = [3, 7, 5, 5, 2, 8, 1, 9];
        let mut total_pushed = 0;
        let mut frames_popped = 0;
        let mut out = [0.0f32; 10];

        for (i, &sz) in sizes.iter().enumerate() {
            let chunk = vec![i as f32; sz];
            mgr.push_samples(&chunk);
            total_pushed += sz;

            while mgr.has_frame() {
                assert!(mgr.pop_frame(&mut out));
                frames_popped += 1;
            }
        }

        assert_eq!(frames_popped, total_pushed / 10);
        assert_eq!(mgr.buffered_samples(), total_pushed % 10);
    }
}
