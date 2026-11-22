"""
Tests for SurrealDB Native Graph Tenant Isolation & Security.
Verifies that graph relationships and traversals strictly respect shop boundaries
and prevent cross-shop data leaks or access.
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.auth import generate_jwt_token
from backend.database.surreal_client import cloud_db
from backend.services.graph_service import graph_service, GraphIntegrityError
from backend.models.graph_models import CreateRelationshipRequest

client = TestClient(app)

@pytest.fixture(scope="module")
def setup_tenant_env():
    shop_a = "tenant_shop_alpha"
    shop_b = "tenant_shop_beta"

    token_a = generate_jwt_token(user_uid="user_alpha", shop_uid=shop_a, role="owner")
    token_b = generate_jwt_token(user_uid="user_beta", shop_uid=shop_b, role="owner")

    headers_a = {"Authorization": f"Bearer {token_a}"}
    headers_b = {"Authorization": f"Bearer {token_b}"}

    # Customers in Shop A
    c_a1 = cloud_db.get_or_create_customer(shop_a, "Pooja Sharma")
    c_a2 = cloud_db.get_or_create_customer(shop_a, "Anita Sharma")

    # Customers in Shop B
    c_b1 = cloud_db.get_or_create_customer(shop_b, "Rahul Verma")
    c_b2 = cloud_db.get_or_create_customer(shop_b, "Sanjay Verma")

    # Relationship inside Shop A
    req_a = CreateRelationshipRequest(
        subject_customer_uid=c_a1["customer_uid"],
        related_customer_uid=c_a2["customer_uid"],
        relationship_type="SISTER",
        confirmation_status="confirmed"
    )
    graph_service.create_family_relationship(shop_a, req_a)

    return {
        "shop_a": shop_a,
        "shop_b": shop_b,
        "headers_a": headers_a,
        "headers_b": headers_b,
        "c_a1": c_a1,
        "c_a2": c_a2,
        "c_b1": c_b1,
        "c_b2": c_b2
    }

def test_prevent_cross_shop_relationship_creation(setup_tenant_env):
    headers_a = setup_tenant_env["headers_a"]
    c_a1 = setup_tenant_env["c_a1"]["customer_uid"]
    c_b1 = setup_tenant_env["c_b1"]["customer_uid"]

    # Attempt to link customer in Shop A to customer in Shop B
    payload = {
        "subject_customer_uid": c_a1,
        "related_customer_uid": c_b1,
        "relationship_type": "COUSIN",
        "confirmation_status": "confirmed"
    }
    res = client.post("/api/v1/graph/relationships", json=payload, headers=headers_a)
    assert res.status_code == 400
    assert "Cross-shop or invalid endpoint" in res.json()["detail"]

def test_tenant_isolated_traversal(setup_tenant_env):
    shop_b = setup_tenant_env["shop_b"]
    headers_b = setup_tenant_env["headers_b"]
    c_a1 = setup_tenant_env["c_a1"]["customer_uid"]

    # Shop B queries graph for customer in Shop A -> Must return 0 relationships
    res = client.get(f"/api/v1/graph/relationships/{c_a1}", headers=headers_b)
    assert res.status_code == 200
    data = res.json()
    assert data["total"] == 0
    assert len(data["relationships"]) == 0

def test_unauthorized_shop_parameter_rejected(setup_tenant_env):
    headers_a = setup_tenant_env["headers_a"]
    shop_b = setup_tenant_env["shop_b"]
    c_b1 = setup_tenant_env["c_b1"]["customer_uid"]

    # User A tries to query Shop B explicitly
    res = client.get(f"/api/v1/graph/relationships/{c_b1}?shop_id={shop_b}", headers=headers_a)
    assert res.status_code == 403
    assert "Forbidden" in res.json()["detail"]
