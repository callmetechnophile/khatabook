"""
Tests for SurrealDB Graph Reconciliation Service.
Verifies auditing, reporting, and idempotent repair of structural graph edges
without mutating or deleting authoritative financial events.
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.auth import generate_jwt_token
from backend.database.surreal_client import cloud_db
from backend.services.graph_reconciliation_service import reconciliation_service

client = TestClient(app)

@pytest.fixture(scope="module")
def setup_reconcile_env():
    shop_uid = "rec_shop_01"
    token_owner = generate_jwt_token(user_uid="rec_owner", shop_uid=shop_uid, role="owner")
    token_viewer = generate_jwt_token(user_uid="rec_viewer", shop_uid=shop_uid, role="viewer")

    headers_owner = {"Authorization": f"Bearer {token_owner}"}
    headers_viewer = {"Authorization": f"Bearer {token_viewer}"}

    # Create customer and transaction
    cust = cloud_db.get_or_create_customer(shop_uid, "Gopal Das")
    
    # Ingest event
    batch = {
        "shop_uid": shop_uid,
        "batch_id": "rec_batch_01",
        "events": [
            {
                "event_uid": "rec_ev_01",
                "customer_name": "Gopal Das",
                "amount_paise": 25000,
                "category": "CREDIT"
            }
        ]
    }
    client.post("/api/v1/sync/events", json=batch, headers=headers_owner)

    return {
        "shop_uid": shop_uid,
        "headers_owner": headers_owner,
        "headers_viewer": headers_viewer,
        "customer": cust
    }

def test_viewer_role_cannot_trigger_reconciliation(setup_reconcile_env):
    headers_viewer = setup_reconcile_env["headers_viewer"]
    res = client.post("/api/v1/graph/reconcile", headers=headers_viewer)
    assert res.status_code == 403
    assert "Only shop owners or admins" in res.json()["detail"]

def test_reconciliation_audit_report(setup_reconcile_env):
    headers_owner = setup_reconcile_env["headers_owner"]
    shop_uid = setup_reconcile_env["shop_uid"]

    res = client.post("/api/v1/graph/reconcile?apply_repairs=false", headers=headers_owner)
    assert res.status_code == 200
    report = res.json()
    assert report["shop_uid"] == shop_uid
    assert report["total_customers"] >= 1
    assert report["total_transactions"] >= 1
    assert report["repairs_applied"] == 0

def test_reconciliation_repairs_missing_edges(setup_reconcile_env):
    shop_uid = setup_reconcile_env["shop_uid"]
    headers_owner = setup_reconcile_env["headers_owner"]

    # Delete owns_customer edge intentionally to simulate inconsistency
    cloud_db.query(f"DELETE FROM owns_customer WHERE in = shop:{shop_uid};")

    # Run audit without repair
    report_before = reconciliation_service.audit_graph_integrity(shop_uid, apply_repairs=False)
    assert report_before.missing_owns_customer_edges >= 1

    # Run audit with repair
    res = client.post("/api/v1/graph/reconcile?apply_repairs=true", headers=headers_owner)
    assert res.status_code == 200
    report_after = res.json()
    assert report_after["repairs_applied"] >= 1

    # Verify edge was restored
    edge = cloud_db.query(f"SELECT * FROM owns_customer WHERE in = shop:{shop_uid} LIMIT 1;")
    assert edge and len(edge) > 0
