/*
 * Copyright (c) 2026 KHATAWAS Project
 * High-performance circular ring buffer for real-time PCM audio streaming.
 */

pub struct AudioRingBuffer {
    buffer: Vec<f32>,
    capacity: usize,
    read_pos: usize,
    write_pos: usize,
    available_samples: usize,
}

impl AudioRingBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            buffer: vec![0.0f32; capacity],
            capacity,
            read_pos: 0,
            write_pos: 0,
            available_samples: 0,
        }
    }

    pub fn write(&mut self, data: &[f32]) -> usize {
        let space = self.capacity - self.available_samples;
        let count = data.len().min(space);
        for i in 0..count {
            self.buffer[self.write_pos] = data[i];
            self.write_pos = (self.write_pos + 1) % self.capacity;
        }
        self.available_samples += count;
        count
    }

    pub fn read(&mut self, out: &mut [f32]) -> usize {
        let count = out.len().min(self.available_samples);
        for i in 0..count {
            out[i] = self.buffer[self.read_pos];
            self.read_pos = (self.read_pos + 1) % self.capacity;
        }
        self.available_samples -= count;
        count
    }

    pub fn available(&self) -> usize {
        self.available_samples
    }

    pub fn clear(&mut self) {
        self.read_pos = 0;
        self.write_pos = 0;
        self.available_samples = 0;
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
    }
}
