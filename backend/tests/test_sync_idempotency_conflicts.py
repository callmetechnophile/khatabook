"""
KHATAWAS Backend - Synchronization Idempotency & Conflict Tests
Verifies exact duplicate detection, conflict detection for differing digests,
and hash chain validation.
"""

import pytest
from backend.database.surreal_client import SurrealDatabaseClient
from backend.app.canonicalization import canonical_serialize_v2, compute_chained_digest, GENESIS_HASH

@pytest.fixture(scope="module")
def db_client():
    return SurrealDatabaseClient(url="mem://", namespace="test_sync_ns", database="test_sync_db")

def test_idempotent_duplicate_same_uuid_same_digest(db_client):
    shop_uid = "idemp_shop_01"
    device_uid = "device_idemp_01"

    event = {
        "event_uid": "idemp_ev_001",
        "customer_name": "Rohan",
        "amount_paise": 25000,
        "category": "CREDIT",
        "device_sequence": 0
    }

    # First upload: accepted
    res1, bal1 = db_client.ingest_sync_batch(shop_uid, [event], device_uid)
    assert res1[0]["status"] == "ACCEPTED"
    assert res1[0]["result"] == "accepted"
    assert bal1["Rohan"] == 25000

    # Second upload with exact same content: must return DUPLICATE_ACCEPTED / duplicate
    res2, bal2 = db_client.ingest_sync_batch(shop_uid, [event], device_uid)
    assert res2[0]["status"] == "DUPLICATE_ACCEPTED"
    assert res2[0]["result"] == "duplicate"
    # Balance must NOT be double-counted!
    assert bal2["Rohan"] == 25000

def test_conflict_detection_same_uuid_differing_digest(db_client):
    shop_uid = "conflict_shop_01"
    device_uid = "device_conflict_01"

    orig_event = {
        "event_uid": "conf_ev_shared_uuid",
        "customer_name": "Vikas",
        "amount_paise": 10000,
        "category": "CREDIT",
        "device_sequence": 0
    }

    # Upload original
    res_orig, bal_orig = db_client.ingest_sync_batch(shop_uid, [orig_event], device_uid)
    assert res_orig[0]["status"] == "ACCEPTED"
    assert bal_orig["Vikas"] == 10000

    # Conflicting upload: Same UUID 'conf_ev_shared_uuid' but tampered amount ₹500 (50000 paise)!
    conflicting_event = {
        "event_uid": "conf_ev_shared_uuid", # Identical UUID
        "customer_name": "Vikas",
        "amount_paise": 50000,              # Differing content!
        "category": "CREDIT",
        "device_sequence": 0
    }

    res_conf, bal_conf = db_client.ingest_sync_batch(shop_uid, [conflicting_event], device_uid)
    assert res_conf[0]["status"] == "CONFLICT"
    assert res_conf[0]["result"] == "conflict"
    assert "Conflict detected" in res_conf[0]["error_message"]

    # Verify original event is preserved and balance remains ₹100 (10000 paise), NOT overwritten to ₹500
    ledger = db_client.get_customer_ledger(shop_uid, "Vikas")
    assert ledger["balance_paise"] == 10000
    assert len(ledger["transactions"]) == 1
    assert ledger["transactions"][0]["amount_paise"] == 10000

    # Verify conflict record was persisted in SurrealDB conflict_record table
    conflicts = db_client.query("SELECT * FROM conflict_record WHERE event_uid = 'conf_ev_shared_uuid';")
    assert len(conflicts) >= 1
    assert conflicts[0]["conflict_type"] == "DIGEST_MISMATCH"

def test_multi_event_hash_chain_continuity(db_client):
    shop_uid = "chain_shop_01"
    device_uid = "chain_device_01"

    # Event 1
    ev1_dict = {
        "event_uid": "chain_ev_01",
        "shop_uid": shop_uid,
        "customer_uid": "cust_chain",
        "originating_device_uid": device_uid,
        "device_sequence": 0,
        "event_type": "TRANSACTION",
        "category": "CREDIT",
        "amount_paise": 10000,
        "balance_effect_paise": 10000,
        "currency": "INR",
        "occurred_at": "2026-11-22T00:00:00Z",
        "canonicalization_version": 2,
        "schema_version": 1,
        "previous_digest": GENESIS_HASH
    }
    canon1 = canonical_serialize_v2(ev1_dict)
    digest1 = compute_chained_digest(GENESIS_HASH, canon1)
    ev1_dict["event_digest"] = digest1

    # Event 2 chained to digest1
    ev2_dict = {
        "event_uid": "chain_ev_02",
        "shop_uid": shop_uid,
        "customer_uid": "cust_chain",
        "originating_device_uid": device_uid,
        "device_sequence": 1,
        "event_type": "TRANSACTION",
        "category": "CREDIT",
        "amount_paise": 20000,
        "balance_effect_paise": 20000,
        "currency": "INR",
        "occurred_at": "2026-11-22T00:01:00Z",
        "canonicalization_version": 2,
        "schema_version": 1,
        "previous_digest": digest1
    }
    canon2 = canonical_serialize_v2(ev2_dict)
    digest2 = compute_chained_digest(digest1, canon2)
    ev2_dict["event_digest"] = digest2

    # Ingest sequential batch
    results, balances = db_client.ingest_sync_batch(shop_uid, [ev1_dict, ev2_dict], device_uid)
    assert results[0]["status"] == "ACCEPTED"
    assert results[1]["status"] == "ACCEPTED"

    # Verify event_chain_head tracks digest2
    head = db_client.get_chain_head(shop_uid, device_uid)
    assert head["last_sequence"] == 1
    assert head["last_digest"] == digest2

