"""
KHATAWAS Backend - Graph Data Models & Schemas (V1)
Defines strongly-typed request and response contracts for SurrealDB native graph operations.
"""

from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field, field_validator

VALID_RELATIONSHIP_TYPES = [
    "FATHER", "MOTHER", "SON", "DAUGHTER", "BROTHER", "SISTER",
    "SPOUSE", "GRANDFATHER", "GRANDMOTHER", "UNCLE", "AUNT", "COUSIN", "OTHER"
]

VALID_CONFIRMATION_STATUSES = ["confirmed", "unconfirmed", "rejected"]
VALID_SOURCES = ["user_confirmed", "manually_entered", "model_suggested", "imported"]

class CreateRelationshipRequest(BaseModel):
    subject_customer_uid: str = Field(..., description="Customer initiating the relationship (e.g. Arun)")
    related_customer_uid: str = Field(..., description="Target relative customer (e.g. Ravi)")
    relationship_type: str = Field(..., description="Semantic type: FATHER, MOTHER, SON, etc.")
    confirmation_status: str = Field("unconfirmed", description="confirmed, unconfirmed, or rejected")
    confidence: float = Field(1.0, ge=0.0, le=1.0)
    source: str = Field("manually_entered", description="user_confirmed, manually_entered, model_suggested, imported")

    @field_validator("relationship_type")
    @classmethod
    def validate_type(cls, v: str) -> str:
        v_upper = v.strip().upper()
        if v_upper not in VALID_RELATIONSHIP_TYPES:
            raise ValueError(f"Invalid relationship_type: {v}. Must be one of {VALID_RELATIONSHIP_TYPES}")
        return v_upper

    @field_validator("confirmation_status")
    @classmethod
    def validate_status(cls, v: str) -> str:
        v_lower = v.strip().lower()
        if v_lower not in VALID_CONFIRMATION_STATUSES:
            raise ValueError(f"Invalid confirmation_status: {v}. Must be one of {VALID_CONFIRMATION_STATUSES}")
        return v_lower

    @field_validator("source")
    @classmethod
    def validate_source(cls, v: str) -> str:
        v_lower = v.strip().lower()
        if v_lower not in VALID_SOURCES:
            raise ValueError(f"Invalid source: {v}. Must be one of {VALID_SOURCES}")
        return v_lower


class ConfirmRelationshipRequest(BaseModel):
    relationship_uid: str = Field(..., description="Unique ID of the relationship to confirm")


class RejectRelationshipRequest(BaseModel):
    relationship_uid: str = Field(..., description="Unique ID of the relationship to reject")
    reason: Optional[str] = None


class CorrectRelationshipRequest(BaseModel):
    existing_relationship_uid: str = Field(..., description="Original relationship UID to supersede")
    new_related_customer_uid: Optional[str] = None
    new_relationship_type: Optional[str] = None
    confidence: float = 1.0
    source: str = "user_confirmed"

    @field_validator("new_relationship_type")
    @classmethod
    def validate_type(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return None
        v_upper = v.strip().upper()
        if v_upper not in VALID_RELATIONSHIP_TYPES:
            raise ValueError(f"Invalid relationship_type: {v}")
        return v_upper


class RelationshipResponse(BaseModel):
    relationship_uid: str
    shop_uid: str
    subject_customer_uid: str
    related_customer_uid: str
    relationship_type: str
    confirmation_status: str
    confidence: float
    source: str
    created_at: str
    updated_at: str
    supersedes_uid: Optional[str] = None


class ResolveCustomerRequest(BaseModel):
    query_text: str = Field(..., description="Natural language spoken phrase, e.g. 'Ravi ke papa'")
    language: Optional[str] = "hi-IN"
    candidate_names: Optional[List[str]] = None


class CustomerCandidate(BaseModel):
    customer_uid: str
    display_name: str
    confidence: float
    match_source: str  # exact_uuid, exact_name, alias_match, family_traversal
    relationship_detail: Optional[str] = None


class CustomerResolutionResponse(BaseModel):
    status: str  # resolved, ambiguous, not_found, clarification_needed
    resolved_customer_uid: Optional[str] = None
    display_name: Optional[str] = None
    resolution_method: Optional[str] = None
    confidence: float
    candidates: List[CustomerCandidate] = []
    clarification_question: Optional[str] = None


class GraphTraversalRequest(BaseModel):
    customer_uid: str
    depth: int = Field(1, ge=1, le=3)
    confirmation_filter: Optional[str] = "confirmed"  # confirmed, all, unconfirmed


class GraphTraversalResponse(BaseModel):
    customer_uid: str
    shop_uid: str
    direct_relationships: List[Dict[str, Any]] = []
    transactions: List[Dict[str, Any]] = []


class ReconciliationReport(BaseModel):
    shop_uid: str
    total_customers: int
    total_transactions: int
    missing_owns_customer_edges: int
    missing_transaction_edges: int
    missing_device_provenance_edges: int
    cross_shop_edges_detected: int
    self_loop_edges_detected: int
    duplicate_active_relationships: int
    inconsistencies: List[Dict[str, Any]] = []
    repairs_applied: int = 0
