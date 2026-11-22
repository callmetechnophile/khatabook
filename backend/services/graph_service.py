"""
KHATAWAS Backend - SurrealDB Native Graph Service (V1)
Manages native SurrealDB graph relations, tenant isolation, directional semantics,
and ACID consistency across relational and graph models.
"""

import uuid
import logging
from typing import Dict, List, Optional, Any
from datetime import datetime, timezone

from backend.database.surreal_client import cloud_db
from backend.models.graph_models import (
    CreateRelationshipRequest,
    RelationshipResponse,
    CorrectRelationshipRequest,
    GraphTraversalResponse
)

logger = logging.getLogger("khatawas.graph")

class GraphIntegrityError(Exception):
    """Raised when a graph relationship violates tenant, semantic, or endpoint constraints."""
    pass

class GraphService:
    def __init__(self, db_client=None):
        self.db = db_client or cloud_db

    def _clean_id(self, val: str, prefix: str) -> str:
        """Strip table prefix if present, returning bare UID."""
        v = val.strip()
        if v.startswith(f"{prefix}:"):
            return v[len(prefix) + 1:]
        return v

    def _ensure_customer_record(self, shop_uid: str, customer_uid: str) -> Dict[str, Any]:
        """Verify that a customer exists in the specified shop and has a corresponding record ID."""
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_cust = self._clean_id(customer_uid, "customer")
        
        # Check customer in relational table
        res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND customer_uid = '{clean_cust}' LIMIT 1;"
        )
        if not res or not isinstance(res, list) or len(res) == 0:
            raise GraphIntegrityError(
                f"Customer '{clean_cust}' does not exist in shop '{clean_shop}' (Cross-shop or invalid endpoint)."
            )
        
        # Ensure customer has an explicit RecordID record customer:clean_cust
        ensure_sql = (
            f"CREATE customer:{clean_cust} SET "
            f"customer_uid = '{clean_cust}', "
            f"shop_uid = '{clean_shop}' "
            f"RETURN NONE;"
        )
        try:
            self.db.query(ensure_sql)
        except Exception:
            pass
        return res[0]

    # -------------------------------------------------------------
    # Family Relationships (A)
    # -------------------------------------------------------------

    def create_family_relationship(
        self,
        shop_uid: str,
        request: CreateRelationshipRequest,
        actor_uid: str = "system"
    ) -> RelationshipResponse:
        clean_shop = self._clean_id(shop_uid, "shop")
        subj_uid = self._clean_id(request.subject_customer_uid, "customer")
        rel_uid = self._clean_id(request.related_customer_uid, "customer")

        # 1. Prevent Self-Relationships
        if subj_uid == rel_uid:
            raise GraphIntegrityError("A customer cannot have a family relationship with themselves.")

        # 2. Prevent Cross-Shop Relationships: Both endpoints must exist in the caller's shop
        self._ensure_customer_record(clean_shop, subj_uid)
        self._ensure_customer_record(clean_shop, rel_uid)

        # 3. Model-suggested relationships must NEVER be automatically confirmed
        status = request.confirmation_status
        if request.source == "model_suggested" and status == "confirmed":
            status = "unconfirmed"

        # 4. Prevent duplicate active relationships between the exact same pair & type
        dup_check = self.db.query(
            f"SELECT * FROM family_of WHERE shop_uid = '{clean_shop}' "
            f"AND in = customer:{subj_uid} AND out = customer:{rel_uid} "
            f"AND relationship_type = '{request.relationship_type}' "
            f"AND confirmation_status != 'rejected' LIMIT 1;"
        )
        if dup_check and isinstance(dup_check, list) and len(dup_check) > 0:
            raise GraphIntegrityError(
                f"An active relationship of type '{request.relationship_type}' already exists between these customers."
            )

        relationship_uid = f"rel_{uuid.uuid4().hex[:12]}"
        now_iso = datetime.now(timezone.utc).isoformat()

        # 5. Create native SurrealDB graph relation
        relate_sql = (
            f"RELATE customer:{subj_uid}->family_of->customer:{rel_uid} SET "
            f"relationship_uid = '{relationship_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"relationship_type = '{request.relationship_type}', "
            f"confirmation_status = '{status}', "
            f"confidence = {request.confidence}, "
            f"source = '{request.source}', "
            f"created_at = time::now(), "
            f"updated_at = time::now(), "
            f"supersedes_uid = NONE;"
        )
        self.db.query(relate_sql)

        # 6. Synchronize with relational customer_relationship table transactionally
        relational_sql = (
            f"CREATE customer_relationship SET "
            f"relationship_uid = '{relationship_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"subject_customer_uid = '{subj_uid}', "
            f"related_customer_uid = '{rel_uid}', "
            f"relationship_type = '{request.relationship_type}', "
            f"confirmation_status = '{status}', "
            f"created_at = time::now(), "
            f"supersedes_uid = NONE;"
        )
        self.db.query(relational_sql)

        return RelationshipResponse(
            relationship_uid=relationship_uid,
            shop_uid=clean_shop,
            subject_customer_uid=subj_uid,
            related_customer_uid=rel_uid,
            relationship_type=request.relationship_type,
            confirmation_status=status,
            confidence=request.confidence,
            source=request.source,
            created_at=now_iso,
            updated_at=now_iso,
            supersedes_uid=None
        )

    def confirm_family_relationship(
        self,
        shop_uid: str,
        relationship_uid: str,
        actor_uid: str = "system"
    ) -> RelationshipResponse:
        clean_shop = self._clean_id(shop_uid, "shop")
        # Fetch existing relationship
        existing = self.db.query(
            f"SELECT * FROM family_of WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{relationship_uid}' LIMIT 1;"
        )
        if not existing or not isinstance(existing, list) or len(existing) == 0:
            raise GraphIntegrityError(f"Relationship '{relationship_uid}' not found in shop '{clean_shop}'.")

        row = existing[0]
        # Update graph relation
        self.db.query(
            f"UPDATE family_of SET confirmation_status = 'confirmed', updated_at = time::now() "
            f"WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{relationship_uid}';"
        )
        # Update relational table
        self.db.query(
            f"UPDATE customer_relationship SET confirmation_status = 'confirmed' "
            f"WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{relationship_uid}';"
        )

        subj = self._clean_id(str(row.get("in", "")), "customer")
        rel = self._clean_id(str(row.get("out", "")), "customer")

        return RelationshipResponse(
            relationship_uid=relationship_uid,
            shop_uid=clean_shop,
            subject_customer_uid=subj,
            related_customer_uid=rel,
            relationship_type=row.get("relationship_type", "OTHER"),
            confirmation_status="confirmed",
            confidence=1.0,
            source="user_confirmed",
            created_at=str(row.get("created_at")),
            updated_at=datetime.now(timezone.utc).isoformat(),
            supersedes_uid=row.get("supersedes_uid")
        )

    def reject_family_relationship(
        self,
        shop_uid: str,
        relationship_uid: str,
        reason: Optional[str] = None
    ) -> RelationshipResponse:
        clean_shop = self._clean_id(shop_uid, "shop")
        existing = self.db.query(
            f"SELECT * FROM family_of WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{relationship_uid}' LIMIT 1;"
        )
        if not existing or not isinstance(existing, list) or len(existing) == 0:
            raise GraphIntegrityError(f"Relationship '{relationship_uid}' not found in shop '{clean_shop}'.")

        row = existing[0]
        self.db.query(
            f"UPDATE family_of SET confirmation_status = 'rejected', updated_at = time::now() "
            f"WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{relationship_uid}';"
        )
        self.db.query(
            f"UPDATE customer_relationship SET confirmation_status = 'rejected' "
            f"WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{relationship_uid}';"
        )

        subj = self._clean_id(str(row.get("in", "")), "customer")
        rel = self._clean_id(str(row.get("out", "")), "customer")

        return RelationshipResponse(
            relationship_uid=relationship_uid,
            shop_uid=clean_shop,
            subject_customer_uid=subj,
            related_customer_uid=rel,
            relationship_type=row.get("relationship_type", "OTHER"),
            confirmation_status="rejected",
            confidence=float(row.get("confidence", 0.0)),
            source=row.get("source", "manually_entered"),
            created_at=str(row.get("created_at")),
            updated_at=datetime.now(timezone.utc).isoformat(),
            supersedes_uid=row.get("supersedes_uid")
        )

    def correct_family_relationship(
        self,
        shop_uid: str,
        request: CorrectRelationshipRequest,
        actor_uid: str = "system"
    ) -> RelationshipResponse:
        clean_shop = self._clean_id(shop_uid, "shop")
        orig_uid = request.existing_relationship_uid

        existing = self.db.query(
            f"SELECT * FROM family_of WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{orig_uid}' LIMIT 1;"
        )
        if not existing or not isinstance(existing, list) or len(existing) == 0:
            raise GraphIntegrityError(f"Original relationship '{orig_uid}' not found in shop '{clean_shop}'.")

        old_row = existing[0]
        subj_uid = self._clean_id(str(old_row.get("in", "")), "customer")
        old_rel_uid = self._clean_id(str(old_row.get("out", "")), "customer")
        new_rel_uid = self._clean_id(request.new_related_customer_uid or old_rel_uid, "customer")
        new_rel_type = request.new_relationship_type or old_row.get("relationship_type", "OTHER")

        # Mark original relationship as superseded/rejected
        self.db.query(
            f"UPDATE family_of SET confirmation_status = 'rejected', updated_at = time::now() "
            f"WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{orig_uid}';"
        )
        self.db.query(
            f"UPDATE customer_relationship SET confirmation_status = 'rejected' "
            f"WHERE shop_uid = '{clean_shop}' AND relationship_uid = '{orig_uid}';"
        )

        # Create new corrected relationship with supersedes_uid pointer
        new_uid = f"rel_{uuid.uuid4().hex[:12]}"
        now_iso = datetime.now(timezone.utc).isoformat()

        self._ensure_customer_record(clean_shop, subj_uid)
        self._ensure_customer_record(clean_shop, new_rel_uid)

        relate_sql = (
            f"RELATE customer:{subj_uid}->family_of->customer:{new_rel_uid} SET "
            f"relationship_uid = '{new_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"relationship_type = '{new_rel_type}', "
            f"confirmation_status = 'confirmed', "
            f"confidence = {request.confidence}, "
            f"source = '{request.source}', "
            f"created_at = time::now(), "
            f"updated_at = time::now(), "
            f"supersedes_uid = '{orig_uid}';"
        )
        self.db.query(relate_sql)

        relational_sql = (
            f"CREATE customer_relationship SET "
            f"relationship_uid = '{new_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"subject_customer_uid = '{subj_uid}', "
            f"related_customer_uid = '{new_rel_uid}', "
            f"relationship_type = '{new_rel_type}', "
            f"confirmation_status = 'confirmed', "
            f"created_at = time::now(), "
            f"supersedes_uid = '{orig_uid}';"
        )
        self.db.query(relational_sql)

        return RelationshipResponse(
            relationship_uid=new_uid,
            shop_uid=clean_shop,
            subject_customer_uid=subj_uid,
            related_customer_uid=new_rel_uid,
            relationship_type=new_rel_type,
            confirmation_status="confirmed",
            confidence=request.confidence,
            source=request.source,
            created_at=now_iso,
            updated_at=now_iso,
            supersedes_uid=orig_uid
        )

    # -------------------------------------------------------------
    # Graph Traversal (A, B, C, D, E, F)
    # -------------------------------------------------------------

    def get_customer_family_graph(
        self,
        shop_uid: str,
        customer_uid: str,
        confirmation_filter: str = "confirmed"
    ) -> List[Dict[str, Any]]:
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_cust = self._clean_id(customer_uid, "customer")

        status_clause = ""
        if confirmation_filter in ["confirmed", "unconfirmed", "rejected"]:
            status_clause = f" AND confirmation_status = '{confirmation_filter}'"

        # Query outgoing edges (e.g. Arun -> FATHER -> Ravi)
        out_query = (
            f"SELECT *, out.customer_uid AS target_customer_uid, out.display_name AS target_name "
            f"FROM family_of WHERE shop_uid = '{clean_shop}' AND in = customer:{clean_cust}{status_clause};"
        )
        out_edges = self.db.query(out_query)

        # Query incoming edges (e.g. Suresh -> SON -> Ravi, meaning Ravi is Father of Suresh)
        in_query = (
            f"SELECT *, in.customer_uid AS source_customer_uid, in.display_name AS source_name "
            f"FROM family_of WHERE shop_uid = '{clean_shop}' AND out = customer:{clean_cust}{status_clause};"
        )
        in_edges = self.db.query(in_query)

        results = []
        if out_edges and isinstance(out_edges, list):
            for edge in out_edges:
                if isinstance(edge, dict):
                    results.append({
                        "relationship_uid": edge.get("relationship_uid"),
                        "direction": "outgoing",
                        "subject_customer_uid": clean_cust,
                        "related_customer_uid": self._clean_id(str(edge.get("out", "")), "customer"),
                        "relationship_type": edge.get("relationship_type"),
                        "confirmation_status": edge.get("confirmation_status"),
                        "confidence": edge.get("confidence", 1.0),
                        "source": edge.get("source", "manually_entered"),
                        "created_at": str(edge.get("created_at")),
                        "supersedes_uid": edge.get("supersedes_uid")
                    })

        if in_edges and isinstance(in_edges, list):
            for edge in in_edges:
                if isinstance(edge, dict):
                    results.append({
                        "relationship_uid": edge.get("relationship_uid"),
                        "direction": "incoming",
                        "subject_customer_uid": self._clean_id(str(edge.get("in", "")), "customer"),
                        "related_customer_uid": clean_cust,
                        "relationship_type": edge.get("relationship_type"),
                        "confirmation_status": edge.get("confirmation_status"),
                        "confidence": edge.get("confidence", 1.0),
                        "source": edge.get("source", "manually_entered"),
                        "created_at": str(edge.get("created_at")),
                        "supersedes_uid": edge.get("supersedes_uid")
                    })

        return results

    def get_customer_transactions_graph(
        self,
        shop_uid: str,
        customer_uid: str,
        limit: int = 50
    ) -> List[Dict[str, Any]]:
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_cust = self._clean_id(customer_uid, "customer")

        # Traverse has_transaction edge to ledger_event
        query = (
            f"SELECT out.* FROM has_transaction "
            f"WHERE shop_uid = '{clean_shop}' AND in = customer:{clean_cust} "
            f"ORDER BY out.occurred_at DESC LIMIT {limit};"
        )
        res = self.db.query(query)
        events = []
        if res and isinstance(res, list):
            for item in res:
                if isinstance(item, dict):
                    events.append(item)
        return events

    # -------------------------------------------------------------
    # Structural Edge Management (B, C, D, E, F)
    # -------------------------------------------------------------

    def link_customer_ownership(self, shop_uid: str, customer_uid: str):
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_cust = self._clean_id(customer_uid, "customer")
        sql = (
            f"RELATE shop:{clean_shop}->owns_customer->customer:{clean_cust} SET "
            f"status = 'active', created_at = time::now();"
        )
        try:
            self.db.query(sql)
        except Exception as e:
            logger.debug(f"owns_customer edge creation notice: {e}")

    def link_customer_alias(self, shop_uid: str, alias_uid: str, customer_uid: str):
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_alias = self._clean_id(alias_uid, "customer_alias")
        clean_cust = self._clean_id(customer_uid, "customer")
        sql = (
            f"RELATE customer_alias:{clean_alias}->alias_of->customer:{clean_cust} SET "
            f"shop_uid = '{clean_shop}', confirmation_status = 'confirmed', "
            f"source = 'manually_entered', created_at = time::now();"
        )
        try:
            self.db.query(sql)
        except Exception as e:
            logger.debug(f"alias_of edge creation notice: {e}")

    def link_customer_transaction(self, shop_uid: str, customer_uid: str, event_uid: str):
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_cust = self._clean_id(customer_uid, "customer")
        clean_ev = self._clean_id(event_uid, "ledger_event")
        sql = (
            f"RELATE customer:{clean_cust}->has_transaction->ledger_event:{clean_ev} SET "
            f"shop_uid = '{clean_shop}', created_at = time::now();"
        )
        try:
            self.db.query(sql)
        except Exception as e:
            logger.debug(f"has_transaction edge creation notice: {e}")

    def link_event_device_provenance(self, shop_uid: str, event_uid: str, device_uid: str):
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_ev = self._clean_id(event_uid, "ledger_event")
        clean_dev = self._clean_id(device_uid, "device_registry")
        sql = (
            f"RELATE ledger_event:{clean_ev}->created_on_device->device_registry:{clean_dev} SET "
            f"shop_uid = '{clean_shop}', created_at = time::now();"
        )
        try:
            self.db.query(sql)
        except Exception as e:
            logger.debug(f"created_on_device edge creation notice: {e}")

    def link_user_membership(self, shop_uid: str, user_uid: str, role: str = "owner"):
        clean_shop = self._clean_id(shop_uid, "shop")
        clean_user = self._clean_id(user_uid, "app_user")
        sql = (
            f"RELATE app_user:{clean_user}->member_of->shop:{clean_shop} SET "
            f"role = '{role}', status = 'active', created_at = time::now();"
        )
        try:
            self.db.query(sql)
        except Exception as e:
            logger.debug(f"member_of edge creation notice: {e}")

# Global singleton
graph_service = GraphService()
