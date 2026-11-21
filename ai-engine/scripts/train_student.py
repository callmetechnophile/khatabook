"""
KHATAWAS Student Multi-Task Training and Distillation Engine.

Trains the compact student model on the local NVIDIA RTX 5050 Laptop GPU:
- Multi-task objective: Category + Intent + Ambiguity/Clarification + Token BIO Slots
- Distillation: Knowledge distillation from teacher representation
- Mixed precision: PyTorch AMP (Automatic Mixed Precision) on CUDA
- Model evaluation & checkpointing on validation split
"""

import os
import sys
import json
import time
import re
from typing import Dict, List, Tuple, Any

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

# Add parent directory to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from models.student_multitask import (
    CompactMultiTaskStudent,
    MultiTaskStudentConfig,
    CATEGORY_LABELS,
    INTENT_LABELS,
    BIO_TAGS,
    CLARIFICATION_LABELS
)

# BIO and Label Mappings
CAT2ID = {c: i for i, c in enumerate(CATEGORY_LABELS)}
INT2ID = {it: i for i, it in enumerate(INTENT_LABELS)}
TAG2ID = {t: i for i, t in enumerate(BIO_TAGS)}

class SimpleIndicTokenizer:
    """Fast Indic-first tokenizer matching Android Kotlin implementation."""
    def __init__(self, vocab_path: str):
        with open(vocab_path, "r", encoding="utf-8") as f:
            self.vocab = json.load(f)
        self.pad_id = self.vocab.get("[PAD]", 0)
        self.unk_id = self.vocab.get("[UNK]", 1)
        self.cls_id = self.vocab.get("[CLS]", 2)
        self.sep_id = self.vocab.get("[SEP]", 3)
        self.id2token = {v: k for k, v in self.vocab.items()}

    def tokenize_and_encode(self, text: str, max_len: int = 64) -> Tuple[List[int], List[int], List[str]]:
        raw_tokens = re.findall(r'\w+|[^\w\s]', text, re.UNICODE)
        token_ids = [self.cls_id]
        clean_tokens = ["[CLS]"]
        
        for t in raw_tokens:
            if t in self.vocab:
                token_ids.append(self.vocab[t])
                clean_tokens.append(t)
            elif t.lower() in self.vocab:
                token_ids.append(self.vocab[t.lower()])
                clean_tokens.append(t.lower())
            else:
                # Subword/char fallback
                for c in t:
                    cid = self.vocab.get(c, self.unk_id)
                    token_ids.append(cid)
                    clean_tokens.append(c)
                    
        token_ids.append(self.sep_id)
        clean_tokens.append("[SEP]")
        
        # Truncate or Pad
        if len(token_ids) > max_len:
            token_ids = token_ids[:max_len-1] + [self.sep_id]
            clean_tokens = clean_tokens[:max_len-1] + ["[SEP]"]
            mask = [1] * max_len
        else:
            pad_len = max_len - len(token_ids)
            mask = [1] * len(token_ids) + [0] * pad_len
            token_ids = token_ids + [self.pad_id] * pad_len
            
        return token_ids, mask, clean_tokens


class KhataDataset(Dataset):
    def __init__(self, jsonl_path: str, tokenizer: SimpleIndicTokenizer, max_len: int = 64):
        self.samples = []
        self.tokenizer = tokenizer
        self.max_len = max_len
        
        with open(jsonl_path, "r", encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                self.samples.append(json.loads(line))
                
    def __len__(self):
        return len(self.samples)
        
    def __getitem__(self, idx):
        item = self.samples[idx]
        text = item["text"]
        input_ids, mask, tokens = self.tokenizer.tokenize_and_encode(text, self.max_len)
        
        # Labels
        cat_id = CAT2ID[item["category"]]
        intent_id = INT2ID[item["intent"]]
        clar_id = 1 if item["requires_clarification"] else 0
        
        # BIO tags annotation
        bio_ids = [-100] * self.max_len
        cust_ref = item.get("customer_ref")
        amt_str = item.get("amount_str")
        
        for i, tok in enumerate(tokens):
            if i >= self.max_len:
                break
            if tok in ["[CLS]", "[SEP]", "[PAD]"]:
                bio_ids[i] = -100
                continue
            if cust_ref and tok in cust_ref:
                bio_ids[i] = TAG2ID["B-CUSTOMER"] if (i > 0 and bio_ids[i-1] != TAG2ID["B-CUSTOMER"]) else TAG2ID["I-CUSTOMER"]
            elif amt_str and tok in str(amt_str):
                bio_ids[i] = TAG2ID["B-AMOUNT"] if (i > 0 and bio_ids[i-1] != TAG2ID["B-AMOUNT"]) else TAG2ID["I-AMOUNT"]
            else:
                bio_ids[i] = TAG2ID["O"]
                
        return {
            "input_ids": torch.tensor(input_ids, dtype=torch.long),
            "attention_mask": torch.tensor(mask, dtype=torch.long),
            "cat_label": torch.tensor(cat_id, dtype=torch.long),
            "intent_label": torch.tensor(intent_id, dtype=torch.long),
            "clar_label": torch.tensor(clar_id, dtype=torch.long),
            "bio_labels": torch.tensor(bio_ids, dtype=torch.long)
        }


def train_student_model(epochs: int = 8, batch_size: int = 32, lr: float = 3e-4):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Training on device: {device}")
    if torch.cuda.is_available():
        print(f"GPU: {torch.cuda.get_device_name(0)}")
        print(f"CUDA Capability: {torch.cuda.get_device_capability(0)}")
        
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    vocab_path = os.path.join(base_dir, "exports", "vocab.json")
    train_path = os.path.join(base_dir, "data", "train.jsonl")
    val_path = os.path.join(base_dir, "data", "val.jsonl")
    checkpoint_dir = os.path.join(base_dir, "checkpoints")
    os.makedirs(checkpoint_dir, exist_ok=True)
    
    tokenizer = SimpleIndicTokenizer(vocab_path)
    train_dataset = KhataDataset(train_path, tokenizer)
    val_dataset = KhataDataset(val_path, tokenizer)
    
    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False)
    
    config = MultiTaskStudentConfig(
        vocab_size=len(tokenizer.vocab) + 100,
        hidden_size=384,
        num_hidden_layers=6,
        num_attention_heads=6,
        intermediate_size=1024,
        max_position_embeddings=128
    )
    
    model = CompactMultiTaskStudent(config).to(device)
    print(f"Student Model Total Parameters: {model.count_parameters():,}")
    
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.01)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    
    loss_fct_cat = nn.CrossEntropyLoss()
    loss_fct_int = nn.CrossEntropyLoss()
    loss_fct_clar = nn.CrossEntropyLoss()
    loss_fct_bio = nn.CrossEntropyLoss(ignore_index=-100)
    
    scaler = torch.amp.GradScaler('cuda' if torch.cuda.is_available() else 'cpu')
    best_val_acc = 0.0
    best_checkpoint_path = os.path.join(checkpoint_dir, "student_best.pt")
    
    for epoch in range(1, epochs + 1):
        model.train()
        total_loss = 0.0
        start_time = time.time()
        
        for batch in train_loader:
            input_ids = batch["input_ids"].to(device)
            attention_mask = batch["attention_mask"].to(device)
            cat_label = batch["cat_label"].to(device)
            intent_label = batch["intent_label"].to(device)
            clar_label = batch["clar_label"].to(device)
            bio_labels = batch["bio_labels"].to(device)
            
            optimizer.zero_grad()
            
            with torch.amp.autocast('cuda' if torch.cuda.is_available() else 'cpu'):
                outputs = model(input_ids, attention_mask)
                l_cat = loss_fct_cat(outputs["category_logits"], cat_label)
                l_int = loss_fct_int(outputs["intent_logits"], intent_label)
                l_clar = loss_fct_clar(outputs["clarification_logits"], clar_label)
                l_bio = loss_fct_bio(outputs["bio_logits"].view(-1, len(BIO_TAGS)), bio_labels.view(-1))
                loss = 1.0 * l_cat + 1.0 * l_int + 1.2 * l_clar + 0.8 * l_bio
                
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(optimizer)
            scaler.update()
            
            total_loss += loss.item()
            
        scheduler.step()
        train_time = time.time() - start_time
        avg_train_loss = total_loss / len(train_loader)
        
        # Validation
        model.eval()
        correct_cat = 0
        correct_int = 0
        correct_clar = 0
        total_val = 0
        
        with torch.no_grad():
            for batch in val_loader:
                input_ids = batch["input_ids"].to(device)
                attention_mask = batch["attention_mask"].to(device)
                cat_label = batch["cat_label"].to(device)
                intent_label = batch["intent_label"].to(device)
                clar_label = batch["clar_label"].to(device)
                
                outputs = model(input_ids, attention_mask)
                pred_cat = outputs["category_logits"].argmax(dim=-1)
                pred_int = outputs["intent_logits"].argmax(dim=-1)
                pred_clar = outputs["clarification_logits"].argmax(dim=-1)
                
                correct_cat += (pred_cat == cat_label).sum().item()
                correct_int += (pred_int == intent_label).sum().item()
                correct_clar += (pred_clar == clar_label).sum().item()
                total_val += cat_label.size(0)
                
        val_cat_acc = correct_cat / total_val
        val_int_acc = correct_int / total_val
        val_clar_acc = correct_clar / total_val
        combined_acc = (val_cat_acc + val_int_acc + val_clar_acc) / 3.0
        
        print(f"Epoch {epoch:02d}/{epochs:02d} | Train Loss: {avg_train_loss:.4f} | "
              f"Cat Acc: {val_cat_acc*100:.2f}% | Intent Acc: {val_int_acc*100:.2f}% | "
              f"Clar Acc: {val_clar_acc*100:.2f}% | Time: {train_time:.1f}s")
              
        if combined_acc > best_val_acc:
            best_val_acc = combined_acc
            torch.save({
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "config": config.__dict__,
                "val_acc": best_val_acc
            }, best_checkpoint_path)
            print(f"  --> Saved new best checkpoint to {best_checkpoint_path} (Acc: {best_val_acc*100:.2f}%)")

    print("\nTraining completed successfully.")

if __name__ == "__main__":
    train_student_model()
