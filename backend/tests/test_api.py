"""
Unit & Integration Tests for KHATAWAS Backend API & SurrealDB Cloud Layer
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.surreal_client import cloud_db

client = TestClient(app)

AUTH_HEADER = {"Authorization": "Bearer test_token", "X-Shop-ID": "shop:test_kirana"}

def test_health_check():
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert data["surrealdb_connected"] is True

def test_shop_authentication():
    payload = {
        "shop_name": "Test Kirana Store",
        "owner_phone": "+919876543210",
        "secret_pin": "1234"
    }
    response = client.post("/api/v1/auth/shop", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "shop_id" in data
    assert "access_token" in data

def test_unauthorized_sync_rejected():
    payload = {
        "shop_id": "shop:test_kirana",
        "batch_id": "batch-001",
        "events": []
    }
    response = client.post("/api/v1/sync/batch", json=payload)
    assert response.status_code == 401

def test_idempotent_sync_batch():
    shop_id = "shop:test_kirana"
    event_1 = {
        "event_id": "test-uuid-001",
        "customer_name": "Ramesh",
        "amount_paise": 34000,
        "type": "CREDIT",
        "timestamp": 1732204800000,
        "note": "Ramesh ka 340 udhaar"
    }
    event_2 = {
        "event_id": "test-uuid-002",
        "customer_name": "Ramesh",
        "amount_paise": 20000,
        "type": "PAYMENT",
        "timestamp": 1732204900000,
        "note": "kal 200 diya"
    }

    payload = {
        "shop_id": shop_id,
        "batch_id": "batch-test-01",
        "events": [event_1, event_2]
    }

    # 1. First ingestion attempt: should accept both
    res1 = client.post("/api/v1/sync/batch", json=payload, headers=AUTH_HEADER)
    assert res1.status_code == 200
    data1 = res1.json()
    assert data1["ingested_count"] == 2
    assert data1["duplicate_count"] == 0
    assert data1["customer_balances"]["Ramesh"] == 14000 # 34000 - 20000

    # 2. Second ingestion attempt with exact same event_ids: must be idempotent!
    res2 = client.post("/api/v1/sync/batch", json=payload, headers=AUTH_HEADER)
    assert res2.status_code == 200
    data2 = res2.json()
    assert data2["ingested_count"] == 0
    assert data2["duplicate_count"] == 2
    for r in data2["results"]:
        assert r["status"] == "DUPLICATE_ACCEPTED"

    # Verify balance was NOT double-counted
    res_ledger = client.get(f"/api/v1/customers/Ramesh/ledger?shop_id={shop_id}", headers=AUTH_HEADER)
    assert res_ledger.status_code == 200
    ledger_data = res_ledger.json()
    assert ledger_data["balance_paise"] == 14000 # Still 14000!

def test_query_customers():
    shop_id = "shop:test_kirana"
    res = client.get(f"/api/v1/customers?shop_id={shop_id}", headers=AUTH_HEADER)
    assert res.status_code == 200
    data = res.json()
    assert data["total_customers"] >= 1
    ramesh = next(c for c in data["customers"] if c["name"] == "Ramesh")
    assert ramesh["balance_paise"] == 14000
    assert ramesh["balance_rupees"] == 140.0
    assert ramesh["status"] == "OWES"

def test_daily_graph_projections_and_cleanup():
    shop_id = "shop:test_kirana"
    res = client.get(f"/api/v1/projections/daily?shop_id={shop_id}", headers=AUTH_HEADER)
    assert res.status_code == 200
    data = res.json()
    assert data["total_credit_paise"] >= 34000
    assert data["total_payment_paise"] >= 20000

    # Test expiry worker
    res_clean = client.post("/api/v1/admin/cleanup-projections", headers=AUTH_HEADER)
    assert res_clean.status_code == 200
    clean_data = res_clean.json()
    assert clean_data["status"] == "COMPLETED"
    # Permanent transactions still intact:
    res_ledger = client.get(f"/api/v1/customers/Ramesh/ledger?shop_id={shop_id}", headers=AUTH_HEADER)
    assert res_ledger.status_code == 200
    assert len(res_ledger.json()["transactions"]) >= 2
