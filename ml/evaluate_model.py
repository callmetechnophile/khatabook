"""
KHATAWAS - Benchmark and Evaluation of ONNX Student Model
Measures accuracy, latency distributions (median, p95), and parameter footprint.
"""

import json
import os
import time
import numpy as np
import onnxruntime as ort

def tokenize(text, vocab, max_len=16):
    tokens = text.replace(",", " ").split()
    ids = [vocab.get(t.lower(), 1) for t in tokens[:max_len]]
    if len(ids) < max_len:
        ids += [0] * (max_len - len(ids))
    return ids

def main():
    model_path = os.path.join(os.path.dirname(__file__), "student_khatawas.onnx")
    vocab_path = os.path.join(os.path.dirname(__file__), "vocab.json")
    dataset_path = os.path.join(os.path.dirname(__file__), "dataset.json")

    with open(vocab_path, "r", encoding="utf-8") as f:
        vocab = json.load(f)
    with open(dataset_path, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    session = ort.InferenceSession(model_path)
    input_name = session.get_inputs()[0].name

    intent_map = {"TRANSACTION": 0, "QUERY": 1, "CORRECTION": 2, "AMBIGUOUS_PRONOUN": 3}
    dir_map = {"CREDIT": 0, "PAYMENT": 1, "COMPOUND": 0}

    latencies = []
    correct_intents = 0
    correct_dirs = 0
    dir_evaluated = 0
    pronouns_evaluated = 0
    pronouns_flagged = 0

    print("=" * 60)
    print("KHATAWAS ONNX Student Model Benchmark (Mobile Runtime)")
    print("=" * 60)

    for item in dataset:
        input_ids = np.array([tokenize(item["utterance"], vocab)], dtype=np.int64)

        t0 = time.perf_counter()
        outputs = session.run(None, {input_name: input_ids})
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        latencies.append(elapsed_ms)

        intent_logits = outputs[0][0]
        dir_logits = outputs[1][0]

        pred_intent_idx = int(np.argmax(intent_logits))
        expected_intent_idx = intent_map.get(item["intent"], 0)
        if pred_intent_idx == expected_intent_idx:
            correct_intents += 1

        if "direction" in item:
            dir_evaluated += 1
            pred_dir_idx = int(np.argmax(dir_logits))
            expected_dir_idx = dir_map.get(item["direction"], 0)
            if pred_dir_idx == expected_dir_idx:
                correct_dirs += 1

        if item.get("should_reject"):
            pronouns_evaluated += 1
            if pred_intent_idx == intent_map["AMBIGUOUS_PRONOUN"]:
                pronouns_flagged += 1

    # Run 100 warmup iterations for stable latency measurement
    bench_input = np.array([tokenize("Ramesh ka 340 udhaar", vocab)], dtype=np.int64)
    for _ in range(100):
        session.run(None, {input_name: bench_input})

    timed_latencies = []
    for _ in range(200):
        t0 = time.perf_counter()
        session.run(None, {input_name: bench_input})
        timed_latencies.append((time.perf_counter() - t0) * 1000.0)

    med_latency = np.median(timed_latencies)
    p95_latency = np.percentile(timed_latencies, 95)
    model_size_mb = os.path.getsize(model_path) / (1024 * 1024)

    intent_acc = (correct_intents / len(dataset)) * 100.0
    dir_acc = (correct_dirs / dir_evaluated) * 100.0 if dir_evaluated > 0 else 100.0
    pronoun_rate = (pronouns_flagged / pronouns_evaluated) * 100.0 if pronouns_evaluated > 0 else 100.0

    print(f"Model File Size:             {model_size_mb:.2f} MB  (Target: <15 MB)")
    print(f"Median Inference Latency:    {med_latency:.2f} ms  (Target: <50 ms)")
    print(f"P95 Inference Latency:       {p95_latency:.2f} ms  (Target: <80 ms)")
    print(f"Intent Classification Acc:   {intent_acc:.1f}%")
    print(f"Direction (Credit/Pay) Acc:  {dir_acc:.1f}%")
    print(f"Pronoun Ambiguity Catch Rate:{pronoun_rate:.1f}%")
    print("=" * 60)

    assert model_size_mb < 15.0, "Model exceeds 15 MB mobile threshold"
    assert med_latency < 50.0, "Median latency exceeds 50 ms budget"
    assert intent_acc >= 90.0, "Intent accuracy below 90%"
    assert dir_acc >= 90.0, "Direction accuracy below 90%"
    assert pronoun_rate == 100.0, "Pronoun ambiguity catch rate must be 100%"
    print("ALL EVALUATION TARGETS PASSED!")

if __name__ == "__main__":
    main()
