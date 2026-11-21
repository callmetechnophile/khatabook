"""
KHATAWAS - Compact Indic Student Model Architecture
Multi-Task Encoder with Prediction Heads for Intent, Direction, Entity Tagging, Amount, and Date.
Designed for on-device mobile inference (<15 MB footprint, <20 ms latency).
"""

import torch
import torch.nn as nn
import torch.nn.functional as F

class KhatawasStudentModel(nn.Module):
    def __init__(
        self,
        vocab_size: int = 4000,
        embed_dim: int = 128,
        hidden_dim: int = 256,
        num_intents: int = 4,      # TRANSACTION, QUERY, CORRECTION, UNKNOWN
        num_directions: int = 2,   # CREDIT, PAYMENT
        num_ner_tags: int = 3,     # O, B-CUST, I-CUST
        num_dates: int = 3         # TODAY, YESTERDAY, DAY_BEFORE
    ):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=0)
        
        # Bidirectional GRU/LSTM backbone for compact, low-latency sequence modeling
        self.encoder = nn.GRU(
            input_size=embed_dim,
            hidden_size=hidden_dim // 2,
            num_layers=2,
            batch_first=True,
            bidirectional=True,
            dropout=0.1
        )
        
        # Multi-task prediction heads
        # Head 1: Intent classification
        self.head_intent = nn.Sequential(
            nn.Linear(hidden_dim, 64),
            nn.ReLU(),
            nn.Linear(64, num_intents)
        )
        
        # Head 2: Direction classification (Credit vs Payment)
        self.head_direction = nn.Sequential(
            nn.Linear(hidden_dim, 64),
            nn.ReLU(),
            nn.Linear(64, num_directions)
        )
        
        # Head 3: Token sequence tagging for customer entity (BIO tags)
        self.head_ner = nn.Linear(hidden_dim, num_ner_tags)
        
        # Head 4: Relative date offset classification
        self.head_date = nn.Linear(hidden_dim, num_dates)
        
        # Head 5: Amount scalar regression head (normalized log paise)
        self.head_amount = nn.Sequential(
            nn.Linear(hidden_dim, 64),
            nn.ReLU(),
            nn.Linear(64, 1)
        )

    def forward(self, input_ids: torch.Tensor):
        # input_ids: (batch_size, seq_len)
        x = self.embedding(input_ids) # (batch, seq_len, embed_dim)
        seq_out, h_n = self.encoder(x) # seq_out: (batch, seq_len, hidden_dim)
        
        # Global pooled representation (mean over non-padding tokens)
        pooled = torch.mean(seq_out, dim=1) # (batch, hidden_dim)
        
        intent_logits = self.head_intent(pooled)
        direction_logits = self.head_direction(pooled)
        date_logits = self.head_date(pooled)
        amount_pred = self.head_amount(pooled)
        ner_logits = self.head_ner(seq_out) # (batch, seq_len, num_ner_tags)
        
        return intent_logits, direction_logits, date_logits, amount_pred, ner_logits
