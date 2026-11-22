"""
KHATAWAS Backend - SurrealDB Graph Reconciliation Service (V1)
Durable reconciliation service that audits and repairs structural consistency
between relational tables and native graph relation edges without data loss.
"""

import logging
from typing import Dict, List, Any
from datetime import datetime, timezone

from backend.database.surreal_client import cloud_db
from backend.services.graph_service import graph_service
from backend.models.graph_models import ReconciliationReport

logger = logging.getLogger("khatawas.reconciliation")

class GraphReconciliationService:
    def __init__(self, db_client=None, graph_svc=None):
        self.db = db_client or cloud_db
        self.graph = graph_svc or graph_service

    def audit_graph_integrity(
        self,
        shop_uid: str,
        apply_repairs: bool = False
    ) -> ReconciliationReport:
        clean_shop = shop_uid.replace("shop:", "").strip()
        inconsistencies = []
        repairs_applied = 0

        # 1. Total Customers & Missing `owns_customer` Edges
        customers = self.db.query(f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}';") or []
        total_customers = len(customers)
        missing_owns_customer = 0

        for cust in customers:
            cust_uid = cust.get("customer_uid")
            edge = self.db.query(
                f"SELECT * FROM owns_customer WHERE in = shop:{clean_shop} AND out = customer:{cust_uid} LIMIT 1;"
            )
            if not edge or not isinstance(edge, list) or len(edge) == 0:
                missing_owns_customer += 1
                inconsistencies.append({
                    "type": "MISSING_OWNS_CUSTOMER_EDGE",
                    "customer_uid": cust_uid,
                    "description": f"Customer '{cust_uid}' has no owns_customer edge from shop:{clean_shop}"
                })
                if apply_repairs:
                    self.graph.link_customer_ownership(clean_shop, cust_uid)
                    repairs_applied += 1

        # 2. Total Transactions & Missing `has_transaction` Edges
        events = self.db.query(f"SELECT * FROM ledger_event WHERE shop_uid = '{clean_shop}';") or []
        total_events = len(events)
        missing_tx_edges = 0
        missing_device_edges = 0

        for ev in events:
            ev_uid = ev.get("event_uid")
            cust_uid = ev.get("customer_uid")
            dev_uid = ev.get("originating_device_uid")

            # Check has_transaction edge
            tx_edge = self.db.query(
                f"SELECT * FROM has_transaction WHERE in = customer:{cust_uid} AND out = ledger_event:{ev_uid} LIMIT 1;"
            )
            if not tx_edge or not isinstance(tx_edge, list) or len(tx_edge) == 0:
                missing_tx_edges += 1
                inconsistencies.append({
                    "type": "MISSING_HAS_TRANSACTION_EDGE",
                    "event_uid": ev_uid,
                    "customer_uid": cust_uid,
                    "description": f"Ledger event '{ev_uid}' missing has_transaction edge from customer:{cust_uid}"
                })
                if apply_repairs:
                    self.graph.link_customer_transaction(clean_shop, cust_uid, ev_uid)
                    repairs_applied += 1

            # Check created_on_device edge
            if dev_uid:
                dev_edge = self.db.query(
                    f"SELECT * FROM created_on_device WHERE in = ledger_event:{ev_uid} AND out = device_registry:{dev_uid} LIMIT 1;"
                )
                if not dev_edge or not isinstance(dev_edge, list) or len(dev_edge) == 0:
                    missing_device_edges += 1
                    inconsistencies.append({
                        "type": "MISSING_CREATED_ON_DEVICE_EDGE",
                        "event_uid": ev_uid,
                        "device_uid": dev_uid,
                        "description": f"Ledger event '{ev_uid}' missing created_on_device edge to device_registry:{dev_uid}"
                    })
                    if apply_repairs:
                        self.graph.link_event_device_provenance(clean_shop, ev_uid, dev_uid)
                        repairs_applied += 1

        # 3. Detect Prohibited Self-Loops in `family_of`
        self_loops = 0
        edges = self.db.query(f"SELECT * FROM family_of WHERE shop_uid = '{clean_shop}';") or []
        for edge in edges:
            in_node = str(edge.get("in", ""))
            out_node = str(edge.get("out", ""))
            if in_node and out_node and in_node == out_node:
                self_loops += 1
                inconsistencies.append({
                    "type": "PROHIBITED_SELF_LOOP",
                    "relationship_uid": edge.get("relationship_uid"),
                    "node": in_node,
                    "description": f"Customer '{in_node}' has a self-relationship edge in family_of"
                })
                if apply_repairs:
                    rel_uid = edge.get("relationship_uid")
                    self.db.query(f"UPDATE family_of SET confirmation_status = 'rejected' WHERE relationship_uid = '{rel_uid}';")
                    repairs_applied += 1

        # 4. Detect Cross-Shop Edge Leaks
        cross_shop = 0
        all_family = self.db.query(f"SELECT * FROM family_of WHERE shop_uid = '{clean_shop}';") or []
        for edge in all_family:
            in_cust = str(edge.get("in", "")).replace("customer:", "")
            out_cust = str(edge.get("out", "")).replace("customer:", "")
            
            # Check if either endpoint belongs to a different shop
            leak = self.db.query(
                f"SELECT customer_uid, shop_uid FROM customer WHERE customer_uid IN ['{in_cust}', '{out_cust}'] AND shop_uid != '{clean_shop}';"
            )
            if leak and isinstance(leak, list) and len(leak) > 0:
                cross_shop += 1
                inconsistencies.append({
                    "type": "CROSS_SHOP_EDGE_LEAK",
                    "relationship_uid": edge.get("relationship_uid"),
                    "details": leak,
                    "description": f"Relationship '{edge.get('relationship_uid')}' connects endpoints outside tenant shop:{clean_shop}"
                })
                if apply_repairs:
                    rel_uid = edge.get("relationship_uid")
                    self.db.query(f"DELETE FROM family_of WHERE relationship_uid = '{rel_uid}';")
                    repairs_applied += 1

        # 5. Detect Duplicate Active Relationships
        duplicates = 0
        seen_pairs = set()
        for edge in all_family:
            if edge.get("confirmation_status") != "rejected":
                pair_key = (str(edge.get("in")), str(edge.get("out")), edge.get("relationship_type"))
                if pair_key in seen_pairs:
                    duplicates += 1
                    inconsistencies.append({
                        "type": "DUPLICATE_ACTIVE_RELATIONSHIP",
                        "relationship_uid": edge.get("relationship_uid"),
                        "pair": pair_key,
                        "description": f"Duplicate active relationship of type '{edge.get('relationship_type')}' for {pair_key[0]} -> {pair_key[1]}"
                    })
                else:
                    seen_pairs.add(pair_key)

        return ReconciliationReport(
            shop_uid=clean_shop,
            total_customers=total_customers,
            total_transactions=total_events,
            missing_owns_customer_edges=missing_owns_customer,
            missing_transaction_edges=missing_tx_edges,
            missing_device_provenance_edges=missing_device_edges,
            cross_shop_edges_detected=cross_shop,
            self_loop_edges_detected=self_loops,
            duplicate_active_relationships=duplicates,
            inconsistencies=inconsistencies,
            repairs_applied=repairs_applied
        )

# Global singleton
reconciliation_service = GraphReconciliationService()
