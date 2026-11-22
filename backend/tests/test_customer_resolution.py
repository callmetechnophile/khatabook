"""
Tests for Graph-Aware Customer Resolution Pipeline.
Verifies exact match, alias match, family graph traversal (Hindi & Kannada),
and mandatory clarification when relatives are unconfirmed or unknown.
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.auth import generate_jwt_token
from backend.database.surreal_client import cloud_db
from backend.services.graph_service import graph_service
from backend.models.graph_models import CreateRelationshipRequest

client = TestClient(app)

@pytest.fixture(scope="module")
def setup_resolution_env():
    shop_uid = "res_shop_01"
    token = generate_jwt_token(user_uid="res_user_01", shop_uid=shop_uid, role="owner")
    headers = {"Authorization": f"Bearer {token}"}

    # Create customers
    c_arun = cloud_db.get_or_create_customer(shop_uid, "Arun Kumar")
    c_ravi = cloud_db.get_or_create_customer(shop_uid, "Ravi Kumar")
    c_deepak = cloud_db.get_or_create_customer(shop_uid, "Deepak Singh")

    # Link Confirmed Family Relationship: Arun -> FATHER -> Ravi
    req = CreateRelationshipRequest(
        subject_customer_uid=c_arun["customer_uid"],
        related_customer_uid=c_ravi["customer_uid"],
        relationship_type="FATHER",
        confirmation_status="confirmed",
        source="user_confirmed"
    )
    graph_service.create_family_relationship(shop_uid, req)

    # Link Unconfirmed Family Relationship: Deepak -> BROTHER -> Ravi
    unc_req = CreateRelationshipRequest(
        subject_customer_uid=c_deepak["customer_uid"],
        related_customer_uid=c_ravi["customer_uid"],
        relationship_type="BROTHER",
        confirmation_status="unconfirmed",
        source="model_suggested"
    )
    graph_service.create_family_relationship(shop_uid, unc_req)

    return {
        "shop_uid": shop_uid,
        "headers": headers,
        "arun": c_arun,
        "ravi": c_ravi,
        "deepak": c_deepak
    }

def test_resolve_exact_name(setup_resolution_env):
    headers = setup_resolution_env["headers"]
    res = client.post("/api/v1/graph/resolve", json={"query_text": "Arun Kumar"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "resolved"
    assert data["resolution_method"] == "exact_name"
    assert data["resolved_customer_uid"] == setup_resolution_env["arun"]["customer_uid"]

def test_resolve_exact_uuid(setup_resolution_env):
    headers = setup_resolution_env["headers"]
    ravi_uid = setup_resolution_env["ravi"]["customer_uid"]
    res = client.post("/api/v1/graph/resolve", json={"query_text": f"customer:{ravi_uid}"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "resolved"
    assert data["resolution_method"] == "exact_uuid"
    assert data["resolved_customer_uid"] == ravi_uid

def test_resolve_family_reference_hindi(setup_resolution_env):
    headers = setup_resolution_env["headers"]
    ravi_uid = setup_resolution_env["ravi"]["customer_uid"]

    # "Arun ke papa ne 500 diye" -> Must resolve to Ravi (Father), NOT Arun
    res = client.post("/api/v1/graph/resolve", json={"query_text": "Arun ke papa ne 500 diye"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "resolved"
    assert data["resolution_method"] == "family_traversal"
    assert data["resolved_customer_uid"] == ravi_uid
    assert data["display_name"] == "Ravi Kumar"

def test_resolve_family_reference_kannada(setup_resolution_env):
    headers = setup_resolution_env["headers"]
    ravi_uid = setup_resolution_env["ravi"]["customer_uid"]

    # "Arun avara tande" (Arun's father in Kannada) -> Must resolve to Ravi
    res = client.post("/api/v1/graph/resolve", json={"query_text": "Arun avara tande 300 kotru"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "resolved"
    assert data["resolution_method"] == "family_traversal"
    assert data["resolved_customer_uid"] == ravi_uid

def test_unconfirmed_family_relationship_causes_clarification(setup_resolution_env):
    headers = setup_resolution_env["headers"]

    # Deepak -> BROTHER -> Ravi is UNCONFIRMED
    # Query for "Deepak ka bhai" must NOT silently assign to Deepak or Ravi
    res = client.post("/api/v1/graph/resolve", json={"query_text": "Deepak ka bhai"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "clarification_needed"
    assert data["resolved_customer_uid"] is None
    assert "unconfirmed" in data["clarification_question"].lower()

def test_unknown_family_member_causes_clarification_no_auto_create(setup_resolution_env):
    headers = setup_resolution_env["headers"]
    arun_uid = setup_resolution_env["arun"]["customer_uid"]

    # Arun has no registered SISTER
    # Query for "Arun ki behen" must NOT assign to Arun and must NOT auto-create a customer
    res = client.post("/api/v1/graph/resolve", json={"query_text": "Arun ki behen ne 200 liye"}, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "clarification_needed"
    assert data["resolved_customer_uid"] is None
    assert "No confirmed sister" in data["clarification_question"]
