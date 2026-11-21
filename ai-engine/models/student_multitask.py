"""
KHATAWAS Compact Multi-Task Student Model.

Designed for edge deployment on Android devices:
- Architecture: 6-layer compact Transformer Encoder (d=384, heads=6, ffn=1024)
- Multi-Task Heads:
  1. Token classification (BIO slot-filling: Customer, Relationship, Amount)
  2. Category classification (CREDIT, DEBIT, STUCK, ILLEGAL)
  3. Intent classification (RECORD_CREDIT, RECORD_PAYMENT, DISPUTE_STUCK, ILLEGAL_FLAG, CLARIFICATION_NEEDED)
  4. Ambiguity / Clarification binary classifier (requires_clarification: False/True)
- Quantization Target: INT8 < 30 MB
- Target Edge Latency: < 25 ms on mobile CPU
"""

import math
import torch
import torch.nn as nn
import torch.nn.functional as F
from typing import Dict, Any, Optional, Tuple, List

# Label Definitions
CATEGORY_LABELS = ["CREDIT", "DEBIT", "STUCK", "ILLEGAL"]
INTENT_LABELS = [
    "RECORD_CREDIT",
    "RECORD_PAYMENT",
    "DISPUTE_STUCK",
    "ILLEGAL_FLAG",
    "CLARIFICATION_NEEDED"
]
BIO_TAGS = [
    "O",
    "B-CUSTOMER",
    "I-CUSTOMER",
    "B-RELATIONSHIP",
    "I-RELATIONSHIP",
    "B-AMOUNT",
    "I-AMOUNT"
]
CLARIFICATION_LABELS = ["NO", "YES"]

class MultiTaskStudentConfig:
    def __init__(
        self,
        vocab_size: int = 32000,
        hidden_size: int = 384,
        num_hidden_layers: int = 6,
        num_attention_heads: int = 6,
        intermediate_size: int = 1024,
        max_position_embeddings: int = 128,
        num_categories: int = len(CATEGORY_LABELS),
        num_intents: int = len(INTENT_LABELS),
        num_bio_tags: int = len(BIO_TAGS),
        dropout: float = 0.1
    ):
        self.vocab_size = vocab_size
        self.hidden_size = hidden_size
        self.num_hidden_layers = num_hidden_layers
        self.num_attention_heads = num_attention_heads
        self.intermediate_size = intermediate_size
        self.max_position_embeddings = max_position_embeddings
        self.num_categories = num_categories
        self.num_intents = num_intents
        self.num_bio_tags = num_bio_tags
        self.dropout = dropout


class CompactMultiTaskStudent(nn.Module):
    def __init__(self, config: MultiTaskStudentConfig):
        super().__init__()
        self.config = config
        
        # Embeddings
        self.token_embeddings = nn.Embedding(config.vocab_size, config.hidden_size, padding_idx=0)
        self.position_embeddings = nn.Embedding(config.max_position_embeddings, config.hidden_size)
        self.layer_norm = nn.LayerNorm(config.hidden_size)
        self.dropout = nn.Dropout(config.dropout)
        
        # Transformer Encoder
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=config.hidden_size,
            nhead=config.num_attention_heads,
            dim_feedforward=config.intermediate_size,
            dropout=config.dropout,
            activation="gelu",
            batch_first=True,
            norm_first=True
        )
        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=config.num_hidden_layers)
        
        # Multi-task Heads
        # 1. Category Classification Head (Sequence level)
        self.category_head = nn.Sequential(
            nn.Linear(config.hidden_size, config.hidden_size // 2),
            nn.GELU(),
            nn.Linear(config.hidden_size // 2, config.num_categories)
        )
        
        # 2. Intent Classification Head (Sequence level)
        self.intent_head = nn.Sequential(
            nn.Linear(config.hidden_size, config.hidden_size // 2),
            nn.GELU(),
            nn.Linear(config.hidden_size // 2, config.num_intents)
        )
        
        # 3. Clarification / Ambiguity Head (Sequence level)
        self.clarification_head = nn.Sequential(
            nn.Linear(config.hidden_size, config.hidden_size // 2),
            nn.GELU(),
            nn.Linear(config.hidden_size // 2, 2)
        )
        
        # 4. Token Classification BIO Head (Token level)
        self.bio_head = nn.Sequential(
            nn.Linear(config.hidden_size, config.hidden_size // 2),
            nn.GELU(),
            nn.Linear(config.hidden_size // 2, config.num_bio_tags)
        )
        
        self.apply(self._init_weights)

    def _init_weights(self, module):
        if isinstance(module, (nn.Linear, nn.Embedding)):
            module.weight.data.normal_(mean=0.0, std=0.02)
            if isinstance(module, nn.Linear) and module.bias is not None:
                module.bias.data.zero_()
        elif isinstance(module, nn.LayerNorm):
            module.bias.data.zero_()
            module.weight.data.fill_(1.0)

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: Optional[torch.Tensor] = None
    ) -> Dict[str, torch.Tensor]:
        """
        Forward pass for inference and training.
        input_ids: [batch_size, seq_len]
        attention_mask: [batch_size, seq_len] (1 for valid tokens, 0 for padding)
        """
        batch_size, seq_len = input_ids.shape
        positions = torch.arange(seq_len, device=input_ids.device).unsqueeze(0).expand(batch_size, -1)
        
        embeddings = self.token_embeddings(input_ids) + self.position_embeddings(positions)
        hidden = self.layer_norm(embeddings)
        hidden = self.dropout(hidden)
        
        # In PyTorch TransformerEncoder, src_key_padding_mask is True for padded positions
        src_key_padding_mask = None
        if attention_mask is not None:
            src_key_padding_mask = (attention_mask == 0)
            
        encoder_out = self.encoder(hidden, src_key_padding_mask=src_key_padding_mask)
        
        # Pooled representation: use token 0 ([CLS]) or masked mean
        if attention_mask is not None:
            mask_expanded = attention_mask.unsqueeze(-1).expand_as(encoder_out)
            sum_hidden = torch.sum(encoder_out * mask_expanded, dim=1)
            sum_mask = torch.clamp(mask_expanded.sum(dim=1), min=1e-9)
            pooled = sum_hidden / sum_mask
        else:
            pooled = encoder_out[:, 0, :]
            
        # Logits
        category_logits = self.category_head(pooled)
        intent_logits = self.intent_head(pooled)
        clarification_logits = self.clarification_head(pooled)
        bio_logits = self.bio_head(encoder_out)
        
        return {
            "category_logits": category_logits,
            "intent_logits": intent_logits,
            "clarification_logits": clarification_logits,
            "bio_logits": bio_logits
        }

    def count_parameters(self) -> int:
        return sum(p.numel() for p in self.parameters() if p.requires_grad)
