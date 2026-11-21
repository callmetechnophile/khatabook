"""
KHATAWAS Chatbot Intent & Query Planner Model Training Engine.
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
from scripts.train_student import SimpleIndicTokenizer

INT2ID = {it: i for i, it in enumerate(INTENT_CLASSES)}
DATE2ID = {d: i for i, d in enumerate(DATE_FILTER_CLASSES)}
CAT2ID = {c: i for i, c in enumerate(CATEGORY_FILTER_CLASSES)}
REL2ID = {r: i for i, r in enumerate(RELATION_CLASSES)}
BIO2ID = {b: i for i, b in enumerate(BIO_CLASSES)}

class ChatbotDataset(Dataset):
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
        
        intent_id = INT2ID.get(item["intent"], INT2ID["UNKNOWN"])
        clar_id = 1 if item["requires_clarification"] else 0
        date_id = DATE2ID.get(item.get("date_filter", "NONE"), 0)
        cat_id = CAT2ID.get(item.get("category_filter", "NONE"), 0)
        rel_id = REL2ID.get(item.get("relation", "NONE"), 0)
        
        bio_ids = [-100] * self.max_len
        cust_name = item.get("customer_name")
        
        for i, tok in enumerate(tokens):
            if i >= self.max_len:
                break
            if tok in ["[CLS]", "[SEP]", "[PAD]"]:
                bio_ids[i] = -100
                continue
            if cust_name and tok in cust_name:
                bio_ids[i] = BIO2ID["B-CUSTOMER"] if (i > 0 and bio_ids[i-1] != BIO2ID["B-CUSTOMER"]) else BIO2ID["I-CUSTOMER"]
            else:
                bio_ids[i] = BIO2ID["O"]
                
        return {
            "input_ids": torch.tensor(input_ids, dtype=torch.long),
            "attention_mask": torch.tensor(mask, dtype=torch.long),
            "intent_label": torch.tensor(intent_id, dtype=torch.long),
            "clar_label": torch.tensor(clar_id, dtype=torch.long),
            "date_label": torch.tensor(date_id, dtype=torch.long),
            "cat_label": torch.tensor(cat_id, dtype=torch.long),
            "rel_label": torch.tensor(rel_id, dtype=torch.long),
            "bio_labels": torch.tensor(bio_ids, dtype=torch.long)
        }

def train_chatbot_model(epochs: int = 6, batch_size: int = 32, lr: float = 4e-4):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Training Chatbot model on: {device}")
    
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    vocab_path = os.path.join(base_dir, "exports", "vocab.json")
    train_path = os.path.join(base_dir, "data", "chatbot_train.jsonl")
    val_path = os.path.join(base_dir, "data", "chatbot_val.jsonl")
    checkpoint_dir = os.path.join(base_dir, "checkpoints")
    os.makedirs(checkpoint_dir, exist_ok=True)
    
    tokenizer = SimpleIndicTokenizer(vocab_path)
    train_dataset = ChatbotDataset(train_path, tokenizer)
    val_dataset = ChatbotDataset(val_path, tokenizer)
    
    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False)
    
    config = ChatbotModelConfig(
        vocab_size=len(tokenizer.vocab) + 100,
        hidden_size=256,
        num_layers=4,
        num_heads=4,
        intermediate_size=512,
        max_seq_len=64
    )
    
    model = ChatbotIntentModel(config).to(device)
    print(f"Chatbot Model Total Parameters: {model.count_parameters():,}")
    
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.01)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    
    loss_fct_int = nn.CrossEntropyLoss()
    loss_fct_clar = nn.CrossEntropyLoss()
    loss_fct_date = nn.CrossEntropyLoss()
    loss_fct_cat = nn.CrossEntropyLoss()
    loss_fct_rel = nn.CrossEntropyLoss()
    loss_fct_bio = nn.CrossEntropyLoss(ignore_index=-100)
    
    scaler = torch.amp.GradScaler('cuda' if torch.cuda.is_available() else 'cpu')
    best_val_acc = 0.0
    best_checkpoint_path = os.path.join(checkpoint_dir, "chatbot_best.pt")
    
    for epoch in range(1, epochs + 1):
        model.train()
        total_loss = 0.0
        start_time = time.time()
        
        for batch in train_loader:
            input_ids = batch["input_ids"].to(device)
            attention_mask = batch["attention_mask"].to(device)
            intent_label = batch["intent_label"].to(device)
            clar_label = batch["clar_label"].to(device)
            date_label = batch["date_label"].to(device)
            cat_label = batch["cat_label"].to(device)
            rel_label = batch["rel_label"].to(device)
            bio_labels = batch["bio_labels"].to(device)
            
            optimizer.zero_grad()
            
            with torch.amp.autocast('cuda' if torch.cuda.is_available() else 'cpu'):
                out = model(input_ids, attention_mask)
                l_int = loss_fct_int(out["intent_logits"], intent_label)
                l_clar = loss_fct_clar(out["clarification_logits"], clar_label)
                l_date = loss_fct_date(out["date_filter_logits"], date_label)
                l_cat = loss_fct_cat(out["category_filter_logits"], cat_label)
                l_rel = loss_fct_rel(out["relation_logits"], rel_label)
                l_bio = loss_fct_bio(out["bio_logits"].view(-1, len(BIO_CLASSES)), bio_labels.view(-1))
                
                loss = 1.0 * l_int + 1.2 * l_clar + 0.8 * l_date + 0.8 * l_cat + 0.8 * l_rel + 0.5 * l_bio
                
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
        correct_int = 0
        correct_clar = 0
        correct_date = 0
        total_val = 0
        
        with torch.no_grad():
            for batch in val_loader:
                input_ids = batch["input_ids"].to(device)
                attention_mask = batch["attention_mask"].to(device)
                intent_label = batch["intent_label"].to(device)
                clar_label = batch["clar_label"].to(device)
                date_label = batch["date_label"].to(device)
                
                out = model(input_ids, attention_mask)
                pred_int = out["intent_logits"].argmax(dim=-1)
                pred_clar = out["clarification_logits"].argmax(dim=-1)
                pred_date = out["date_filter_logits"].argmax(dim=-1)
                
                correct_int += (pred_int == intent_label).sum().item()
                correct_clar += (pred_clar == clar_label).sum().item()
                correct_date += (pred_date == date_label).sum().item()
                total_val += intent_label.size(0)
                
        val_int_acc = correct_int / total_val
        val_clar_acc = correct_clar / total_val
        val_date_acc = correct_date / total_val
        combined_acc = (val_int_acc + val_clar_acc + val_date_acc) / 3.0
        
        print(f"Epoch {epoch:02d}/{epochs:02d} | Train Loss: {avg_train_loss:.4f} | "
              f"Intent Acc: {val_int_acc*100:.2f}% | Clar Acc: {val_clar_acc*100:.2f}% | "
              f"Date Acc: {val_date_acc*100:.2f}% | Time: {train_time:.1f}s")
              
        if combined_acc > best_val_acc:
            best_val_acc = combined_acc
            torch.save({
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "config": config.__dict__,
                "val_acc": best_val_acc
            }, best_checkpoint_path)
            print(f"  --> Saved new best checkpoint to {best_checkpoint_path} (Acc: {best_val_acc*100:.2f}%)")

    print("\nChatbot model training finished.")

if __name__ == "__main__":
    train_chatbot_model()
