"""
Tests for SurrealDB Native Graph Relationships (V1).
Verifies creation, confirmation, rejection, correction, self-loop prevention,
and directional semantics on native SurrealDB graph relations.
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.auth import generate_jwt_token
from backend.database.surreal_client import cloud_db
from backend.services.graph_service import graph_service, GraphIntegrityError
from backend.models.graph_models import CreateRelationshipRequest, CorrectRelationshipRequest

client = TestClient(app)

@pytest.fixture(scope="module")
def setup_graph_env():
    shop_uid = "graph_shop_01"
    token = generate_jwt_token(user_uid="graph_user_01", shop_uid=shop_uid, role="owner")
    headers = {"Authorization": f"Bearer {token}"}

    # Create customers in shop
    c1 = cloud_db.get_or_create_customer(shop_uid, "Ravi Kumar")
    c2 = cloud_db.get_or_create_customer(shop_uid, "Arun Kumar")
    c3 = cloud_db.get_or_create_customer(shop_uid, "Suresh Kumar")

    return {
        "shop_uid": shop_uid,
        "headers": headers,
        "c1": c1, # Ravi (Father)
        "c2": c2, # Arun (Son)
        "c3": c3  # Suresh (Brother)
    }

def test_create_confirmed_relationship(setup_graph_env):
    shop_uid = setup_graph_env["shop_uid"]
    headers = setup_graph_env["headers"]
    arun = setup_graph_env["c2"]["customer_uid"]
    ravi = setup_graph_env["c1"]["customer_uid"]

    payload = {
        "subject_customer_uid": arun,
        "related_customer_uid": ravi,
        "relationship_type": "FATHER",
        "confirmation_status": "confirmed",
        "confidence": 1.0,
        "source": "user_confirmed"
    }
    res = client.post("/api/v1/graph/relationships", json=payload, headers=headers)
    assert res.status_code == 201
    data = res.json()
    assert data["confirmation_status"] == "confirmed"
    assert data["relationship_type"] == "FATHER"
    assert data["subject_customer_uid"] == arun
    assert data["related_customer_uid"] == ravi

def test_create_unconfirmed_model_suggested_relationship(setup_graph_env):
    shop_uid = setup_graph_env["shop_uid"]
    headers = setup_graph_env["headers"]
    ravi = setup_graph_env["c1"]["customer_uid"]
    suresh = setup_graph_env["c3"]["customer_uid"]

    # Even if client requests 'confirmed', model_suggested source MUST be forced to 'unconfirmed'
    payload = {
        "subject_customer_uid": ravi,
        "related_customer_uid": suresh,
        "relationship_type": "BROTHER",
        "confirmation_status": "confirmed",
        "confidence": 0.85,
        "source": "model_suggested"
    }
    res = client.post("/api/v1/graph/relationships", json=payload, headers=headers)
    assert res.status_code == 201
    data = res.json()
    assert data["confirmation_status"] == "unconfirmed"
    assert data["source"] == "model_suggested"

def test_confirm_relationship(setup_graph_env):
    shop_uid = setup_graph_env["shop_uid"]
    headers = setup_graph_env["headers"]
    ravi = setup_graph_env["c1"]["customer_uid"]
    suresh = setup_graph_env["c3"]["customer_uid"]

    # Create unconfirmed relationship
    req = CreateRelationshipRequest(
        subject_customer_uid=suresh,
        related_customer_uid=ravi,
        relationship_type="BROTHER",
        confirmation_status="unconfirmed",
        source="manually_entered"
    )
    rel = graph_service.create_family_relationship(shop_uid, req)
    assert rel.confirmation_status == "unconfirmed"

    # Confirm it via API
    res = client.post("/api/v1/graph/relationships/confirm", json={"relationship_uid": rel.relationship_uid}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["confirmation_status"] == "confirmed"

def test_reject_relationship(setup_graph_env):
    shop_uid = setup_graph_env["shop_uid"]
    headers = setup_graph_env["headers"]
    ravi = setup_graph_env["c1"]["customer_uid"]
    suresh = setup_graph_env["c3"]["customer_uid"]

    # Create relationship to reject
    req = CreateRelationshipRequest(
        subject_customer_uid=ravi,
        related_customer_uid=suresh,
        relationship_type="COUSIN",
        confirmation_status="unconfirmed",
        source="model_suggested"
    )
    rel = graph_service.create_family_relationship(shop_uid, req)

    res = client.post("/api/v1/graph/relationships/reject", json={"relationship_uid": rel.relationship_uid, "reason": "Incorrect relation"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["confirmation_status"] == "rejected"

def test_correct_relationship_preserves_history(setup_graph_env):
    shop_uid = setup_graph_env["shop_uid"]
    headers = setup_graph_env["headers"]
    arun = setup_graph_env["c2"]["customer_uid"]
    suresh = setup_graph_env["c3"]["customer_uid"]
    ravi = setup_graph_env["c1"]["customer_uid"]

    # Create original relationship (incorrectly labeled UNCLE)
    orig_req = CreateRelationshipRequest(
        subject_customer_uid=arun,
        related_customer_uid=suresh,
        relationship_type="UNCLE",
        confirmation_status="confirmed"
    )
    orig_rel = graph_service.create_family_relationship(shop_uid, orig_req)

    # Correct it to COUSIN
    correct_payload = {
        "existing_relationship_uid": orig_rel.relationship_uid,
        "new_relationship_type": "COUSIN",
        "confidence": 1.0,
        "source": "user_confirmed"
    }
    res = client.post("/api/v1/graph/relationships/correct", json=correct_payload, headers=headers)
    assert res.status_code == 200
    corrected = res.json()
    assert corrected["relationship_type"] == "COUSIN"
    assert corrected["supersedes_uid"] == orig_rel.relationship_uid

    # Verify original relationship is marked rejected/superseded
    edges = graph_service.get_customer_family_graph(shop_uid, arun, confirmation_filter="rejected")
    superseded = [e for e in edges if e["relationship_uid"] == orig_rel.relationship_uid]
    assert len(superseded) == 1

def test_prevent_self_relationship(setup_graph_env):
    headers = setup_graph_env["headers"]
    ravi = setup_graph_env["c1"]["customer_uid"]

    payload = {
        "subject_customer_uid": ravi,
        "related_customer_uid": ravi,
        "relationship_type": "BROTHER"
    }
    res = client.post("/api/v1/graph/relationships", json=payload, headers=headers)
    assert res.status_code == 400
    assert "cannot have a family relationship with themselves" in res.json()["detail"]

def test_prevent_duplicate_active_relationship(setup_graph_env):
    headers = setup_graph_env["headers"]
    arun = setup_graph_env["c2"]["customer_uid"]
    ravi = setup_graph_env["c1"]["customer_uid"]

    # First test already created arun -> FATHER -> ravi
    payload = {
        "subject_customer_uid": arun,
        "related_customer_uid": ravi,
        "relationship_type": "FATHER"
    }
    res = client.post("/api/v1/graph/relationships", json=payload, headers=headers)
    assert res.status_code == 400
    assert "already exists" in res.json()["detail"]

def test_graph_directional_semantics(setup_graph_env):
    shop_uid = setup_graph_env["shop_uid"]
    arun = setup_graph_env["c2"]["customer_uid"]
    ravi = setup_graph_env["c1"]["customer_uid"]

    # Outgoing from Arun should show Ravi as Father
    arun_graph = graph_service.get_customer_family_graph(shop_uid, arun, confirmation_filter="confirmed")
    assert any(e["direction"] == "outgoing" and e["related_customer_uid"] == ravi and e["relationship_type"] == "FATHER" for e in arun_graph)

    # Incoming to Ravi should show Arun as child with Father relationship
    ravi_graph = graph_service.get_customer_family_graph(shop_uid, ravi, confirmation_filter="confirmed")
    assert any(e["direction"] == "incoming" and e["subject_customer_uid"] == arun and e["relationship_type"] == "FATHER" for e in ravi_graph)
