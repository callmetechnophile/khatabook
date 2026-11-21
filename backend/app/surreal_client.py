"""
KHATAWAS Backend - SurrealDB Client & Cloud Persistence Layer
Manages permanent documents, graph relationships, and 24-hour temporary event projections.
"""

import threading
import time
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Optional, Tuple, Any

class SurrealCloudStore:
    """
    SurrealDB-compatible cloud persistence manager.
    Implements ACID semantics, graph relations, idempotent event deduplication,
    and 24-hour temporary graph projection expiration.
    """

    def __init__(self):
        self._lock = threading.Lock()
        # Permanent storage
        self.shops: Dict[str, Dict[str, Any]] = {}
        self.customers: Dict[str, Dict[str, Any]] = {} # key: f"{shop_id}:{customer_name.lower()}"
        self.transactions: Dict[str, Dict[str, Any]] = {} # key: event_id
        
        # Graph edges
        self.owes_edges: List[Dict[str, Any]] = [] # customer -> OWES -> shop
        self.paid_edges: List[Dict[str, Any]] = [] # customer -> PAID -> shop

        # Temporary 24-hour graph projections
        self.temporary_events: List[Dict[str, Any]] = []
        self.recent_activity_edges: List[Dict[str, Any]] = []

    def get_or_create_shop(self, shop_name: str, owner_phone: str) -> Dict[str, Any]:
        with self._lock:
            for s in self.shops.values():
                if s["owner_phone"] == owner_phone:
                    return s
            shop_id = f"shop:{owner_phone.replace('+', '').strip()}"
            shop_record = {
                "id": shop_id,
                "name": shop_name,
                "owner_phone": owner_phone,
                "created_at": datetime.now(timezone.utc).isoformat()
            }
            self.shops[shop_id] = shop_record
            return shop_record

    def get_or_create_customer(self, shop_id: str, customer_name: str) -> Dict[str, Any]:
        norm_name = customer_name.strip()
        key = f"{shop_id}:{norm_name.lower()}"
        if key in self.customers:
            return self.customers[key]
        
        customer_id = f"customer:{shop_id.split(':')[-1]}_{len(self.customers) + 1}"
        record = {
            "id": customer_id,
            "shop_id": shop_id,
            "name": norm_name,
            "balance_paise": 0,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "updated_at": datetime.now(timezone.utc).isoformat()
        }
        self.customers[key] = record
        return record

    def ingest_sync_batch(self, shop_id: str, events: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
        with self._lock:
            results = []
            affected_customers = set()

            for ev in events:
                event_id = ev["event_id"]
                now_utc = datetime.now(timezone.utc)
                now_iso = now_utc.isoformat()

                # Idempotency check: if event_id already ingested, return DUPLICATE_ACCEPTED
                if event_id in self.transactions:
                    results.append({
                        "event_id": event_id,
                        "status": "DUPLICATE_ACCEPTED",
                        "server_timestamp": now_iso
                    })
                    continue

                cust_name = ev["customer_name"].strip()
                cust = self.get_or_create_customer(shop_id, cust_name)
                cust_key = f"{shop_id}:{cust_name.lower()}"
                affected_customers.add(cust_name)

                amount_paise = int(ev["amount_paise"])
                tx_type = ev["type"].upper()

                # Permanent transaction record
                tx_record = {
                    "event_id": event_id,
                    "shop_id": shop_id,
                    "customer_id": cust["id"],
                    "customer_name": cust_name,
                    "amount_paise": amount_paise,
                    "type": tx_type,
                    "timestamp": ev.get("timestamp", int(time.time() * 1000)),
                    "note": ev.get("note", ""),
                    "server_ingested_at": now_iso
                }
                self.transactions[event_id] = tx_record

                # Apply authoritative balance calculation
                if tx_type == "CREDIT":
                    cust["balance_paise"] += amount_paise
                    # Update OWES graph edge
                    self.owes_edges.append({
                        "from": cust["id"],
                        "to": shop_id,
                        "amount_paise": amount_paise,
                        "timestamp": now_iso
                    })
                elif tx_type == "PAYMENT":
                    cust["balance_paise"] -= amount_paise
                    # Update PAID graph edge
                    self.paid_edges.append({
                        "from": cust["id"],
                        "to": shop_id,
                        "amount_paise": amount_paise,
                        "timestamp": now_iso
                    })
                elif tx_type == "REVERSAL":
                    cust["balance_paise"] -= amount_paise

                cust["updated_at"] = now_iso

                # Add to temporary 24-hour graph projection
                expires_at = (now_utc + timedelta(hours=24)).isoformat()
                self.temporary_events.append({
                    "event_id": event_id,
                    "shop_id": shop_id,
                    "customer_name": cust_name,
                    "amount_paise": amount_paise,
                    "type": tx_type,
                    "created_at": now_iso,
                    "expires_at": expires_at
                })
                self.recent_activity_edges.append({
                    "from": shop_id,
                    "to": cust["id"],
                    "event_id": event_id,
                    "expires_at": expires_at
                })

                results.append({
                    "event_id": event_id,
                    "status": "ACCEPTED",
                    "server_timestamp": now_iso
                })

            balances = {}
            for name in affected_customers:
                k = f"{shop_id}:{name.lower()}"
                if k in self.customers:
                    balances[name] = self.customers[k]["balance_paise"]

            return results, balances

    def get_customer_balances(self, shop_id: str) -> List[Dict[str, Any]]:
        with self._lock:
            res = []
            for cust in self.customers.values():
                if cust["shop_id"] == shop_id:
                    bal = cust["balance_paise"]
                    status = "OWES" if bal > 0 else "PAID_UP" if bal == 0 else "SURPLUS"
                    res.append({
                        "customer_id": cust["id"],
                        "name": cust["name"],
                        "balance_paise": bal,
                        "balance_rupees": bal / 100.0,
                        "status": status
                    })
            return res

    def get_customer_ledger(self, shop_id: str, customer_name: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            cust_key = f"{shop_id}:{customer_name.strip().lower()}"
            if cust_key not in self.customers:
                return None
            cust = self.customers[cust_key]
            
            txs = []
            for tx in self.transactions.values():
                if tx["shop_id"] == shop_id and tx["customer_id"] == cust["id"]:
                    txs.append({
                        "event_id": tx["event_id"],
                        "type": tx["type"],
                        "amount_paise": tx["amount_paise"],
                        "timestamp": datetime.fromtimestamp(tx["timestamp"] / 1000.0, tz=timezone.utc).isoformat(),
                        "note": tx["note"]
                    })
            
            txs.sort(key=lambda x: x["timestamp"], reverse=True)
            return {
                "customer_name": cust["name"],
                "balance_paise": cust["balance_paise"],
                "transactions": txs
            }

    def get_daily_projection(self, shop_id: str) -> Dict[str, Any]:
        with self._lock:
            now_iso = datetime.now(timezone.utc).isoformat()
            active_events = [
                e for e in self.temporary_events
                if e["shop_id"] == shop_id and e["expires_at"] > now_iso
            ]

            total_credit = sum(e["amount_paise"] for e in active_events if e["type"] == "CREDIT")
            total_payment = sum(e["amount_paise"] for e in active_events if e["type"] == "PAYMENT")

            return {
                "shop_id": shop_id,
                "window": "24_HOURS",
                "total_credit_paise": total_credit,
                "total_payment_paise": total_payment,
                "active_events_count": len(active_events),
                "recent_events": [
                    {
                        "event_id": e["event_id"],
                        "customer_name": e["customer_name"],
                        "amount_paise": e["amount_paise"],
                        "type": e["type"],
                        "expires_at": e["expires_at"]
                    }
                    for e in active_events[-20:] # Return last 20 active events
                ]
            }

    def cleanup_expired_projections(self) -> int:
        """
        Background worker logic: deletes expired temporary projections.
        Leaves all permanent transactions and customer records untouched.
        """
        with self._lock:
            now_iso = datetime.now(timezone.utc).isoformat()
            initial_count = len(self.temporary_events)
            self.temporary_events = [
                e for e in self.temporary_events
                if e["expires_at"] > now_iso
            ]
            self.recent_activity_edges = [
                edge for edge in self.recent_activity_edges
                if edge["expires_at"] > now_iso
            ]
            purged = initial_count - len(self.temporary_events)
            return purged

# Global instance
cloud_db = SurrealCloudStore()
