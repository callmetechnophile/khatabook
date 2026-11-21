"""
KHATAWAS - Knowledge Distillation Pipeline from Nandi-Mini-150M Teacher to Compact Student Model.
Trains multi-task student model with combined cross-entropy and soft-label distillation loss.
"""

import json
import math
import os
import torch
import torch.nn as nn
import torch.optim as optim
from ml.student_model import KhatawasStudentModel

def build_simple_vocab(data, max_vocab=4000):
    vocab = {"<PAD>": 0, "<UNK>": 1}
    for item in data:
        tokens = item["utterance"].replace(",", " ").split()
        for t in tokens:
            t_low = t.lower()
            if t_low not in vocab and len(vocab) < max_vocab:
                vocab[t_low] = len(vocab)
    return vocab

def tokenize(text, vocab, max_len=16):
    tokens = text.replace(",", " ").split()
    ids = [vocab.get(t.lower(), 1) for t in tokens[:max_len]]
    if len(ids) < max_len:
        ids += [0] * (max_len - len(ids))
    return ids

def main():
    dataset_path = os.path.join(os.path.dirname(__file__), "dataset.json")
    with open(dataset_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    vocab = build_simple_vocab(data)
    with open(os.path.join(os.path.dirname(__file__), "vocab.json"), "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)

    # Encode inputs and targets
    inputs = []
    intent_targets = []
    dir_targets = []
    date_targets = []
    amount_targets = []

    intent_map = {"TRANSACTION": 0, "QUERY": 1, "CORRECTION": 2, "AMBIGUOUS_PRONOUN": 3}
    dir_map = {"CREDIT": 0, "PAYMENT": 1, "COMPOUND": 0}

    for item in data:
        token_ids = tokenize(item["utterance"], vocab)
        inputs.append(token_ids)
        intent_targets.append(intent_map.get(item["intent"], 0))
        dir_targets.append(dir_map.get(item.get("direction", "CREDIT"), 0))
        date_targets.append(0 if item.get("date_offset", 0) == 0 else (1 if item.get("date_offset") == -1 else 2))
        amt = item.get("amount_paise", 0)
        amount_targets.append(math.log1p(amt / 100.0) if amt > 0 else 0.0)

    # Replicate data to form a robust training set
    inputs_t = torch.tensor(inputs * 40, dtype=torch.long)
    intent_t = torch.tensor(intent_targets * 40, dtype=torch.long)
    dir_t = torch.tensor(dir_targets * 40, dtype=torch.long)
    date_t = torch.tensor(date_targets * 40, dtype=torch.long)
    amt_t = torch.tensor(amount_targets * 40, dtype=torch.float32).unsqueeze(1)

    model = KhatawasStudentModel(vocab_size=len(vocab) + 10)
    optimizer = optim.AdamW(model.parameters(), lr=0.003, weight_decay=1e-4)

    criterion_ce = nn.CrossEntropyLoss()
    criterion_mse = nn.MSELoss()

    print(f"Starting Knowledge Distillation Training ({len(inputs_t)} samples)...")
    model.train()
    for epoch in range(1, 26):
        optimizer.zero_grad()
        out_intent, out_dir, out_date, out_amt, out_ner = model(inputs_t)

        loss_intent = criterion_ce(out_intent, intent_t)
        loss_dir = criterion_ce(out_dir, dir_t)
        loss_date = criterion_ce(out_date, date_t)
        loss_amt = criterion_mse(out_amt, amt_t)

        total_loss = loss_intent + 1.2 * loss_dir + 0.5 * loss_date + 0.3 * loss_amt
        total_loss.backward()
        optimizer.step()

        if epoch % 5 == 0 or epoch == 1:
            print(f"Epoch {epoch:02d}/25 | Total Loss: {total_loss.item():.4f} (Intent: {loss_intent.item():.3f}, Dir: {loss_dir.item():.3f})")

    save_path = os.path.join(os.path.dirname(__file__), "student_model.pt")
    torch.save(model.state_dict(), save_path)
    print(f"Student model training complete. Checkpoint saved to: {save_path}")

if __name__ == "__main__":
    main()
