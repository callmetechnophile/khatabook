/*
 * Copyright (c) 2026 KHATAWAS Project
 * Android JNI integration library (khatawas_android) wrapping khatawas_core.
 */

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};
use std::sync::Mutex;

use khatawas_core::audio::pipeline::AudioPipelineState;
use khatawas_core::core_add;
use khatawas_core::core_version;
use khatawas_core::dsp::fir::FirDecimator3;
use khatawas_core::ledger::LedgerEngine;

type JNIEnv = *mut *const JNINativeInterface;
type JClass = *mut c_void;
type JString = *mut c_void;
type JByteArray = *mut c_void;
type JFloatArray = *mut c_void;
type JLongArray = *mut c_void;

#[repr(C)]
#[derive(Copy, Clone)]
pub struct JNINativeInterface {
    reserved: [*const c_void; 167],
    pub new_string_utf: unsafe extern "system" fn(JNIEnv, *const c_char) -> JString,
    pub get_string_utf_length: unsafe extern "system" fn(JNIEnv, JString) -> i32,
    pub get_string_utf_chars: unsafe extern "system" fn(JNIEnv, JString, *mut u8) -> *const c_char,
    pub release_string_utf_chars: unsafe extern "system" fn(JNIEnv, JString, *const c_char),
    pub get_array_length: unsafe extern "system" fn(JNIEnv, *mut c_void) -> i32,
    reserved_arrays: [*const c_void; 28], // 172..199 (NewObject..ReleaseDouble + GetBooleanArrayRegion)
    pub get_byte_array_region: unsafe extern "system" fn(JNIEnv, JByteArray, i32, i32, *mut i8), // 200
    reserved_after_byte_region: [*const c_void; 3], // 201..203 (Char, Short, Int)
    pub get_long_array_region: unsafe extern "system" fn(JNIEnv, JLongArray, i32, i32, *mut i64), // 204
    pub get_float_array_region: unsafe extern "system" fn(JNIEnv, JFloatArray, i32, i32, *mut f32), // 205
    pub get_double_array_region: *const c_void, // 206
    pub set_boolean_array_region: *const c_void, // 207
    pub set_byte_array_region: unsafe extern "system" fn(JNIEnv, JByteArray, i32, i32, *const i8), // 208
    reserved_set_after_byte: [*const c_void; 3], // 209..211 (Char, Short, Int)
    pub set_long_array_region: unsafe extern "system" fn(JNIEnv, JLongArray, i32, i32, *const i64), // 212
    pub set_float_array_region: unsafe extern "system" fn(JNIEnv, JFloatArray, i32, i32, *const f32), // 213
    pub set_double_array_region: *const c_void, // 214
}

const FIR_HANDLE_MAGIC: u32 = 0x4B485457; // 'KHTW'
const AUDIO_PIPELINE_MAGIC: u32 = 0x41554450; // 'AUDP'

#[repr(C)]
struct FirHandle {
    magic: u32,
    decimator: FirDecimator3,
}

#[repr(C)]
struct AudioPipelineHandle {
    magic: u32,
    pipeline: AudioPipelineState,
}

static FALLBACK_FIR: Mutex<Option<FirDecimator3>> = Mutex::new(None);

/// JNI function returning Rust engine version string.
#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeGetVersion(
    env: JNIEnv,
    _class: JClass,
) -> JString {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let version_str = CString::new(core_version()).unwrap_or_else(|_| CString::new("unknown").unwrap());
    let iface = **env;
    (iface.new_string_utf)(env, version_str.as_ptr())
}

/// JNI function for integer addition testing (Milestone 2 integration proof).
#[no_mangle]
pub extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAdd(
    _env: JNIEnv,
    _class: JClass,
    a: i32,
    b: i32,
) -> i32 {
    core_add(a, b)
}

/// JNI health check function returning boolean.
#[no_mangle]
pub extern "system" fn Java_com_khatawas_rust_RustEngine_nativeHealthCheck(
    _env: JNIEnv,
    _class: JClass,
) -> u8 {
    1
}

// ---------------------------------------------------------------------------
// Milestone 3: Lifecycle-safe Stateful FIR Decimator Handle Management
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_khatawas_rust_RustEngine_nativeFirInit(
    _env: JNIEnv,
    _class: JClass,
) -> i64 {
    let handle = Box::new(FirHandle {
        magic: FIR_HANDLE_MAGIC,
        decimator: FirDecimator3::new(),
    });
    Box::into_raw(handle) as i64
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeFirProcessHandle(
    env: JNIEnv,
    _class: JClass,
    handle: i64,
    input_arr: JFloatArray,
    output_arr: JFloatArray,
) -> i32 {
    if handle == 0 || env.is_null() || input_arr.is_null() || output_arr.is_null() {
        return 0;
    }

    let ptr = handle as *mut FirHandle;
    if (*ptr).magic != FIR_HANDLE_MAGIC {
        return -1; // Corrupted or invalid handle
    }
    let handle_ref = &mut *ptr;

    let iface = **env;
    let in_len = (iface.get_array_length)(env, input_arr);
    let out_len = (iface.get_array_length)(env, output_arr);
    if in_len <= 0 || out_len <= 0 {
        return 0;
    }

    let in_usize = in_len as usize;
    let out_usize = out_len as usize;

    let mut in_stack = [0.0f32; 1024];
    let mut out_stack = [0.0f32; 512];
    let (mut in_heap, mut out_heap);

    let in_slice: &mut [f32] = if in_usize <= in_stack.len() {
        &mut in_stack[..in_usize]
    } else {
        in_heap = vec![0.0f32; in_usize];
        &mut in_heap[..]
    };

    let out_slice: &mut [f32] = if out_usize <= out_stack.len() {
        &mut out_stack[..out_usize]
    } else {
        out_heap = vec![0.0f32; out_usize];
        &mut out_heap[..]
    };

    (iface.get_float_array_region)(env, input_arr, 0, in_len, in_slice.as_mut_ptr());

    let written = handle_ref.decimator.process(in_slice, out_slice);

    if written > 0 {
        (iface.set_float_array_region)(env, output_arr, 0, written as i32, out_slice.as_ptr());
    }

    written as i32
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeFirResetHandle(
    _env: JNIEnv,
    _class: JClass,
    handle: i64,
) -> u8 {
    if handle == 0 {
        return 0;
    }
    let ptr = handle as *mut FirHandle;
    if (*ptr).magic != FIR_HANDLE_MAGIC {
        return 0;
    }
    (*ptr).decimator.reset();
    1
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeFirDestroyHandle(
    _env: JNIEnv,
    _class: JClass,
    handle: i64,
) -> u8 {
    if handle == 0 {
        return 0;
    }
    let ptr = handle as *mut FirHandle;
    if (*ptr).magic == FIR_HANDLE_MAGIC {
        (*ptr).magic = 0;
        let _ = Box::from_raw(ptr);
        1
    } else {
        0
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeFirProcess(
    env: JNIEnv,
    _class: JClass,
    input_arr: JFloatArray,
    output_arr: JFloatArray,
) -> i32 {
    if env.is_null() || input_arr.is_null() || output_arr.is_null() {
        return 0;
    }
    let iface = **env;
    let in_len = (iface.get_array_length)(env, input_arr);
    let out_len = (iface.get_array_length)(env, output_arr);
    if in_len <= 0 || out_len <= 0 {
        return 0;
    }

    let in_usize = in_len as usize;
    let out_usize = out_len as usize;

    let mut in_stack = [0.0f32; 1024];
    let mut out_stack = [0.0f32; 512];
    let (mut in_heap, mut out_heap);

    let in_slice: &mut [f32] = if in_usize <= in_stack.len() {
        &mut in_stack[..in_usize]
    } else {
        in_heap = vec![0.0f32; in_usize];
        &mut in_heap[..]
    };

    let out_slice: &mut [f32] = if out_usize <= out_stack.len() {
        &mut out_stack[..out_usize]
    } else {
        out_heap = vec![0.0f32; out_usize];
        &mut out_heap[..]
    };

    (iface.get_float_array_region)(env, input_arr, 0, in_len, in_slice.as_mut_ptr());

    let mut guard = FALLBACK_FIR.lock().unwrap();
    if guard.is_none() {
        *guard = Some(FirDecimator3::new());
    }

    let written = guard.as_mut().unwrap().process(in_slice, out_slice);
    if written > 0 {
        (iface.set_float_array_region)(env, output_arr, 0, written as i32, out_slice.as_ptr());
    }

    written as i32
}

// ---------------------------------------------------------------------------
// Milestone 4: Stateful Audio Pipeline Core Management (JNI)
// ---------------------------------------------------------------------------

/// Create and initialize an AudioPipelineState instance.
/// Returns an opaque 64-bit handle.
#[no_mangle]
pub extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAudioPipelineCreate(
    _env: JNIEnv,
    _class: JClass,
) -> i64 {
    let handle = Box::new(AudioPipelineHandle {
        magic: AUDIO_PIPELINE_MAGIC,
        pipeline: AudioPipelineState::new(),
    });
    Box::into_raw(handle) as i64
}

/// Process a complete 480-sample float frame (e.g. from RNNoise) and output 320 bytes PCM16 at 16 kHz.
#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAudioPipelineProcessFloatFrame(
    env: JNIEnv,
    _class: JClass,
    handle: i64,
    in_floats: JFloatArray,
    out_pcm16_bytes: JByteArray,
) -> i32 {
    if handle == 0 || env.is_null() || in_floats.is_null() || out_pcm16_bytes.is_null() {
        return 0;
    }

    let ptr = handle as *mut AudioPipelineHandle;
    if (*ptr).magic != AUDIO_PIPELINE_MAGIC {
        return -1;
    }
    let handle_ref = &mut *ptr;

    let iface = **env;
    let in_len = (iface.get_array_length)(env, in_floats);
    let out_len = (iface.get_array_length)(env, out_pcm16_bytes);

    if in_len != 480 || out_len < 320 {
        return 0;
    }

    let mut float_buf = [0.0f32; 480];
    let mut byte_buf = [0u8; 320];

    (iface.get_float_array_region)(env, in_floats, 0, 480, float_buf.as_mut_ptr());

    let written = handle_ref.pipeline.process_float_frame_to_pcm16(&float_buf, &mut byte_buf);

    if written > 0 {
        (iface.set_byte_array_region)(
            env,
            out_pcm16_bytes,
            0,
            written as i32,
            byte_buf.as_ptr() as *const i8,
        );
    }

    written as i32
}

/// Ingest arbitrary-sized incoming PCM16 byte chunks (from AudioRecord) and output accumulated 16 kHz PCM16 bytes.
#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAudioPipelinePushAndProcessPcm16(
    env: JNIEnv,
    _class: JClass,
    handle: i64,
    in_pcm16_bytes: JByteArray,
    out_pcm16_bytes: JByteArray,
) -> i32 {
    if handle == 0 || env.is_null() || in_pcm16_bytes.is_null() || out_pcm16_bytes.is_null() {
        return 0;
    }

    let ptr = handle as *mut AudioPipelineHandle;
    if (*ptr).magic != AUDIO_PIPELINE_MAGIC {
        return -1;
    }
    let handle_ref = &mut *ptr;

    let iface = **env;
    let in_len = (iface.get_array_length)(env, in_pcm16_bytes);
    let out_len = (iface.get_array_length)(env, out_pcm16_bytes);
    if in_len <= 0 || out_len <= 0 {
        return 0;
    }

    let in_usize = in_len as usize;
    let out_usize = out_len as usize;

    let mut in_stack = [0u8; 1024];
    let mut out_stack = [0u8; 1024];
    let (mut in_heap, mut out_heap);

    let in_slice: &mut [u8] = if in_usize <= in_stack.len() {
        &mut in_stack[..in_usize]
    } else {
        in_heap = vec![0u8; in_usize];
        &mut in_heap[..]
    };

    let out_slice: &mut [u8] = if out_usize <= out_stack.len() {
        &mut out_stack[..out_usize]
    } else {
        out_heap = vec![0u8; out_usize];
        &mut out_heap[..]
    };

    (iface.get_byte_array_region)(
        env,
        in_pcm16_bytes,
        0,
        in_len,
        in_slice.as_mut_ptr() as *mut i8,
    );

    let written = handle_ref.pipeline.push_and_process_pcm16(in_slice, out_slice);

    if written > 0 {
        (iface.set_byte_array_region)(
            env,
            out_pcm16_bytes,
            0,
            written as i32,
            out_slice.as_ptr() as *const i8,
        );
    }

    written as i32
}

/// Reset pipeline state without destroying instance.
#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAudioPipelineReset(
    _env: JNIEnv,
    _class: JClass,
    handle: i64,
) -> u8 {
    if handle == 0 {
        return 0;
    }
    let ptr = handle as *mut AudioPipelineHandle;
    if (*ptr).magic != AUDIO_PIPELINE_MAGIC {
        return 0;
    }
    (*ptr).pipeline.reset();
    1
}

/// Safely destroy AudioPipeline instance.
#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAudioPipelineDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: i64,
) -> u8 {
    if handle == 0 {
        return 0;
    }
    let ptr = handle as *mut AudioPipelineHandle;
    if (*ptr).magic == AUDIO_PIPELINE_MAGIC {
        (*ptr).magic = 0;
        let _ = Box::from_raw(ptr);
        1
    } else {
        0
    }
}

/// Query count of processed 10ms frames.
#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeAudioPipelineGetProcessedFrames(
    _env: JNIEnv,
    _class: JClass,
    handle: i64,
) -> i64 {
    if handle == 0 {
        return 0;
    }
    let ptr = handle as *mut AudioPipelineHandle;
    if (*ptr).magic != AUDIO_PIPELINE_MAGIC {
        return 0;
    }
    (*ptr).pipeline.processed_frames() as i64
}

// ---------------------------------------------------------------------------
// Natural Language & Ledger Engines
// ---------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeParseUtterance(
    env: JNIEnv,
    _class: JClass,
    utterance: JString,
) -> JString {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let iface = **env;
    if utterance.is_null() {
        let err = CString::new("{\"status\":\"Failure\",\"error_message\":\"Null utterance\"}").unwrap();
        return (iface.new_string_utf)(env, err.as_ptr());
    }

    let c_ptr = (iface.get_string_utf_chars)(env, utterance, std::ptr::null_mut());
    if c_ptr.is_null() {
        let err = CString::new("{\"status\":\"Failure\",\"error_message\":\"Failed to read string\"}").unwrap();
        return (iface.new_string_utf)(env, err.as_ptr());
    }

    let input_str = match CStr::from_ptr(c_ptr).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => String::new(),
    };
    (iface.release_string_utf_chars)(env, utterance, c_ptr);

    let result = LedgerEngine::parse_structured(&input_str);
    let json_res = result.to_json();
    let c_res = CString::new(json_res).unwrap_or_else(|_| CString::new("{\"status\":\"Failure\"}").unwrap());

    (iface.new_string_utf)(env, c_res.as_ptr())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_khatawas_rust_RustEngine_nativeCalculateBalance(
    env: JNIEnv,
    _class: JClass,
    credits: JLongArray,
    payments: JLongArray,
    reversals: JLongArray,
) -> i64 {
    if env.is_null() {
        return 0;
    }
    let iface = **env;

    let c_len = if credits.is_null() { 0 } else { (iface.get_array_length)(env, credits) };
    let p_len = if payments.is_null() { 0 } else { (iface.get_array_length)(env, payments) };
    let r_len = if reversals.is_null() { 0 } else { (iface.get_array_length)(env, reversals) };

    let mut c_buf = vec![0i64; c_len.max(0) as usize];
    let mut p_buf = vec![0i64; p_len.max(0) as usize];
    let mut r_buf = vec![0i64; r_len.max(0) as usize];

    if c_len > 0 {
        (iface.get_long_array_region)(env, credits, 0, c_len, c_buf.as_mut_ptr());
    }
    if p_len > 0 {
        (iface.get_long_array_region)(env, payments, 0, p_len, p_buf.as_mut_ptr());
    }
    if r_len > 0 {
        (iface.get_long_array_region)(env, reversals, 0, r_len, r_buf.as_mut_ptr());
    }

    LedgerEngine::calculate_balance_paise(&c_buf, &p_buf, &r_buf)
}
