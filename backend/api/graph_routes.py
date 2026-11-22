"""
KHATAWAS Backend - Graph Routes & Endpoints (V1)
Exposes tenant-isolated native SurrealDB graph capabilities to authorized clients.
"""

from typing import Dict, Any, Optional
from fastapi import APIRouter, Depends, HTTPException, Query, status

from backend.app.auth import get_current_user_claims, verify_tenant_access
from backend.services.graph_service import graph_service, GraphIntegrityError
from backend.services.relationship_resolution_service import resolution_service
from backend.services.graph_reconciliation_service import reconciliation_service
from backend.models.graph_models import (
    CreateRelationshipRequest,
    ConfirmRelationshipRequest,
    RejectRelationshipRequest,
    CorrectRelationshipRequest,
    RelationshipResponse,
    ResolveCustomerRequest,
    CustomerResolutionResponse,
    GraphTraversalResponse,
    ReconciliationReport
)

router = APIRouter(prefix="/api/v1/graph", tags=["Graph Relationships & Resolution"])

@router.post("/relationships", response_model=RelationshipResponse, status_code=status.HTTP_201_CREATED)
def create_relationship(
    request: CreateRelationshipRequest,
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)
    actor_uid = claims.get("user_uid", "system")

    try:
        return graph_service.create_family_relationship(
            shop_uid=authorized_shop,
            request=request,
            actor_uid=actor_uid
        )
    except GraphIntegrityError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))

@router.post("/relationships/confirm", response_model=RelationshipResponse)
def confirm_relationship(
    request: ConfirmRelationshipRequest,
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)
    actor_uid = claims.get("user_uid", "system")

    try:
        return graph_service.confirm_family_relationship(
            shop_uid=authorized_shop,
            relationship_uid=request.relationship_uid,
            actor_uid=actor_uid
        )
    except GraphIntegrityError as e:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))

@router.post("/relationships/reject", response_model=RelationshipResponse)
def reject_relationship(
    request: RejectRelationshipRequest,
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)

    try:
        return graph_service.reject_family_relationship(
            shop_uid=authorized_shop,
            relationship_uid=request.relationship_uid,
            reason=request.reason
        )
    except GraphIntegrityError as e:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(e))

@router.post("/relationships/correct", response_model=RelationshipResponse)
def correct_relationship(
    request: CorrectRelationshipRequest,
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)
    actor_uid = claims.get("user_uid", "system")

    try:
        return graph_service.correct_family_relationship(
            shop_uid=authorized_shop,
            request=request,
            actor_uid=actor_uid
        )
    except GraphIntegrityError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))

@router.get("/relationships/{customer_uid}")
def get_customer_relationships(
    customer_uid: str,
    confirmation_status: str = Query("confirmed", pattern="^(confirmed|unconfirmed|rejected|all)$"),
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)

    edges = graph_service.get_customer_family_graph(
        shop_uid=authorized_shop,
        customer_uid=customer_uid,
        confirmation_filter=confirmation_status
    )
    return {
        "shop_uid": authorized_shop,
        "customer_uid": customer_uid,
        "confirmation_filter": confirmation_status,
        "total": len(edges),
        "relationships": edges
    }

@router.post("/resolve", response_model=CustomerResolutionResponse)
def resolve_spoken_customer(
    request: ResolveCustomerRequest,
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)

    return resolution_service.resolve_customer(
        shop_uid=authorized_shop,
        query_text=request.query_text,
        language=request.language or "hi-IN"
    )

@router.get("/traversal/{customer_uid}", response_model=GraphTraversalResponse)
def traverse_customer_graph(
    customer_uid: str,
    depth: int = Query(1, ge=1, le=3),
    confirmation_filter: str = Query("confirmed"),
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)

    edges = graph_service.get_customer_family_graph(authorized_shop, customer_uid, confirmation_filter)
    transactions = graph_service.get_customer_transactions_graph(authorized_shop, customer_uid)

    return GraphTraversalResponse(
        customer_uid=customer_uid,
        shop_uid=authorized_shop,
        direct_relationships=edges,
        transactions=transactions
    )

@router.post("/reconcile", response_model=ReconciliationReport)
def reconcile_graph_data(
    apply_repairs: bool = Query(False, description="Whether to apply automated consistency repairs"),
    shop_id: Optional[str] = None,
    claims: Dict[str, Any] = Depends(get_current_user_claims)
):
    target_shop = shop_id or claims.get("shop_uid", "default_kirana")
    authorized_shop = verify_tenant_access(target_shop, claims)

    # Only owners or admins can trigger reconciliation
    role = claims.get("role", "viewer")
    if role not in ["owner", "admin"]:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only shop owners or admins can execute graph reconciliation."
        )

    return reconciliation_service.audit_graph_integrity(
        shop_uid=authorized_shop,
        apply_repairs=apply_repairs
    )
