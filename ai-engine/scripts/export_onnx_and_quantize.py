"""
KHATAWAS ONNX Export and INT8 Dynamic Quantization Pipeline.

- Exports trained PyTorch student model to ONNX format
- Applies INT8 dynamic quantization for edge mobile deployment
- Evaluates CPU latency (P50, P90, P95, P99)
- Measures quantized file size (asserts < 30 MB)
- Copies production model assets to app/src/main/assets/models/
"""

import os
import sys
import time
import shutil
import numpy as np
import torch
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnxruntime as ort

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from models.student_multitask import (
    CompactMultiTaskStudent,
    MultiTaskStudentConfig,
    CATEGORY_LABELS,
    INTENT_LABELS,
    BIO_TAGS,
    CLARIFICATION_LABELS
)

def export_and_quantize():
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    checkpoint_path = os.path.join(base_dir, "checkpoints", "student_best.pt")
    exports_dir = os.path.join(base_dir, "exports")
    os.makedirs(exports_dir, exist_ok=True)
    
    fp32_onnx_path = os.path.join(exports_dir, "khatawas_student_fp32.onnx")
    int8_onnx_path = os.path.join(exports_dir, "khatawas_student_int8.onnx")
    
    print("=" * 70)
    print("STEP 1: Loading PyTorch Checkpoint")
    print("=" * 70)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    config = MultiTaskStudentConfig(**checkpoint["config"])
    model = CompactMultiTaskStudent(config)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    
    total_params = sum(p.numel() for p in model.parameters())
    print(f"Model parameters: {total_params:,}")
    
    print("\n" + "=" * 70)
    print("STEP 2: Exporting PyTorch Model to ONNX")
    print("=" * 70)
    dummy_input_ids = torch.randint(0, config.vocab_size, (1, 32), dtype=torch.long)
    dummy_mask = torch.ones((1, 32), dtype=torch.long)
    
    torch.onnx.export(
        model,
        (dummy_input_ids, dummy_mask),
        fp32_onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["category_logits", "intent_logits", "clarification_logits", "bio_logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "seq_len"},
            "attention_mask": {0: "batch_size", 1: "seq_len"},
            "category_logits": {0: "batch_size"},
            "intent_logits": {0: "batch_size"},
            "clarification_logits": {0: "batch_size"},
            "bio_logits": {0: "batch_size", 1: "seq_len"}
        },
        opset_version=14,
        do_constant_folding=True
    )
    
    fp32_size_mb = os.path.getsize(fp32_onnx_path) / (1024 * 1024)
    print(f"FP32 ONNX model saved to: {fp32_onnx_path} ({fp32_size_mb:.2f} MB)")
    
    print("\n" + "=" * 70)
    print("STEP 3: Applying INT8 Dynamic Quantization")
    print("=" * 70)
    quantize_dynamic(
        model_input=fp32_onnx_path,
        model_output=int8_onnx_path,
        weight_type=QuantType.QInt8
    )
    
    int8_size_mb = os.path.getsize(int8_onnx_path) / (1024 * 1024)
    print(f"INT8 ONNX model saved to: {int8_onnx_path} ({int8_size_mb:.2f} MB)")
    
    assert int8_size_mb < 30.0, f"Error: Quantized model size {int8_size_mb:.2f} MB exceeds 30 MB limit!"
    print(f"SUCCESS: Model size {int8_size_mb:.2f} MB is within the < 30 MB budget.")
    
    print("\n" + "=" * 70)
    print("STEP 4: Mobile CPU Latency Benchmark (500 runs)")
    print("=" * 70)
    session_options = ort.SessionOptions()
    session_options.intra_op_num_threads = 2
    session_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(int8_onnx_path, session_options, providers=["CPUExecutionProvider"])
    
    feed_ids = np.random.randint(0, config.vocab_size, (1, 32)).astype(np.int64)
    feed_mask = np.ones((1, 32), dtype=np.int64)
    
    # Warmup
    for _ in range(50):
        _ = session.run(None, {"input_ids": feed_ids, "attention_mask": feed_mask})
        
    latencies = []
    for _ in range(500):
        t0 = time.perf_counter()
        _ = session.run(None, {"input_ids": feed_ids, "attention_mask": feed_mask})
        t1 = time.perf_counter()
        latencies.append((t1 - t0) * 1000.0)
        
    p50 = np.percentile(latencies, 50)
    p90 = np.percentile(latencies, 90)
    p95 = np.percentile(latencies, 95)
    p99 = np.percentile(latencies, 99)
    avg_lat = np.mean(latencies)
    
    print(f"Inference Latency on CPU (batch=1, seq=32):")
    print(f"  - Average: {avg_lat:.2f} ms")
    print(f"  - P50:     {p50:.2f} ms")
    print(f"  - P90:     {p90:.2f} ms")
    print(f"  - P95:     {p95:.2f} ms")
    print(f"  - P99:     {p99:.2f} ms")
    
    if p95 < 25.0:
        print(f"SUCCESS: P95 latency {p95:.2f} ms meets the < 25 ms requirement.")
    else:
        print(f"WARNING: P95 latency {p95:.2f} ms exceeds target.")
        
    print("\n" + "=" * 70)
    print("STEP 5: Copying Edge Assets to Android App")
    print("=" * 70)
    android_assets_dir = os.path.abspath(os.path.join(base_dir, "..", "app", "src", "main", "assets", "models"))
    os.makedirs(android_assets_dir, exist_ok=True)
    
    shutil.copy2(int8_onnx_path, os.path.join(android_assets_dir, "khatawas_student_int8.onnx"))
    shutil.copy2(os.path.join(exports_dir, "vocab.json"), os.path.join(android_assets_dir, "vocab.json"))
    shutil.copy2(os.path.join(exports_dir, "vocab.txt"), os.path.join(android_assets_dir, "vocab.txt"))
    
    print(f"Copied {os.path.basename(int8_onnx_path)}, vocab.json, and vocab.txt to {android_assets_dir}")

if __name__ == "__main__":
    export_and_quantize()
