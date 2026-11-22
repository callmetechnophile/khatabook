"""
KHATAWAS Backend - Security & Tenant Isolation Tests
Verifies cross-tenant isolation, JWT verification, and unauthorized query rejection.
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.auth import generate_jwt_token

client = TestClient(app)

def test_cross_tenant_query_rejected():
    shop_a_token = generate_jwt_token(user_uid="user_a", shop_uid="shop_alpha", role="owner")
    shop_b_token = generate_jwt_token(user_uid="user_b", shop_uid="shop_beta", role="owner")

    header_a = {"Authorization": f"Bearer {shop_a_token}"}
    header_b = {"Authorization": f"Bearer {shop_b_token}"}

    # Shop A syncs a customer
    payload_a = {
        "shop_uid": "shop_alpha",
        "batch_id": "batch_sec_01",
        "events": [{
            "event_uid": "sec_ev_01",
            "customer_name": "Alpha Customer",
            "amount_paise": 50000,
            "category": "CREDIT"
        }]
    }
    res_sync = client.post("/api/v1/sync/events", json=payload_a, headers=header_a)
    assert res_sync.status_code == 200

    # Shop A can read their customer
    res_a = client.get("/api/v1/customers?shop_id=shop_alpha", headers=header_a)
    assert res_a.status_code == 200
    assert any(c["name"] == "Alpha Customer" for c in res_a.json()["customers"])

    # Attack: User B attempts to query Shop A's customer records using User B's token
    res_attack = client.get("/api/v1/customers?shop_id=shop_alpha", headers=header_b)
    assert res_attack.status_code == 403
    assert "Forbidden" in res_attack.json()["detail"]

def test_cross_tenant_sync_injection_rejected():
    shop_b_token = generate_jwt_token(user_uid="user_b", shop_uid="shop_beta", role="owner")
    header_b = {"Authorization": f"Bearer {shop_b_token}"}

    # Attack: User B attempts to inject transactions into Shop A
    malicious_payload = {
        "shop_uid": "shop_alpha",
        "batch_id": "malicious_batch_01",
        "events": [{
            "event_uid": "mal_ev_01",
            "customer_name": "Injected Customer",
            "amount_paise": 999999,
            "category": "CREDIT"
        }]
    }
    res_inj = client.post("/api/v1/sync/events", json=malicious_payload, headers=header_b)
    assert res_inj.status_code == 403

def test_unauthenticated_request_rejected():
    res = client.get("/api/v1/customers")
    assert res.status_code == 401

def test_invalid_signature_token_rejected():
    bad_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.bad_signature"
    res = client.get("/api/v1/customers?shop_id=shop_alpha", headers={"Authorization": f"Bearer {bad_token}"})
    assert res.status_code == 401

