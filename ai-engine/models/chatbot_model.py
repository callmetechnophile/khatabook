"""
KHATAWAS Chatbot Intent and Query Planner Model Architecture.

Compact Multi-Task Edge Encoder:
- Input: Token IDs and Attention Mask (max_len=64)
- Transformer Backbone: 4 layers, hidden_size=256, num_heads=4, intermediate=512
- Output Heads:
  1. Intent Head (11 classes)
  2. Clarification Head (2 classes: requires_clarification)
  3. Date Filter Head (6 classes: NONE, TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, LAST_MONTH)
  4. Category Filter Head (5 classes: NONE, CREDIT, DEBIT, STUCK, ILLEGAL)
  5. Relation Head (5 classes: NONE, FATHER, BROTHER, SON, FAMILY)
  6. BIO Token Slot Head (3 classes: O, B-CUSTOMER, I-CUSTOMER)
"""

import math
from typing import Dict, Any, Optional
import torch
import torch.nn as nn
import torch.nn.functional as F

INTENT_CLASSES = [
    "GET_CUSTOMER_BALANCE",
    "GET_CUSTOMER_HISTORY",
    "GET_CUSTOMER_LAST_PAYMENT",
    "LIST_OUTSTANDING_CUSTOMERS",
    "GET_TOTAL_OUTSTANDING",
    "GET_PERIOD_SUMMARY",
    "LIST_CATEGORY_TRANSACTIONS",
    "GET_TRANSACTIONS_BY_DATE",
    "GET_TOP_DEBTOR",
    "FOLLOW_UP_QUERY",
    "UNKNOWN"
]

DATE_FILTER_CLASSES = ["NONE", "TODAY", "YESTERDAY", "THIS_WEEK", "THIS_MONTH", "LAST_MONTH"]
CATEGORY_FILTER_CLASSES = ["NONE", "CREDIT", "DEBIT", "STUCK", "ILLEGAL"]
RELATION_CLASSES = ["NONE", "FATHER", "BROTHER", "SON", "FAMILY"]
BIO_CLASSES = ["O", "B-CUSTOMER", "I-CUSTOMER"]

class ChatbotModelConfig:
    def __init__(
        self,
        vocab_size: int = 1000,
        hidden_size: int = 256,
        num_layers: int = 4,
        num_heads: int = 4,
        intermediate_size: int = 512,
        max_seq_len: int = 64,
        dropout: float = 0.1
    ):
        self.vocab_size = vocab_size
        self.hidden_size = hidden_size
        self.num_layers = num_layers
        self.num_heads = num_heads
        self.intermediate_size = intermediate_size
        self.max_seq_len = max_seq_len
        self.dropout = dropout


class ChatbotIntentModel(nn.Module):
    def __init__(self, config: ChatbotModelConfig):
        super().__init__()
        self.config = config
        
        self.word_embeddings = nn.Embedding(config.vocab_size, config.hidden_size, padding_idx=0)
        self.position_embeddings = nn.Embedding(config.max_seq_len, config.hidden_size)
        self.layer_norm = nn.LayerNorm(config.hidden_size)
        self.dropout = nn.Dropout(config.dropout)
        
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=config.hidden_size,
            nhead=config.num_heads,
            dim_feedforward=config.intermediate_size,
            dropout=config.dropout,
            activation="gelu",
            batch_first=True,
            norm_first=True
        )
        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=config.num_layers)
        
        # Heads
        self.intent_head = nn.Linear(config.hidden_size, len(INTENT_CLASSES))
        self.clarification_head = nn.Linear(config.hidden_size, 2)
        self.date_filter_head = nn.Linear(config.hidden_size, len(DATE_FILTER_CLASSES))
        self.category_filter_head = nn.Linear(config.hidden_size, len(CATEGORY_FILTER_CLASSES))
        self.relation_head = nn.Linear(config.hidden_size, len(RELATION_CLASSES))
        self.bio_head = nn.Linear(config.hidden_size, len(BIO_CLASSES))
        
    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> Dict[str, torch.Tensor]:
        seq_len = input_ids.size(1)
        positions = torch.arange(seq_len, dtype=torch.long, device=input_ids.device).unsqueeze(0)
        
        x = self.word_embeddings(input_ids) + self.position_embeddings(positions)
        x = self.layer_norm(x)
        x = self.dropout(x)
        
        src_key_padding_mask = (attention_mask == 0)
        encoded = self.encoder(x, src_key_padding_mask=src_key_padding_mask)
        
        # Pooled output ([CLS] token at index 0)
        cls_rep = encoded[:, 0, :]
        
        intent_logits = self.intent_head(cls_rep)
        clarification_logits = self.clarification_head(cls_rep)
        date_filter_logits = self.date_filter_head(cls_rep)
        category_filter_logits = self.category_filter_head(cls_rep)
        relation_logits = self.relation_head(cls_rep)
        bio_logits = self.bio_head(encoded)
        
        return {
            "intent_logits": intent_logits,
            "clarification_logits": clarification_logits,
            "date_filter_logits": date_filter_logits,
            "category_filter_logits": category_filter_logits,
            "relation_logits": relation_logits,
            "bio_logits": bio_logits
        }

    def count_parameters(self) -> int:
        return sum(p.numel() for p in self.parameters() if p.requires_grad)
