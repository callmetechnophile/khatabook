/*
 * Copyright (c) 2026 KHATAWAS Project
 * Platform-independent core computational engine for Kirana voice ledger.
 */

pub mod audio;
pub mod dsp;
pub mod ledger;

pub fn core_version() -> &'static str {
    "KHATAWAS-Core-v0.1.0"
}

/// Core integer addition for integration and arithmetic integrity testing.
pub fn core_add(a: i32, b: i32) -> i32 {
    a.saturating_add(b)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_core_version() {
        assert_eq!(core_version(), "KHATAWAS-Core-v0.1.0");
    }

    #[test]
    fn test_core_add() {
        assert_eq!(core_add(10, 20), 30);
        assert_eq!(core_add(-5, 5), 0);
        assert_eq!(core_add(i32::MAX, 1), i32::MAX); // Saturating addition
    }
}
