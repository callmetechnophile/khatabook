"""
KHATAWAS Chatbot Intent ONNX Export and Quantization Engine.
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
from models.chatbot_model import (
    ChatbotIntentModel,
    ChatbotModelConfig,
    INTENT_CLASSES,
    DATE_FILTER_CLASSES,
    CATEGORY_FILTER_CLASSES,
    RELATION_CLASSES,
    BIO_CLASSES
)

def export_and_quantize_chatbot():
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    checkpoint_path = os.path.join(base_dir, "checkpoints", "chatbot_best.pt")
    exports_dir = os.path.join(base_dir, "exports")
    os.makedirs(exports_dir, exist_ok=True)
    
    fp32_onnx_path = os.path.join(exports_dir, "khatawas_chatbot_fp32.onnx")
    int8_onnx_path = os.path.join(exports_dir, "khatawas_chatbot_int8.onnx")
    
    print("=" * 70)
    print("STEP 1: Loading Chatbot Model Checkpoint")
    print("=" * 70)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    config = ChatbotModelConfig(**checkpoint["config"])
    model = ChatbotIntentModel(config)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    
    print(f"Chatbot Model Parameters: {model.count_parameters():,}")
    
    print("\n" + "=" * 70)
    print("STEP 2: Exporting to FP32 ONNX")
    print("=" * 70)
    dummy_input_ids = torch.randint(0, config.vocab_size, (1, 32), dtype=torch.long)
    dummy_mask = torch.ones((1, 32), dtype=torch.long)
    
    torch.onnx.export(
        model,
        (dummy_input_ids, dummy_mask),
        fp32_onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=[
            "intent_logits",
            "clarification_logits",
            "date_filter_logits",
            "category_filter_logits",
            "relation_logits",
            "bio_logits"
        ],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "seq_len"},
            "attention_mask": {0: "batch_size", 1: "seq_len"},
            "intent_logits": {0: "batch_size"},
            "clarification_logits": {0: "batch_size"},
            "date_filter_logits": {0: "batch_size"},
            "category_filter_logits": {0: "batch_size"},
            "relation_logits": {0: "batch_size"},
            "bio_logits": {0: "batch_size", 1: "seq_len"}
        },
        opset_version=14,
        do_constant_folding=True
    )
    
    fp32_size = os.path.getsize(fp32_onnx_path) / (1024 * 1024)
    print(f"FP32 Model Saved: {fp32_onnx_path} ({fp32_size:.2f} MB)")
    
    print("\n" + "=" * 70)
    print("STEP 3: Dynamic INT8 Quantization")
    print("=" * 70)
    quantize_dynamic(
        model_input=fp32_onnx_path,
        model_output=int8_onnx_path,
        weight_type=QuantType.QInt8
    )
    int8_size = os.path.getsize(int8_onnx_path) / (1024 * 1024)
    print(f"INT8 Model Saved: {int8_onnx_path} ({int8_size:.2f} MB)")
    
    print("\n" + "=" * 70)
    print("STEP 4: Latency Benchmark on CPU")
    print("=" * 70)
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 2
    session = ort.InferenceSession(int8_onnx_path, opts, providers=["CPUExecutionProvider"])
    
    feed_ids = np.random.randint(0, config.vocab_size, (1, 32)).astype(np.int64)
    feed_mask = np.ones((1, 32), dtype=np.int64)
    
    for _ in range(20):
        _ = session.run(None, {"input_ids": feed_ids, "attention_mask": feed_mask})
        
    latencies = []
    for _ in range(200):
        t0 = time.perf_counter()
        _ = session.run(None, {"input_ids": feed_ids, "attention_mask": feed_mask})
        t1 = time.perf_counter()
        latencies.append((t1 - t0) * 1000.0)
        
    p50 = np.percentile(latencies, 50)
    p95 = np.percentile(latencies, 95)
    print(f"Inference Latency: P50={p50:.2f}ms | P95={p95:.2f}ms")
    
    print("\n" + "=" * 70)
    print("STEP 5: Copy to Android App Assets")
    print("=" * 70)
    android_assets_dir = os.path.abspath(os.path.join(base_dir, "..", "app", "src", "main", "assets", "models"))
    dest_path = os.path.join(android_assets_dir, "khatawas_chatbot_int8.onnx")
    shutil.copy2(int8_onnx_path, dest_path)
    print(f"Copied to {dest_path}")

if __name__ == "__main__":
    export_and_quantize_chatbot()
