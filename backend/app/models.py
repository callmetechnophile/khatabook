"""
KHATAWAS Backend - Pydantic Data Models
"""

from typing import List, Optional, Dict
from pydantic import BaseModel, Field
from datetime import datetime

class ShopAuthRequest(BaseModel):
    shop_name: str
    owner_phone: str
    secret_pin: str

class ShopAuthResponse(BaseModel):
    shop_id: str
    access_token: str
    token_type: str = "bearer"
    expires_in_hours: int = 720

class SyncEventItem(BaseModel):
    event_id: str
    customer_name: str
    amount_paise: int
    type: str # 'CREDIT', 'PAYMENT', 'REVERSAL'
    timestamp: int # epoch millis
    note: Optional[str] = ""
    event_hash: Optional[str] = None
    previous_event_hash: Optional[str] = None
    schema_version: Optional[int] = 1

class SyncBatchRequest(BaseModel):
    shop_id: str
    batch_id: str
    events: List[SyncEventItem]

class SyncEventResult(BaseModel):
    event_id: str
    status: str # 'ACCEPTED', 'DUPLICATE_ACCEPTED', 'REJECTED'
    server_timestamp: str
    error_message: Optional[str] = None

class SyncBatchResponse(BaseModel):
    batch_id: str
    status: str
    ingested_count: int
    duplicate_count: int
    rejected_count: int = 0
    results: List[SyncEventResult]
    customer_balances: Dict[str, int]

class CustomerSummary(BaseModel):
    customer_id: str
    name: str
    balance_paise: int
    balance_rupees: float
    status: str # 'OWES', 'PAID_UP', 'SURPLUS'

class CustomerListResponse(BaseModel):
    shop_id: str
    total_customers: int
    customers: List[CustomerSummary]

class TransactionItem(BaseModel):
    event_id: str
    type: str
    amount_paise: int
    timestamp: str
    note: Optional[str] = ""

class CustomerLedgerResponse(BaseModel):
    customer_name: str
    balance_paise: int
    transactions: List[TransactionItem]

class TemporaryProjectionEvent(BaseModel):
    event_id: str
    customer_name: str
    amount_paise: int
    type: str
    expires_at: str

class DailyProjectionResponse(BaseModel):
    shop_id: str
    window: str = "24_HOURS"
    total_credit_paise: int
    total_payment_paise: int
    active_events_count: int
    recent_events: List[TemporaryProjectionEvent]

class CleanupResponse(BaseModel):
    status: str
    purged_records: int
    timestamp: str
