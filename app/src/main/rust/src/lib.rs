//! KHATAWAS low-latency audio DSP.
//! Stateful 31-tap FIR low-pass filter followed by decimation by three.

const TAPS: usize = 31;
const FRAME_IN: usize = 480;
const FRAME_OUT: usize = 160;
const COEFFICIENTS: [f64; TAPS] = [
    0.001694864290, 0.001201491530, -0.000904732020, -0.004227545336,
    -0.005427042574, 0.0, 0.011365095290, 0.018584217910,
    0.008250100127, -0.021236460303, -0.048939206360, -0.039590259461,
    0.029858125147, 0.145106949851, 0.254510780636, 0.299507242544,
    0.254510780636, 0.145106949851, 0.029858125147, -0.039590259461,
    -0.048939206360, -0.021236460303, 0.008250100127, 0.018584217910,
    0.011365095290, 0.0, -0.005427042574, -0.004227545336,
    -0.000904732020, 0.001201491530, 0.001694864290,
];

/// Per-recording-session state. Never share one instance across threads.
pub struct FirDecimator3 {
    history: [f64; TAPS],
    write_index: usize,
    input_sample_count: u64,
}

impl Default for FirDecimator3 {
    fn default() -> Self {
        Self { history: [0.0; TAPS], write_index: 0, input_sample_count: 0 }
    }
}

impl FirDecimator3 {
    pub fn process(&mut self, input: &[f32], output: &mut [f32]) -> Result<usize, &'static str> {
        let required = (input.len() + 2) / 3;
        if output.len() < required { return Err("output buffer too small"); }

        let mut output_count = 0;
        for &sample in input {
            self.history[self.write_index] = sample as f64;
            self.write_index = (self.write_index + 1) % TAPS;
            self.input_sample_count += 1;

            if self.input_sample_count % 3 == 0 {
                let mut accumulator = 0.0_f64;
                let mut index = self.write_index;
                for coefficient in COEFFICIENTS {
                    index = if index == 0 { TAPS - 1 } else { index - 1 };
                    accumulator += coefficient * self.history[index];
                }
                output[output_count] = accumulator as f32;
                output_count += 1;
            }
        }
        Ok(output_count)
    }
}

pub fn encode_pcm16_le(input: &[f32], output: &mut [u8]) -> Result<(), &'static str> {
    if output.len() < input.len() * 2 { return Err("PCM output buffer too small"); }
    for (i, &sample) in input.iter().enumerate() {
        let value = (sample as i32).clamp(i16::MIN as i32, i16::MAX as i32) as i16;
        let bytes = value.to_le_bytes();
        output[i * 2] = bytes[0];
        output[i * 2 + 1] = bytes[1];
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_khatawas_audio_RustDspEngine_nativeCreate(
    _env: jni::JNIEnv, _this: jni::objects::JObject,
) -> jni::sys::jlong {
    Box::into_raw(Box::new(FirDecimator3::default())) as jni::sys::jlong
}

#[no_mangle]
pub extern "system" fn Java_com_khatawas_audio_RustDspEngine_nativeProcess(
    mut env: jni::JNIEnv, _this: jni::objects::JObject, handle: jni::sys::jlong,
    input: jni::objects::JFloatArray, output: jni::objects::JByteArray,
) -> jni::sys::jint {
    if handle == 0 { return -1; }
    let engine = unsafe { &mut *(handle as *mut FirDecimator3) };
    if env.get_array_length(&input).map(|n| n as usize).unwrap_or(0) != FRAME_IN { return -2; }
    if env.get_array_length(&output).map(|n| n as usize).unwrap_or(0) < FRAME_OUT * 2 { return -3; }

    let mut samples = [0.0_f32; FRAME_IN];
    if env.get_float_array_region(&input, 0, &mut samples).is_err() { return -4; }
    let mut filtered = [0.0_f32; FRAME_OUT];
    if engine.process(&samples, &mut filtered).ok() != Some(FRAME_OUT) { return -5; }
    let mut pcm = [0_u8; FRAME_OUT * 2];
    if encode_pcm16_le(&filtered, &mut pcm).is_err() { return -6; }
    let mut signed_bytes = [0_i8; FRAME_OUT * 2];
    for i in 0..FRAME_OUT * 2 { signed_bytes[i] = pcm[i] as i8; }
    if env.set_byte_array_region(&output, 0, &signed_bytes).is_err() { return -7; }
    FRAME_OUT as jni::sys::jint
}

#[no_mangle]
pub extern "system" fn Java_com_khatawas_audio_RustDspEngine_nativeReset(
    _env: jni::JNIEnv, _this: jni::objects::JObject, handle: jni::sys::jlong,
) {
    if handle != 0 {
        unsafe { *(handle as *mut FirDecimator3) = FirDecimator3::default(); }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_khatawas_audio_RustDspEngine_nativeDestroy(
    _env: jni::JNIEnv, _this: jni::objects::JObject, handle: jni::sys::jlong,
) {
    if handle != 0 { unsafe { drop(Box::from_raw(handle as *mut FirDecimator3)); } }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_frame_output_count() {
        let mut dsp = FirDecimator3::default();
        assert_eq!(dsp.process(&[1200.0; FRAME_IN], &mut [0.0; FRAME_OUT]), Ok(FRAME_OUT));
    }

    #[test]
    fn state_is_continuous_across_frames() {
        let input: Vec<f32> = (0..FRAME_IN * 2)
            .map(|i| (2.0 * std::f64::consts::PI * 1000.0 * i as f64 / 48000.0).sin() as f32)
            .collect();
        let mut whole = FirDecimator3::default();
        let mut expected = [0.0; FRAME_OUT * 2];
        whole.process(&input, &mut expected).unwrap();
        let mut split = FirDecimator3::default();
        let mut a = [0.0; FRAME_OUT];
        let mut b = [0.0; FRAME_OUT];
        split.process(&input[..FRAME_IN], &mut a).unwrap();
        split.process(&input[FRAME_IN..], &mut b).unwrap();
        assert_eq!(&expected[..FRAME_OUT], &a);
        assert_eq!(&expected[FRAME_OUT..], &b);
    }

    #[test]
    fn pcm_is_little_endian_and_clamped() {
        let mut out = [0_u8; 10];
        encode_pcm16_le(&[0.0, 1.9, -1.9, 40000.0, -40000.0], &mut out).unwrap();
        assert_eq!(&out, &[0, 0, 1, 0, 255, 255, 255, 127, 0, 128]);
    }
}
