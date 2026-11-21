"""
KHATAWAS - Export Student Model to ONNX format.
Exports trained PyTorch weights to an optimized ONNX computational graph for mobile runtime inference.
"""

import os
import json
import torch
from ml.student_model import KhatawasStudentModel

def main():
    vocab_path = os.path.join(os.path.dirname(__file__), "vocab.json")
    with open(vocab_path, "r", encoding="utf-8") as f:
        vocab = json.load(f)

    checkpoint_path = os.path.join(os.path.dirname(__file__), "student_model.pt")
    model = KhatawasStudentModel(vocab_size=len(vocab) + 10)
    model.load_state_dict(torch.load(checkpoint_path, map_location="cpu"))
    model.eval()

    dummy_input = torch.zeros((1, 16), dtype=torch.long)
    output_onnx_path = os.path.join(os.path.dirname(__file__), "student_khatawas.onnx")

    torch.onnx.export(
        model,
        dummy_input,
        output_onnx_path,
        input_names=["input_ids"],
        output_names=["intent_logits", "direction_logits", "date_logits", "amount_pred", "ner_logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "seq_len"},
            "intent_logits": {0: "batch_size"},
            "direction_logits": {0: "batch_size"},
            "date_logits": {0: "batch_size"},
            "amount_pred": {0: "batch_size"},
            "ner_logits": {0: "batch_size", 1: "seq_len"}
        },
        opset_version=17
    )

    file_size_mb = os.path.getsize(output_onnx_path) / (1024 * 1024)
    print(f"Exported ONNX model successfully to: {output_onnx_path} (Size: {file_size_mb:.2f} MB)")

if __name__ == "__main__":
    main()
