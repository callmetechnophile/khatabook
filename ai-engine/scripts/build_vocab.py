"""
KHATAWAS Tokenizer & Vocabulary Builder.

Constructs an Indic-first WordPiece / subword vocabulary from the training dataset,
covering:
- Devanagari script (Hindi)
- Kannada script
- Latin script (English, Hinglish, Kanglish)
- Numeric digits and financial symbols
- Special tokens: [PAD], [UNK], [CLS], [SEP]
"""

import json
import os
import re
from collections import Counter
from typing import List, Dict, Set

SPECIAL_TOKENS = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]

def build_vocabulary(train_path: str, output_vocab_path: str, max_vocab_size: int = 16000):
    word_counts = Counter()
    char_set = set()
    
    print(f"Reading training data from {train_path}...")
    with open(train_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            item = json.loads(line)
            text = item["text"].strip()
            
            # Record individual characters
            for char in text:
                char_set.add(char)
                
            # Basic whitespace / punctuation splitting
            tokens = re.findall(r'\w+|[^\w\s]', text, re.UNICODE)
            for token in tokens:
                word_counts[token.lower()] += 1
                word_counts[token] += 1
                
    print(f"Found {len(char_set)} unique Unicode characters and {len(word_counts)} unique tokens.")
    
    # Initialize vocabulary with special tokens
    vocab = list(SPECIAL_TOKENS)
    
    # Add all unique characters
    sorted_chars = sorted(list(char_set))
    for c in sorted_chars:
        if c not in vocab:
            vocab.append(c)
            
    # Add most frequent whole tokens / subwords
    remaining_slots = max_vocab_size - len(vocab)
    most_common = word_counts.most_common(remaining_slots)
    for word, _ in most_common:
        if word not in vocab:
            vocab.append(word)
            
    print(f"Final vocabulary size: {len(vocab)} tokens.")
    
    # Write vocab.txt
    os.makedirs(os.path.dirname(output_vocab_path), exist_ok=True)
    with open(output_vocab_path, "w", encoding="utf-8") as f:
        for token in vocab:
            f.write(token + "\n")
            
    # Also write a JSON mapping
    json_path = output_vocab_path.replace(".txt", ".json")
    vocab_dict = {token: idx for idx, token in enumerate(vocab)}
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(vocab_dict, f, ensure_ascii=False, indent=2)
        
    print(f"Saved vocabulary to {output_vocab_path} and {json_path}")
    return vocab_dict

if __name__ == "__main__":
    train_file = os.path.join(os.path.dirname(__file__), "..", "data", "train.jsonl")
    vocab_file = os.path.join(os.path.dirname(__file__), "..", "exports", "vocab.txt")
    build_vocabulary(train_file, vocab_file)
