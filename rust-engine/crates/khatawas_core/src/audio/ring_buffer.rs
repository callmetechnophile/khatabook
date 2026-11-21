/*
 * Copyright (c) 2026 KHATAWAS Project
 * High-performance bounded circular ring buffer for real-time PCM audio streaming.
 * Includes explicit overflow and underflow telemetry and zero allocation in steady state.
 */

#[derive(Debug, Clone)]
pub struct AudioRingBuffer {
    buffer: Vec<f32>,
    capacity: usize,
    read_pos: usize,
    write_pos: usize,
    available_samples: usize,
    overflow_count: u64,
    underflow_count: u64,
}

impl AudioRingBuffer {
    /// Creates a bounded circular ring buffer with fixed capacity in samples.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "Capacity must be greater than zero");
        Self {
            buffer: vec![0.0f32; capacity],
            capacity,
            read_pos: 0,
            write_pos: 0,
            available_samples: 0,
            overflow_count: 0,
            underflow_count: 0,
        }
    }

    /// Fixed maximum sample capacity of this buffer.
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// Number of samples currently available for reading.
    pub fn available(&self) -> usize {
        self.available_samples
    }

    /// Remaining free space in samples before buffer overflows.
    pub fn free_space(&self) -> usize {
        self.capacity - self.available_samples
    }

    /// Number of sample write attempts that dropped due to overflow.
    pub fn overflow_count(&self) -> u64 {
        self.overflow_count
    }

    /// Number of sample read attempts that starved due to underflow.
    pub fn underflow_count(&self) -> u64 {
        self.underflow_count
    }

    /// Write samples into the circular buffer.
    /// Returns the number of samples actually written.
    /// If incoming data exceeds free space, writes as much as possible and increments overflow count.
    pub fn write(&mut self, data: &[f32]) -> usize {
        let space = self.free_space();
        let count = data.len().min(space);
        let dropped = data.len() - count;

        if dropped > 0 {
            self.overflow_count += dropped as u64;
        }

        for i in 0..count {
            self.buffer[self.write_pos] = data[i];
            self.write_pos = (self.write_pos + 1) % self.capacity;
        }
        self.available_samples += count;
        count
    }

    /// Read available samples from the circular buffer into `out`.
    /// Returns the number of samples actually transferred.
    /// If requested samples exceed available samples, transfers all available and increments underflow count.
    pub fn read(&mut self, out: &mut [f32]) -> usize {
        let count = out.len().min(self.available_samples);
        let starved = out.len() - count;

        if starved > 0 {
            self.underflow_count += starved as u64;
        }

        for i in 0..count {
            out[i] = self.buffer[self.read_pos];
            self.read_pos = (self.read_pos + 1) % self.capacity;
        }
        self.available_samples -= count;
        count
    }

    /// Peek at available samples without advancing read pointer.
    pub fn peek(&self, out: &mut [f32]) -> usize {
        let count = out.len().min(self.available_samples);
        let mut r = self.read_pos;
        for i in 0..count {
            out[i] = self.buffer[r];
            r = (r + 1) % self.capacity;
        }
        count
    }

    /// Reset read/write pointers and clear all buffer contents.
    pub fn clear(&mut self) {
        self.read_pos = 0;
        self.write_pos = 0;
        self.available_samples = 0;
    }

    /// Reset both content and telemetry counters.
    pub fn reset_telemetry(&mut self) {
        self.clear();
        self.overflow_count = 0;
        self.underflow_count = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ring_buffer_fifo() {
        let mut ring = AudioRingBuffer::new(1024);
        let in_data = [1.0, 2.0, 3.0, 4.0, 5.0];
        assert_eq!(ring.write(&in_data), 5);
        assert_eq!(ring.available(), 5);
        assert_eq!(ring.free_space(), 1024 - 5);

        let mut out_data = [0.0; 3];
        assert_eq!(ring.read(&mut out_data), 3);
        assert_eq!(out_data, [1.0, 2.0, 3.0]);
        assert_eq!(ring.available(), 2);

        let in_more = [6.0, 7.0];
        assert_eq!(ring.write(&in_more), 2);
        assert_eq!(ring.available(), 4);

        let mut out_rest = [0.0; 4];
        assert_eq!(ring.read(&mut out_rest), 4);
        assert_eq!(out_rest, [4.0, 5.0, 6.0, 7.0]);
        assert_eq!(ring.available(), 0);
        assert_eq!(ring.overflow_count(), 0);
        assert_eq!(ring.underflow_count(), 0);
    }

    #[test]
    fn test_overflow_detection() {
        let mut ring = AudioRingBuffer::new(10);
        let in_data = [1.0f32; 15];
        let written = ring.write(&in_data);
        assert_eq!(written, 10);
        assert_eq!(ring.available(), 10);
        assert_eq!(ring.overflow_count(), 5);
    }

    #[test]
    fn test_underflow_detection() {
        let mut ring = AudioRingBuffer::new(10);
        let in_data = [42.0f32; 3];
        ring.write(&in_data);

        let mut out_data = [0.0f32; 7];
        let read = ring.read(&mut out_data);
        assert_eq!(read, 3);
        assert_eq!(ring.underflow_count(), 4);
    }

    #[test]
    fn test_wraparound_continuity() {
        let mut ring = AudioRingBuffer::new(8);
        for iter in 0..100 {
            let chunk = [iter as f32, (iter + 1) as f32];
            assert_eq!(ring.write(&chunk), 2);

            let mut out = [0.0f32; 2];
            assert_eq!(ring.read(&mut out), 2);
            assert_eq!(out, chunk);
        }
        assert_eq!(ring.available(), 0);
        assert_eq!(ring.overflow_count(), 0);
        assert_eq!(ring.underflow_count(), 0);
    }
}
