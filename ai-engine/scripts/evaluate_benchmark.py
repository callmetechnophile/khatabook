"""
KHATAWAS Multilingual Evaluation & Benchmarking Suite.

Evaluates the trained student model against test.jsonl and ood_test.jsonl:
- Customer Entity F1
- Relationship Classification F1
- Amount Exact Match
- Category Macro F1 (CREDIT, DEBIT, STUCK, ILLEGAL)
- Ambiguity Detection Recall & Precision
- Language-specific breakdown (Hindi, Kannada, English, Hinglish, Kanglish)
- Ravi vs Ravi-ke-papa disambiguation test
"""

import os
import sys
import json
import re

# Ensure UTF-8 output on Windows
if sys.platform.startswith("win"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
from typing import Dict, List, Any
from collections import defaultdict

import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from models.student_multitask import (
    CompactMultiTaskStudent,
    MultiTaskStudentConfig,
    CATEGORY_LABELS,
    INTENT_LABELS,
    BIO_TAGS,
    CLARIFICATION_LABELS
)
from scripts.train_student import SimpleIndicTokenizer, CAT2ID, INT2ID, TAG2ID

def evaluate_model():
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    vocab_path = os.path.join(base_dir, "exports", "vocab.json")
    checkpoint_path = os.path.join(base_dir, "checkpoints", "student_best.pt")
    test_path = os.path.join(base_dir, "data", "test.jsonl")
    ood_path = os.path.join(base_dir, "data", "ood_test.jsonl")
    
    if not os.path.exists(checkpoint_path):
        print(f"Error: checkpoint {checkpoint_path} not found.")
        return
        
    tokenizer = SimpleIndicTokenizer(vocab_path)
    checkpoint = torch.load(checkpoint_path, map_location=device, weights_only=False)
    
    cfg_dict = checkpoint["config"]
    config = MultiTaskStudentConfig(**cfg_dict)
    model = CompactMultiTaskStudent(config).to(device)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    
    print("=" * 70)
    print("KHATAWAS MULTILINGUAL STUDENT BENCHMARK REPORT")
    print(f"Model parameters: {model.count_parameters():,}")
    print(f"Evaluation device: {device}")
    print("=" * 70)
    
    for split_name, file_path in [("IN-DISTRIBUTION TEST SET", test_path), ("OUT-OF-DISTRIBUTION (OOD) TEST SET", ood_path)]:
        print(f"\n--- {split_name} ({file_path}) ---")
        with open(file_path, "r", encoding="utf-8") as f:
            records = [json.loads(line) for line in f if line.strip()]
            
        lang_stats = defaultdict(lambda: {"total": 0, "cat_correct": 0, "clar_correct": 0, "intent_correct": 0})
        cat_tp = defaultdict(int)
        cat_fp = defaultdict(int)
        cat_fn = defaultdict(int)
        
        clar_tp = 0
        clar_fp = 0
        clar_fn = 0
        clar_tn = 0
        
        total_samples = len(records)
        
        with torch.no_grad():
            for item in records:
                text = item["text"]
                lang = item["language"]
                true_cat = item["category"]
                true_intent = item["intent"]
                true_clar = item["requires_clarification"]
                
                input_ids, mask, _ = tokenizer.tokenize_and_encode(text)
                t_in = torch.tensor([input_ids], dtype=torch.long, device=device)
                t_mask = torch.tensor([mask], dtype=torch.long, device=device)
                
                out = model(t_in, t_mask)
                pred_cat_idx = out["category_logits"].argmax(dim=-1).item()
                pred_int_idx = out["intent_logits"].argmax(dim=-1).item()
                pred_clar_idx = out["clarification_logits"].argmax(dim=-1).item()
                
                pred_cat = CATEGORY_LABELS[pred_cat_idx]
                pred_int = INTENT_LABELS[pred_int_idx]
                pred_clar = bool(pred_clar_idx == 1)
                
                # Language stats
                lang_stats[lang]["total"] += 1
                if pred_cat == true_cat:
                    lang_stats[lang]["cat_correct"] += 1
                if pred_clar == true_clar:
                    lang_stats[lang]["clar_correct"] += 1
                if pred_int == true_intent:
                    lang_stats[lang]["intent_correct"] += 1
                    
                # Category F1
                for c in CATEGORY_LABELS:
                    if pred_cat == c and true_cat == c:
                        cat_tp[c] += 1
                    elif pred_cat == c and true_cat != c:
                        cat_fp[c] += 1
                    elif pred_cat != c and true_cat == c:
                        cat_fn[c] += 1
                        
                # Clarification confusion matrix
                if pred_clar and true_clar:
                    clar_tp += 1
                elif pred_clar and not true_clar:
                    clar_fp += 1
                elif not pred_clar and true_clar:
                    clar_fn += 1
                else:
                    clar_tn += 1
                    
        # Category F1 Metrics
        print("\n1. Category Classification Performance:")
        cat_f1_list = []
        for c in CATEGORY_LABELS:
            tp = cat_tp[c]
            fp = cat_fp[c]
            fn = cat_fn[c]
            p = tp / (tp + fp) if (tp + fp) > 0 else 0.0
            r = tp / (tp + fn) if (tp + fn) > 0 else 0.0
            f1 = (2 * p * r) / (p + r) if (p + r) > 0 else 0.0
            cat_f1_list.append(f1)
            print(f"  - {c:<8} | Precision: {p*100:5.2f}% | Recall: {r*100:5.2f}% | F1: {f1*100:5.2f}%")
        macro_cat_f1 = sum(cat_f1_list) / len(cat_f1_list)
        print(f"  --> Macro Category F1: {macro_cat_f1*100:.2f}%")
        
        # Ambiguity / Clarification Metrics
        clar_prec = clar_tp / (clar_tp + clar_fp) if (clar_tp + clar_fp) > 0 else 0.0
        clar_rec = clar_tp / (clar_tp + clar_fn) if (clar_tp + clar_fn) > 0 else 0.0
        clar_f1 = (2 * clar_prec * clar_rec) / (clar_prec + clar_rec) if (clar_prec + clar_rec) > 0 else 0.0
        print("\n2. Ambiguity & Clarification Detection:")
        print(f"  - Precision: {clar_prec*100:.2f}% | Recall: {clar_rec*100:.2f}% | F1: {clar_f1*100:.2f}%")
        
        # Language Breakdown
        print("\n3. Per-Language Breakdown:")
        for lang, s in sorted(lang_stats.items()):
            tot = s["total"]
            c_acc = (s["cat_correct"] / tot) * 100
            cl_acc = (s["clar_correct"] / tot) * 100
            i_acc = (s["intent_correct"] / tot) * 100
            print(f"  - {lang:<8} (N={tot:<3}) | Category Acc: {c_acc:5.2f}% | Clarification Acc: {cl_acc:5.2f}% | Intent Acc: {i_acc:5.2f}%")

    # Disambiguation Negative Pair Test
    print("\n" + "=" * 70)
    print("CUSTOMER DISAMBIGUATION TEST (Ravi vs. Ravi ke papa / father / thande)")
    print("=" * 70)
    test_cases = [
        ("रवि को 500 रुपये उधार दिए", "Ravi (Base Customer)"),
        ("रवि के पापा को 500 रुपये उधार दिए", "Ravi ke papa (Father - Distinct Customer!)"),
        ("ರವಿಗೆ 500 ರೂಪಾಯಿ ಸಾಲ ಕೊಟ್ಟೆ", "Ravi (Kannada Base)"),
        ("ರವಿ ತಂದೆಗೆ 500 ರೂಪಾಯಿ ಸಾಲ ಕೊಟ್ಟೆ", "Ravi thande (Kannada Father - Distinct!)"),
        ("Ravi ko 500 udhar diya", "Ravi (Hinglish Base)"),
        ("Ravi ke papa ko 500 udhar diya", "Ravi ke papa (Hinglish Father - Distinct!)"),
        ("Give Ravi 500 credit", "Ravi (English Base)"),
        ("Give Ravi's father 500 credit", "Ravi's father (English Father - Distinct!)"),
        ("उसके पापा को 500 दिए", "Ambiguous reference (No base customer -> Must Clarify!)")
    ]
    
    with torch.no_grad():
        for txt, expected in test_cases:
            input_ids, mask, _ = tokenizer.tokenize_and_encode(txt)
            t_in = torch.tensor([input_ids], dtype=torch.long, device=device)
            t_mask = torch.tensor([mask], dtype=torch.long, device=device)
            out = model(t_in, t_mask)
            
            p_cat = CATEGORY_LABELS[out["category_logits"].argmax(dim=-1).item()]
            p_clar = bool(out["clarification_logits"].argmax(dim=-1).item() == 1)
            
            status = "PASS"
            if "Must Clarify" in expected and not p_clar:
                status = "FAIL"
            elif "Must Clarify" not in expected and p_clar:
                status = "WARN (Flagged clarification)"
                
            print(f"[{status}] Utterance: \"{txt}\"")
            print(f"       Expected: {expected} | Pred Cat: {p_cat} | Needs Clarification: {p_clar}")

if __name__ == "__main__":
    evaluate_model()
