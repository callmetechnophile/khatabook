"""
KHATAWAS Backend - Family Relationship & Graph-Aware Customer Resolution Pipeline.
Resolves spoken multilingual references (Hindi, Kannada, English, Hinglish, Romanized)
using exact match, aliases, and native SurrealDB graph traversal.
"""

import re
import logging
from typing import Dict, List, Optional, Tuple, Any

from backend.database.surreal_client import cloud_db
from backend.services.graph_service import graph_service
from backend.models.graph_models import (
    CustomerResolutionResponse,
    CustomerCandidate
)

logger = logging.getLogger("khatawas.resolution")

# Multilingual pattern dictionary mapping spoken/written phrases to canonical relationship types
RELATIONSHIP_PATTERNS = [
    # FATHER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:papa|pita|pitaji|baap|father|dad|thande|tande|appa)\b", "FATHER"),
    (r"'s\s*(?:father|dad)\b", "FATHER"),

    # MOTHER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:mummy|mami|maa|mata|mataji|mother|mom|tayi|thayi|amma)\b", "MOTHER"),
    (r"'s\s*(?:mother|mom)\b", "MOTHER"),

    # SON
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:beta|ladka|putra|son|maga)\b", "SON"),
    (r"'s\s*son\b", "SON"),

    # DAUGHTER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:beti|ladki|putri|daughter|magalu)\b", "DAUGHTER"),
    (r"'s\s*daughter\b", "DAUGHTER"),

    # BROTHER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:bhai|bhaiya|brother|sahodara|anna|tamma)\b", "BROTHER"),
    (r"'s\s*brother\b", "BROTHER"),

    # SISTER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:behen|didi|sister|sahodari|akka|tangi)\b", "SISTER"),
    (r"'s\s*sister\b", "SISTER"),

    # SPOUSE
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:patni|pati|wife|husband|spouse|hendathi|ganda)\b", "SPOUSE"),
    (r"'s\s*(?:wife|husband|spouse)\b", "SPOUSE"),

    # UNCLE
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:chacha|chachaji|mama|mamaji|kaka|uncle|chikkappa|mava)\b", "UNCLE"),

    # AUNT
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:chachi|mami|bua|aunt|chikkamma|atte)\b", "AUNT"),

    # GRANDFATHER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:dada|dadaji|nana|nanaji|grandfather|thatha|ajja)\b", "GRANDFATHER"),

    # GRANDMOTHER
    (r"\b(?:ke|ka|ki|avara|ge|ra|da)?\s*(?:dadi|dadiji|nani|naniji|grandmother|ajji)\b", "GRANDMOTHER"),
]

class RelationshipResolutionService:
    def __init__(self, db_client=None, graph_svc=None):
        self.db = db_client or cloud_db
        self.graph = graph_svc or graph_service

    def extract_family_reference(self, utterance: str) -> Optional[Tuple[str, str]]:
        """
        Extracts (subject_name, relationship_type) from text.
        Example: 'Ravi ke papa ne 500 diye' -> ('ravi', 'FATHER')
                 'Ravi avara tande' -> ('ravi', 'FATHER')
        """
        clean_text = utterance.strip().lower()
        
        # Remove financial keywords to isolate names and relations
        clean_text = re.sub(r"\b(?:ne|ko|se|rupay|rupaye|rs|inr|diye|liye|jama|paid|udhaar|baki|dena|hai|\d+)\b", " ", clean_text)
        
        for pattern, rel_type in RELATIONSHIP_PATTERNS:
            match = re.search(pattern, clean_text)
            if match:
                rel_span = match.span()
                # Text before relationship expression is typically the subject name
                prefix = clean_text[:rel_span[0]].strip()
                prefix = re.sub(r"['’]s$", "", prefix).strip()
                
                # Split prefix words and take last 1-2 words as customer name
                words = [w for w in prefix.split() if len(w) > 1]
                if words:
                    subject_name = words[-1]
                    return (subject_name, rel_type)
        return None

    def resolve_customer(
        self,
        shop_uid: str,
        query_text: str,
        language: str = "hi-IN"
    ) -> CustomerResolutionResponse:
        """
        Deterministic 7-step customer resolution pipeline.
        """
        clean_shop = shop_uid.replace("shop:", "").strip()
        raw_query = query_text.strip()
        norm_query = raw_query.lower()

        # Step 1: Check for Exact Customer UID
        if norm_query.startswith("cust_") or norm_query.startswith("customer:cust_"):
            clean_cust = norm_query.replace("customer:", "")
            res = self.db.query(
                f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND customer_uid = '{clean_cust}' LIMIT 1;"
            )
            if res and isinstance(res, list) and len(res) > 0:
                row = res[0]
                return CustomerResolutionResponse(
                    status="resolved",
                    resolved_customer_uid=row["customer_uid"],
                    display_name=row["display_name"],
                    resolution_method="exact_uuid",
                    confidence=1.0,
                    candidates=[CustomerCandidate(
                        customer_uid=row["customer_uid"],
                        display_name=row["display_name"],
                        confidence=1.0,
                        match_source="exact_uuid"
                    )]
                )

        # Step 2: Check for Family Relationship Utterance (e.g. 'Ravi ke papa')
        family_ref = self.extract_family_reference(raw_query)
        if family_ref:
            subject_name, rel_type = family_ref
            logger.info(f"Detected family reference: subject='{subject_name}', type='{rel_type}' in shop='{clean_shop}'")
            
            # Find subject customer
            subject_cust = self._find_customer_by_name_or_alias(clean_shop, subject_name)
            if not subject_cust:
                return CustomerResolutionResponse(
                    status="clarification_needed",
                    confidence=0.0,
                    resolution_method="family_traversal",
                    clarification_question=f"Customer '{subject_name}' was not found in your khata directory. Would you like to create an account for {subject_name} first?"
                )

            subj_uid = subject_cust["customer_uid"]
            subj_name = subject_cust["display_name"]

            # Traverse confirmed graph relationships for this subject customer
            relationships = self.graph.get_customer_family_graph(
                clean_shop, subj_uid, confirmation_filter="confirmed"
            )

            # Filter for the requested relationship type
            matching_relatives = []
            for edge in relationships:
                # Direct match: e.g. edge says Arun (subj) -> FATHER -> Ravi
                if edge["relationship_type"] == rel_type:
                    rel_cust_uid = edge["related_customer_uid"]
                    target = self._get_customer_by_uid(clean_shop, rel_cust_uid)
                    if target:
                        matching_relatives.append(target)
                # Symmetrical/Inverse match: if edge says Ravi -> SON -> Arun, and query asks for Arun's son
                elif rel_type == "SON" and edge["relationship_type"] == "FATHER" and edge["direction"] == "incoming":
                    rel_cust_uid = edge["subject_customer_uid"]
                    target = self._get_customer_by_uid(clean_shop, rel_cust_uid)
                    if target:
                        matching_relatives.append(target)

            if len(matching_relatives) == 1:
                target = matching_relatives[0]
                return CustomerResolutionResponse(
                    status="resolved",
                    resolved_customer_uid=target["customer_uid"],
                    display_name=target["display_name"],
                    resolution_method="family_traversal",
                    confidence=0.95,
                    candidates=[CustomerCandidate(
                        customer_uid=target["customer_uid"],
                        display_name=target["display_name"],
                        confidence=0.95,
                        match_source="family_traversal",
                        relationship_detail=f"{rel_type} of {subj_name}"
                    )]
                )
            elif len(matching_relatives) > 1:
                candidates = [
                    CustomerCandidate(
                        customer_uid=t["customer_uid"],
                        display_name=t["display_name"],
                        confidence=0.7,
                        match_source="family_traversal",
                        relationship_detail=f"{rel_type} of {subj_name}"
                    )
                    for t in matching_relatives
                ]
                return CustomerResolutionResponse(
                    status="ambiguous",
                    confidence=0.6,
                    resolution_method="family_traversal",
                    candidates=candidates,
                    clarification_question=f"Multiple confirmed {rel_type.lower()} records found for {subj_name}. Please select which customer made this transaction."
                )
            else:
                # Check if an unconfirmed suggestion exists
                unconfirmed_edges = self.graph.get_customer_family_graph(
                    clean_shop, subj_uid, confirmation_filter="unconfirmed"
                )
                matching_unconfirmed = [e for e in unconfirmed_edges if e["relationship_type"] == rel_type]
                
                if matching_unconfirmed:
                    unc = matching_unconfirmed[0]
                    target = self._get_customer_by_uid(clean_shop, unc["related_customer_uid"])
                    unc_name = target["display_name"] if target else "Unknown"
                    return CustomerResolutionResponse(
                        status="clarification_needed",
                        confidence=0.4,
                        resolution_method="family_traversal",
                        clarification_question=f"A suggested {rel_type.lower()} relationship exists between {subj_name} and {unc_name}, but it is unconfirmed. Please confirm this relationship in Settings before recording udhaar."
                    )
                else:
                    return CustomerResolutionResponse(
                        status="clarification_needed",
                        confidence=0.2,
                        resolution_method="family_traversal",
                        clarification_question=f"No confirmed {rel_type.lower()} relationship is registered for {subj_name}. Would you like to link a customer record for {subj_name}'s {rel_type.lower()}?"
                    )

        # Step 3: Exact Normalized Customer Name Lookup
        clean_name = re.sub(r"[^\w\s]", "", norm_query).strip()
        exact_res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND normalized_name = '{clean_name}' LIMIT 1;"
        )
        if exact_res and isinstance(exact_res, list) and len(exact_res) > 0:
            row = exact_res[0]
            return CustomerResolutionResponse(
                status="resolved",
                resolved_customer_uid=row["customer_uid"],
                display_name=row["display_name"],
                resolution_method="exact_name",
                confidence=1.0,
                candidates=[CustomerCandidate(
                    customer_uid=row["customer_uid"],
                    display_name=row["display_name"],
                    confidence=1.0,
                    match_source="exact_name"
                )]
            )

        # Step 4: Confirmed Alias Lookup
        alias_res = self.db.query(
            f"SELECT * FROM customer_alias WHERE shop_uid = '{clean_shop}' AND normalized_alias = '{clean_name}' LIMIT 1;"
        )
        if alias_res and isinstance(alias_res, list) and len(alias_res) > 0:
            alias_row = alias_res[0]
            cust = self._get_customer_by_uid(clean_shop, alias_row["customer_uid"])
            if cust:
                return CustomerResolutionResponse(
                    status="resolved",
                    resolved_customer_uid=cust["customer_uid"],
                    display_name=cust["display_name"],
                    resolution_method="alias_match",
                    confidence=0.95,
                    candidates=[CustomerCandidate(
                        customer_uid=cust["customer_uid"],
                        display_name=cust["display_name"],
                        confidence=0.95,
                        match_source="alias_match"
                    )]
                )

        # Step 5: Partial / Multi-Candidate Search
        like_res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND normalized_name CONTAINS '{clean_name}' LIMIT 5;"
        )
        if like_res and isinstance(like_res, list) and len(like_res) > 0:
            if len(like_res) == 1:
                row = like_res[0]
                return CustomerResolutionResponse(
                    status="resolved",
                    resolved_customer_uid=row["customer_uid"],
                    display_name=row["display_name"],
                    resolution_method="fuzzy_name",
                    confidence=0.85,
                    candidates=[CustomerCandidate(
                        customer_uid=row["customer_uid"],
                        display_name=row["display_name"],
                        confidence=0.85,
                        match_source="fuzzy_name"
                    )]
                )
            else:
                candidates = [
                    CustomerCandidate(
                        customer_uid=r["customer_uid"],
                        display_name=r["display_name"],
                        confidence=0.7,
                        match_source="fuzzy_name"
                    )
                    for r in like_res
                ]
                return CustomerResolutionResponse(
                    status="ambiguous",
                    confidence=0.6,
                    resolution_method="fuzzy_name",
                    candidates=candidates,
                    clarification_question=f"Found {len(candidates)} customers matching '{clean_name}'. Please select the intended customer."
                )

        return CustomerResolutionResponse(
            status="not_found",
            confidence=0.0,
            resolution_method="none",
            clarification_question=f"No customer found matching '{raw_query}'. Would you like to add a new customer?"
        )

    def _find_customer_by_name_or_alias(self, shop_uid: str, name: str) -> Optional[Dict[str, Any]]:
        clean_name = name.strip().lower()
        res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{shop_uid}' AND (normalized_name = '{clean_name}' OR normalized_name CONTAINS '{clean_name}') LIMIT 1;"
        )
        if res and isinstance(res, list) and len(res) > 0:
            return res[0]
        
        # Check alias
        a_res = self.db.query(
            f"SELECT customer_uid FROM customer_alias WHERE shop_uid = '{shop_uid}' AND (normalized_alias = '{clean_name}' OR normalized_alias CONTAINS '{clean_name}') LIMIT 1;"
        )
        if a_res and isinstance(a_res, list) and len(a_res) > 0:
            return self._get_customer_by_uid(shop_uid, a_res[0]["customer_uid"])
        return None

    def _get_customer_by_uid(self, shop_uid: str, customer_uid: str) -> Optional[Dict[str, Any]]:
        clean_cust = customer_uid.replace("customer:", "").strip()
        res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{shop_uid}' AND customer_uid = '{clean_cust}' LIMIT 1;"
        )
        if res and isinstance(res, list) and len(res) > 0:
            return res[0]
        return None

# Global singleton
resolution_service = RelationshipResolutionService()
