"""
KHATAWAS Backend - Financial Balance Engine Tests
Verifies deterministic integer-paise calculations, reversals, corrections, and category semantics.
"""

import pytest
from backend.database.surreal_client import SurrealDatabaseClient

@pytest.fixture(scope="module")
def db_client():
    return SurrealDatabaseClient(url="mem://", namespace="test_fin_ns", database="test_fin_db")

def test_credit_debit_balance_flow(db_client):
    shop_uid = "fin_shop_01"
    device_uid = "dev_01"

    # 1. Customer Ramesh takes ₹350 udhaar (CREDIT) -> Balance: +35000 paise
    ev1 = [{
        "event_uid": "ev_credit_350",
        "customer_name": "Ramesh Kumar",
        "amount_paise": 35000,
        "category": "CREDIT",
        "device_sequence": 0
    }]
    _, balances1 = db_client.ingest_sync_batch(shop_uid, ev1, device_uid)
    assert balances1["Ramesh Kumar"] == 35000

    # 2. Ramesh pays ₹150 (DEBIT) -> Balance: 35000 - 15000 = +20000 paise
    ev2 = [{
        "event_uid": "ev_debit_150",
        "customer_name": "Ramesh Kumar",
        "amount_paise": 15000,
        "category": "DEBIT",
        "device_sequence": 1
    }]
    _, balances2 = db_client.ingest_sync_batch(shop_uid, ev2, device_uid)
    assert balances2["Ramesh Kumar"] == 20000

def test_explicit_balance_effects_stuck_illegal(db_client):
    shop_uid = "fin_shop_02"
    device_uid = "dev_02"

    # STUCK payment: client specifies 0 balance effect pending bank verification
    ev_stuck = [{
        "event_uid": "ev_stuck_500",
        "customer_name": "Suresh",
        "amount_paise": 50000,
        "category": "STUCK",
        "balance_effect_paise": 0,
        "device_sequence": 0
    }]
    _, bal_stuck = db_client.ingest_sync_batch(shop_uid, ev_stuck, device_uid)
    assert bal_stuck["Suresh"] == 0

    # ILLEGAL payment: client explicitly flags without reducing debt
    ev_illegal = [{
        "event_uid": "ev_illegal_100",
        "customer_name": "Suresh",
        "amount_paise": 10000,
        "category": "ILLEGAL",
        "balance_effect_paise": 0,
        "device_sequence": 1
    }]
    _, bal_illegal = db_client.ingest_sync_batch(shop_uid, ev_illegal, device_uid)
    assert bal_illegal["Suresh"] == 0

def test_reversal_creates_new_event_preserving_history(db_client):
    shop_uid = "fin_shop_03"
    device_uid = "dev_03"

    # Original mistake: entered ₹1000 credit instead of ₹100
    ev_orig = [{
        "event_uid": "ev_mistake_1000",
        "customer_name": "Ganesh",
        "amount_paise": 100000,
        "category": "CREDIT",
        "device_sequence": 0
    }]
    db_client.ingest_sync_batch(shop_uid, ev_orig, device_uid)

    # Reversal event: cancels original credit by specifying balance_effect_paise = -100000
    ev_reversal = [{
        "event_uid": "ev_reversal_1000",
        "customer_name": "Ganesh",
        "amount_paise": 100000,
        "event_type": "REVERSAL",
        "category": "DEBIT",
        "balance_effect_paise": -100000,
        "reverses_event_uid": "ev_mistake_1000",
        "device_sequence": 1
    }]
    _, bal_rev = db_client.ingest_sync_batch(shop_uid, ev_reversal, device_uid)
    assert bal_rev["Ganesh"] == 0

    # Verify both original event and reversal event exist in immutable ledger history
    ledger = db_client.get_customer_ledger(shop_uid, "Ganesh")
    assert len(ledger["transactions"]) == 2
    uids = [tx["event_uid"] for tx in ledger["transactions"]]
    assert "ev_mistake_1000" in uids
    assert "ev_reversal_1000" in uids

