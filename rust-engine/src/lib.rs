/*
 * Copyright (c) 2026 KHATAWAS Project
 * Rust-first computational engine for Android Kirana voice ledger.
 * High-performance numerical DSP and financial intelligence engine.
 */

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::sync::Mutex;

pub mod audio;
pub mod dsp;
pub mod ledger;

use dsp::fir::FirDecimator3;
use ledger::LedgerEngine;

static GLOBAL_FIR: Mutex<Option<FirDecimator3>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn rust_engine_version() -> *const c_char {
    static VERSION: &[u8] = b"KHATAWAS-RustEngine-v1.0.0\0";
    VERSION.as_ptr() as *const c_char
}

#[no_mangle]
pub extern "C" fn rust_fir_process(
    input: *const f32,
    in_len: usize,
    output: *mut f32,
    out_len: usize,
) -> i32 {
    if input.is_null() || output.is_null() || in_len == 0 || out_len == 0 {
        return 0;
    }

    let in_slice = unsafe { std::slice::from_raw_parts(input, in_len) };
    let out_slice = unsafe { std::slice::from_raw_parts_mut(output, out_len) };

    let mut guard = GLOBAL_FIR.lock().unwrap();
    if guard.is_none() {
        *guard = Some(FirDecimator3::new());
    }

    let written = guard.as_mut().unwrap().process(in_slice, out_slice);
    written as i32
}

#[no_mangle]
pub extern "C" fn rust_fir_reset() {
    let mut guard = GLOBAL_FIR.lock().unwrap();
    if let Some(fir) = guard.as_mut() {
        fir.reset();
    }
}

/// Parses a speech utterance transcript and copies JSON result to out_buf.
/// Returns number of bytes written, or -1 if buffer was too small.
#[no_mangle]
pub extern "C" fn rust_parse_utterance(
    input_utf8: *const c_char,
    out_buf: *mut c_char,
    out_capacity: usize,
) -> i32 {
    if input_utf8.is_null() || out_buf.is_null() || out_capacity == 0 {
        return -1;
    }

    let c_str = unsafe { CStr::from_ptr(input_utf8) };
    let input_str = match c_str.to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let result = LedgerEngine::parse_structured(input_str);
    let json_res = result.to_json();
    let c_res = match CString::new(json_res) {
        Ok(c) => c,
        Err(_) => return -1,
    };

    let bytes = c_res.as_bytes_with_nul();
    if bytes.len() > out_capacity {
        return -1;
    }

    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr() as *const c_char, out_buf, bytes.len());
    }

    (bytes.len() - 1) as i32
}

#[no_mangle]
pub extern "C" fn rust_calculate_balance(
    credits: *const i64,
    c_len: usize,
    payments: *const i64,
    p_len: usize,
    reversals: *const i64,
    r_len: usize,
) -> i64 {
    let c_slice = if credits.is_null() || c_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(credits, c_len) }
    };

    let p_slice = if payments.is_null() || p_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(payments, p_len) }
    };

    let r_slice = if reversals.is_null() || r_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(reversals, r_len) }
    };

    LedgerEngine::calculate_balance_paise(c_slice, p_slice, r_slice)
}
