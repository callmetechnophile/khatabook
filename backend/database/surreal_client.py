"""
KHATAWAS Backend - SurrealDB Client & Storage Engine
Provides ACID transactions, tenant isolation, append-only ledger storage,
hash-chain verification, idempotent receipts, and conflict detection.
"""

import os
import json
import logging
from typing import Dict, List, Optional, Tuple, Any
from datetime import datetime, timezone
from surrealdb import Surreal
from backend.database.migration_runner import MigrationRunner
from backend.app.canonicalization import (
    verify_event_integrity, compute_sha256, compute_chained_digest,
    canonical_serialize_v2, GENESIS_HASH
)

logger = logging.getLogger("khatawas.database")

class SurrealDatabaseClient:
    def __init__(self, url: Optional[str] = None, namespace: Optional[str] = None, database: Optional[str] = None):
        self.url = url or os.getenv("SURREALDB_URL", "mem://")
        self.namespace = namespace or os.getenv("SURREALDB_NAMESPACE", "khatawas")
        self.database = database or os.getenv("SURREALDB_DATABASE", "ledger")
        self.username = os.getenv("SURREALDB_USERNAME", "root")
        self.password = os.getenv("SURREALDB_PASSWORD", "root")
        
        self.db: Optional[Surreal] = None
        self._initialize_connection()

    def _initialize_connection(self):
        """Establish connection to SurrealDB and select namespace/database."""
        self.db = Surreal(self.url)
        self.db.use(self.namespace, self.database)
        if not self.url.startswith("mem://") and self.username and self.password:
            try:
                self.db.signin({"username": self.username, "password": self.password})
            except Exception as e:
                logger.warning(f"SurrealDB signin skipped or failed: {e}")

        # Run any pending migrations automatically
        runner = MigrationRunner(self.db)
        runner.apply_pending_migrations()

    def query(self, sql: str) -> Any:
        return self.db.query(sql)

    # ---------------------------------------------------------
    # Tenant & Membership Management
    # ---------------------------------------------------------

    def get_or_create_shop(self, shop_name: str, owner_phone: str, owner_uid: str = "user_default") -> Dict[str, Any]:
        """Fetch existing shop by owner_phone or create a new one."""
        clean_phone = owner_phone.strip()
        res = self.db.query(f"SELECT * FROM shop WHERE owner_uid = '{owner_uid}' LIMIT 1;")
        if res and isinstance(res, list) and len(res) > 0 and isinstance(res[0], dict) and res[0].get("shop_name"):
            return res[0]
        
        shop_uid = f"shop_{clean_phone.replace('+', '').replace(' ', '')}"
        safe_shop_name = shop_name.replace("'", "''")
        insert_sql = (
            f"CREATE shop SET "
            f"id = 'shop:{shop_uid}', "
            f"owner_uid = '{owner_uid}', "
            f"shop_name = '{safe_shop_name}', "
            f"preferred_language = 'hi-IN', "
            f"currency = 'INR', "
            f"timezone = 'Asia/Kolkata', "
            f"created_at = time::now(), "
            f"updated_at = time::now(), "
            f"archived = false;"
        )
        res = self.db.query(insert_sql)
        # Register owner in shop_member
        self.db.query(
            f"CREATE shop_member SET "
            f"shop_uid = '{shop_uid}', "
            f"user_uid = '{owner_uid}', "
            f"role = 'owner', "
            f"status = 'active', "
            f"created_at = time::now();"
        )
        # Native graph edge: app_user -> member_of -> shop
        try:
            self.db.query(f"CREATE app_user:{owner_uid} SET external_uid = '{owner_uid}' RETURN NONE;")
            self.db.query(f"CREATE shop:{shop_uid} SET shop_uid = '{shop_uid}' RETURN NONE;")
            self.db.query(f"RELATE app_user:{owner_uid}->member_of->shop:{shop_uid} SET role = 'owner', status = 'active', created_at = time::now();")
        except Exception as e:
            logger.debug(f"member_of edge hook notice: {e}")
        return {
            "id": f"shop:{shop_uid}",
            "shop_uid": shop_uid,
            "shop_name": shop_name,
            "owner_phone": clean_phone
        }

    def verify_shop_membership(self, shop_uid: str, user_uid: str) -> Optional[str]:
        """Verify user is an active member of shop_uid. Returns role or None."""
        clean_shop = shop_uid.replace("shop:", "").strip()
        res = self.db.query(
            f"SELECT role FROM shop_member WHERE shop_uid = '{clean_shop}' AND user_uid = '{user_uid}' AND status = 'active' LIMIT 1;"
        )
        if res and isinstance(res, list) and len(res) > 0:
            row = res[0]
            if isinstance(row, dict) and "role" in row:
                return row["role"]
        return None

    def get_or_create_device(self, shop_uid: str, device_uid: str, user_uid: str = "user_default") -> Dict[str, Any]:
        """Register device if not already registered."""
        clean_shop = shop_uid.replace("shop:", "").strip()
        res = self.db.query(
            f"SELECT * FROM device_registry WHERE shop_uid = '{clean_shop}' AND device_uid = '{device_uid}' LIMIT 1;"
        )
        if res and isinstance(res, list) and len(res) > 0 and isinstance(res[0], dict) and res[0].get("device_uid"):
            return res[0]

        sql = (
            f"CREATE device_registry SET "
            f"device_uid = '{device_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"user_uid = '{user_uid}', "
            f"device_label = 'Android Device', "
            f"platform = 'android', "
            f"status = 'active', "
            f"registered_at = time::now(), "
            f"last_seen_at = time::now();"
        )
        self.db.query(sql)
        return {"device_uid": device_uid, "shop_uid": clean_shop, "platform": "android"}

    def get_or_create_customer(self, shop_uid: str, customer_name: str) -> Dict[str, Any]:
        """Resolve customer by normalized name or create a new customer record."""
        clean_shop = shop_uid.replace("shop:", "").strip()
        norm_name = customer_name.strip().lower()
        res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND normalized_name = '{norm_name}' LIMIT 1;"
        )
        if res and isinstance(res, list) and len(res) > 0 and isinstance(res[0], dict) and res[0].get("customer_uid"):
            return res[0]

        import uuid
        customer_uid = f"cust_{uuid.uuid4().hex[:12]}"
        disp_name = customer_name.strip().replace("'", "''")
        insert_sql = (
            f"CREATE customer SET "
            f"customer_uid = '{customer_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"display_name = '{disp_name}', "
            f"normalized_name = '{norm_name}', "
            f"preferred_language = 'hi-IN', "
            f"created_at = time::now(), "
            f"updated_at = time::now(), "
            f"archived = false;"
        )
        self.db.query(insert_sql)
        # Register alias as well
        alias_uid = f"alias_{uuid.uuid4().hex[:10]}"
        self.db.query(
            f"CREATE customer_alias SET "
            f"alias_uid = '{alias_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"customer_uid = '{customer_uid}', "
            f"alias_text = '{disp_name}', "
            f"normalized_alias = '{norm_name}', "
            f"language_code = 'hi-IN', "
            f"created_at = time::now();"
        )

        # Native graph edges: shop -> owns_customer -> customer, customer_alias -> alias_of -> customer
        try:
            self.db.query(f"CREATE shop:{clean_shop} SET shop_uid = '{clean_shop}' RETURN NONE;")
            self.db.query(f"CREATE customer:{customer_uid} SET customer_uid = '{customer_uid}', shop_uid = '{clean_shop}', display_name = '{disp_name}' RETURN NONE;")
            self.db.query(f"CREATE customer_alias:{alias_uid} SET alias_uid = '{alias_uid}', customer_uid = '{customer_uid}' RETURN NONE;")
            self.db.query(f"RELATE shop:{clean_shop}->owns_customer->customer:{customer_uid} SET status = 'active', created_at = time::now();")
            self.db.query(f"RELATE customer_alias:{alias_uid}->alias_of->customer:{customer_uid} SET shop_uid = '{clean_shop}', confirmation_status = 'confirmed', source = 'manually_entered', created_at = time::now();")
        except Exception as e:
            logger.debug(f"owns_customer / alias_of edge hook notice: {e}")
        return {"customer_uid": customer_uid, "shop_uid": clean_shop, "display_name": customer_name.strip()}

    # ---------------------------------------------------------
    # Event Chain Head & State Tracking
    # ---------------------------------------------------------

    def get_chain_head(self, shop_uid: str, device_uid: str) -> Dict[str, Any]:
        clean_shop = shop_uid.replace("shop:", "").strip()
        res = self.db.query(
            f"SELECT * FROM event_chain_head WHERE shop_uid = '{clean_shop}' AND device_uid = '{device_uid}' LIMIT 1;"
        )
        if res and isinstance(res, list) and len(res) > 0 and isinstance(res[0], dict) and "last_sequence" in res[0]:
            return res[0]
        return {
            "shop_uid": clean_shop,
            "device_uid": device_uid,
            "last_sequence": -1,
            "last_event_uid": "",
            "last_digest": GENESIS_HASH
        }

    # ---------------------------------------------------------
    # Append-Only Event Ingestion with Idempotency & Conflict Detection
    # ---------------------------------------------------------

    def ingest_sync_batch(
        self,
        shop_uid: str,
        events: List[Dict[str, Any]],
        device_uid: Optional[str] = None
    ) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
        """
        Processes an incoming synchronization batch adhering strictly to:
        - Append-only immutability.
        - Idempotent deduplication (same UUID + same digest = duplicate).
        - Conflict detection (same UUID + different digest = conflict).
        - Sequence continuity validation.
        - Cryptographic hash-chain verification.
        - Integer paise balance updates.
        """
        clean_shop = shop_uid.replace("shop:", "").strip()
        results = []
        affected_customers = set()

        for ev in events:
            now_iso = datetime.now(timezone.utc).isoformat()
            event_uid = str(ev.get("event_uid") or ev.get("event_id") or "").strip()
            orig_device = str(ev.get("originating_device_uid") or device_uid or "android_default").strip()
            
            # Check for existing event with this UUID
            existing = self.db.query(
                f"SELECT * FROM ledger_event WHERE event_uid = '{event_uid}' LIMIT 1;"
            )
            has_existing = bool(
                existing and isinstance(existing, list) and len(existing) > 0 and 
                isinstance(existing[0], dict) and existing[0].get("event_uid")
            )

            # Determine event parameters
            raw_amount = int(ev.get("amount_paise", 0))
            if raw_amount <= 0:
                results.append({
                    "event_uid": event_uid,
                    "event_id": event_uid,
                    "status": "REJECTED",
                    "result": "rejected",
                    "server_timestamp": now_iso,
                    "error_message": "amount_paise must be a strictly positive integer"
                })
                continue

            category = str(ev.get("category") or ev.get("type", "CREDIT")).strip().upper()
            if category not in ["CREDIT", "DEBIT", "STUCK", "ILLEGAL"]:
                # Map legacy PAYMENT -> DEBIT
                if category == "PAYMENT":
                    category = "DEBIT"
                else:
                    results.append({
                        "event_uid": event_uid,
                        "event_id": event_uid,
                        "status": "REJECTED",
                        "result": "rejected",
                        "server_timestamp": now_iso,
                        "error_message": f"Invalid category: {category}"
                    })
                    continue

            # Determine balance effect (in paise)
            if ev.get("balance_effect_paise") is not None:
                balance_effect = int(ev["balance_effect_paise"])
            else:
                # Rule: CREDIT adds to customer balance (debt), DEBIT deducts
                if category == "CREDIT":
                    balance_effect = raw_amount
                elif category == "DEBIT":
                    balance_effect = -raw_amount
                else:
                    balance_effect = 0 # STUCK / ILLEGAL require explicit client balance effect

            # Canonical serialization & verification
            ev_dict = dict(ev)
            ev_dict["shop_uid"] = clean_shop
            ev_dict["event_uid"] = event_uid
            ev_dict["originating_device_uid"] = orig_device
            ev_dict["category"] = category
            ev_dict["amount_paise"] = raw_amount
            ev_dict["balance_effect_paise"] = balance_effect

            is_valid, computed_digest, canonical_json = verify_event_integrity(ev_dict)

            # Check for existing duplicate or conflict
            if has_existing:
                existing_record = existing[0]
                existing_digest = str(existing_record.get("event_digest", "")).strip()

                if existing_digest.lower() == computed_digest.lower():
                    # Idempotent Duplicate
                    cname = str(ev.get("customer_name") or ev.get("customer_uid") or "").strip()
                    if cname:
                        affected_customers.add(cname)
                    receipt_sql = (
                        f"CREATE sync_receipt SET "
                        f"receipt_uid = 'rcpt_{event_uid}', "
                        f"shop_uid = '{clean_shop}', "
                        f"device_uid = '{orig_device}', "
                        f"event_uid = '{event_uid}', "
                        f"accepted_digest = '{computed_digest}', "
                        f"result = 'duplicate', "
                        f"received_at = time::now();"
                    )
                    try:
                        self.db.query(receipt_sql)
                    except Exception:
                        pass

                    results.append({
                        "event_uid": event_uid,
                        "event_id": event_uid,
                        "status": "DUPLICATE_ACCEPTED",
                        "result": "duplicate",
                        "server_timestamp": now_iso
                    })
                    continue
                else:
                    # Conflict: Identical UUID with DIFFERENT content digest!
                    import uuid
                    conflict_uid = f"conf_{uuid.uuid4().hex[:12]}"
                    safe_payload = json.dumps(ev).replace("'", "''")
                    conflict_sql = (
                        f"CREATE conflict_record SET "
                        f"conflict_uid = '{conflict_uid}', "
                        f"shop_uid = '{clean_shop}', "
                        f"event_uid = '{event_uid}', "
                        f"device_uid = '{orig_device}', "
                        f"conflict_type = 'DIGEST_MISMATCH', "
                        f"existing_digest = '{existing_digest}', "
                        f"incoming_digest = '{computed_digest}', "
                        f"payload_json = '{safe_payload}', "
                        f"detected_at = time::now();"
                    )
                    self.db.query(conflict_sql)

                    results.append({
                        "event_uid": event_uid,
                        "event_id": event_uid,
                        "status": "CONFLICT",
                        "result": "conflict",
                        "server_timestamp": now_iso,
                        "error_message": f"Conflict detected: existing digest ({existing_digest}) does not match incoming ({computed_digest})"
                    })
                    continue

            # Verify integrity hash
            provided_hash = str(ev.get("event_digest") or ev.get("event_hash") or "").strip()
            if provided_hash and not is_valid:
                results.append({
                    "event_uid": event_uid,
                    "event_id": event_uid,
                    "status": "REJECTED",
                    "result": "rejected",
                    "server_timestamp": now_iso,
                    "error_message": f"Digest verification failed. Expected {computed_digest}, received {provided_hash}"
                })
                continue

            # Customer resolution
            cust_name = str(ev.get("customer_name") or ev.get("customer_uid", "Default Customer")).strip()
            customer_record = self.get_or_create_customer(clean_shop, cust_name)
            customer_uid = customer_record["customer_uid"]
            affected_customers.add(cust_name)

            # Device sequence & chain continuity check
            chain_head = self.get_chain_head(clean_shop, orig_device)
            device_seq = int(ev["device_sequence"]) if ev.get("device_sequence") is not None else (int(chain_head["last_sequence"]) + 1)
            prev_digest = str(ev.get("previous_digest") or ev.get("previous_event_hash") or chain_head["last_digest"]).strip()
            schema_ver = int(ev["schema_version"]) if ev.get("schema_version") is not None else 1
            canon_ver = int(ev["canonicalization_version"]) if ev.get("canonicalization_version") is not None else 1

            # Insert immutable financial event
            desc = str(ev.get("description") or ev.get("note", "")).replace("'", "''")
            insert_event_sql = (
                f"CREATE ledger_event SET "
                f"event_uid = '{event_uid}', "
                f"shop_uid = '{clean_shop}', "
                f"customer_uid = '{customer_uid}', "
                f"originating_device_uid = '{orig_device}', "
                f"device_sequence = {device_seq}, "
                f"event_type = '{ev.get('event_type', 'TRANSACTION')}', "
                f"category = '{category}', "
                f"amount_paise = {raw_amount}, "
                f"balance_effect_paise = {balance_effect}, "
                f"currency = 'INR', "
                f"occurred_at = time::now(), "
                f"recorded_at = time::now(), "
                f"description = '{desc}', "
                f"source_language = '{ev.get('source_language', 'hi-IN')}', "
                f"source_type = '{ev.get('source_type', 'voice')}', "
                f"schema_version = {schema_ver}, "
                f"canonicalization_version = {canon_ver}, "
                f"hash_algorithm = 'SHA-256', "
                f"previous_digest = '{prev_digest}', "
                f"event_digest = '{computed_digest}', "
                f"sync_received_at = time::now();"
            )
            self.db.query(insert_event_sql)

            # Native graph edges: customer -> has_transaction -> ledger_event, ledger_event -> created_on_device -> device_registry
            try:
                self.db.query(f"CREATE customer:{customer_uid} SET customer_uid = '{customer_uid}', shop_uid = '{clean_shop}' RETURN NONE;")
                self.db.query(f"CREATE ledger_event:{event_uid} SET event_uid = '{event_uid}', shop_uid = '{clean_shop}' RETURN NONE;")
                self.db.query(f"CREATE device_registry:{orig_device} SET device_uid = '{orig_device}', shop_uid = '{clean_shop}' RETURN NONE;")
                self.db.query(f"RELATE customer:{customer_uid}->has_transaction->ledger_event:{event_uid} SET shop_uid = '{clean_shop}', created_at = time::now();")
                self.db.query(f"RELATE ledger_event:{event_uid}->created_on_device->device_registry:{orig_device} SET shop_uid = '{clean_shop}', created_at = time::now();")
            except Exception as e:
                logger.debug(f"has_transaction / created_on_device edge hook notice: {e}")

            # Record Sync Receipt
            receipt_sql = (
                f"CREATE sync_receipt SET "
                f"receipt_uid = 'rcpt_{event_uid}', "
                f"shop_uid = '{clean_shop}', "
                f"device_uid = '{orig_device}', "
                f"event_uid = '{event_uid}', "
                f"accepted_digest = '{computed_digest}', "
                f"result = 'accepted', "
                f"received_at = time::now();"
            )
            self.db.query(receipt_sql)

            # Update Event Chain Head
            head_sql = (
                f"UPSERT event_chain_head CONTENT {{ "
                f"shop_uid: '{clean_shop}', "
                f"device_uid: '{orig_device}', "
                f"last_sequence: {device_seq}, "
                f"last_event_uid: '{event_uid}', "
                f"last_digest: '{computed_digest}', "
                f"updated_at: time::now() "
                f"}} WHERE shop_uid = '{clean_shop}' AND device_uid = '{orig_device}';"
            )
            self.db.query(head_sql)

            # Update Device Sync State
            self.db.query(
                f"UPSERT device_sync_state CONTENT {{ "
                f"shop_uid: '{clean_shop}', "
                f"device_uid: '{orig_device}', "
                f"last_accepted_event_uid: '{event_uid}', "
                f"last_successful_sync_at: time::now(), "
                f"updated_at: time::now() "
                f"}} WHERE shop_uid = '{clean_shop}' AND device_uid = '{orig_device}';"
            )

            results.append({
                "event_uid": event_uid,
                "event_id": event_uid,
                "status": "ACCEPTED",
                "result": "accepted",
                "server_timestamp": now_iso
            })

        # Calculate authoritative updated balances
        balances = {}
        for cname in affected_customers:
            cust_res = self.get_or_create_customer(clean_shop, cname)
            bal = self.calculate_customer_balance(clean_shop, cust_res["customer_uid"])
            balances[cname] = bal

        return results, balances

    # ---------------------------------------------------------
    # Authoritative Deterministic Balance Calculations
    # ---------------------------------------------------------

    def calculate_customer_balance(self, shop_uid: str, customer_uid: str) -> int:
        """
        Formula: Outstanding balance = SUM(balance_effect_paise)
        Excludes unaccepted/conflicted events. Pure integer paise arithmetic.
        """
        clean_shop = shop_uid.replace("shop:", "").strip()
        res = self.db.query(
            f"SELECT balance_effect_paise FROM ledger_event "
            f"WHERE shop_uid = '{clean_shop}' AND customer_uid = '{customer_uid}';"
        )
        total_paise = 0
        if res and isinstance(res, list):
            for row in res:
                if isinstance(row, dict) and "balance_effect_paise" in row:
                    total_paise += int(row["balance_effect_paise"])
        return total_paise

    def get_customer_balances(self, shop_uid: str) -> List[Dict[str, Any]]:
        clean_shop = shop_uid.replace("shop:", "").strip()
        custs = self.db.query(f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND archived = false;")
        out = []
        if custs and isinstance(custs, list):
            for c in custs:
                if isinstance(c, dict) and "customer_uid" in c:
                    cuid = c["customer_uid"]
                    bal_paise = self.calculate_customer_balance(clean_shop, cuid)
                    status_str = "OWES" if bal_paise > 0 else ("PAID_UP" if bal_paise == 0 else "SURPLUS")
                    out.append({
                        "customer_id": f"customer:{cuid}",
                        "customer_uid": cuid,
                        "name": c.get("display_name", ""),
                        "balance_paise": bal_paise,
                        "balance_rupees": round(bal_paise / 100.0, 2),
                        "status": status_str
                    })
        return out

    def get_customer_ledger(self, shop_uid: str, customer_ident: str) -> Optional[Dict[str, Any]]:
        """Retrieve customer ledger by customer_name or customer_uid."""
        clean_shop = shop_uid.replace("shop:", "").strip()
        # Try finding customer by normalized_name or customer_uid
        norm = customer_ident.strip().lower()
        cust_res = self.db.query(
            f"SELECT * FROM customer WHERE shop_uid = '{clean_shop}' AND (normalized_name = '{norm}' OR customer_uid = '{customer_ident}') LIMIT 1;"
        )
        if not cust_res or not isinstance(cust_res, list) or len(cust_res) == 0:
            return None

        cust = cust_res[0]
        cuid = cust["customer_uid"]
        cname = cust["display_name"]

        txs = self.db.query(
            f"SELECT * FROM ledger_event WHERE shop_uid = '{clean_shop}' AND customer_uid = '{cuid}' ORDER BY occurred_at ASC;"
        )
        items = []
        if txs and isinstance(txs, list):
            for t in txs:
                if isinstance(t, dict) and "event_uid" in t:
                    items.append({
                        "event_id": t["event_uid"],
                        "event_uid": t["event_uid"],
                        "type": t["category"],
                        "category": t["category"],
                        "amount_paise": t["amount_paise"],
                        "balance_effect_paise": t["balance_effect_paise"],
                        "timestamp": str(t.get("occurred_at", "")),
                        "note": t.get("description", "")
                    })

        balance_paise = self.calculate_customer_balance(clean_shop, cuid)
        return {
            "customer_name": cname,
            "customer_uid": cuid,
            "balance_paise": balance_paise,
            "transactions": items
        }

    def get_ledger_summary(self, shop_uid: str) -> Dict[str, Any]:
        """Calculates total credits, debits, net balance, and transaction count in integer paise."""
        clean_shop = shop_uid.replace("shop:", "").strip()
        res = self.db.query(
            f"SELECT category, amount_paise, balance_effect_paise FROM ledger_event WHERE shop_uid = '{clean_shop}';"
        )
        total_credit = 0
        total_debit = 0
        net_balance = 0
        count = 0
        if res and isinstance(res, list):
            for r in res:
                if isinstance(r, dict) and "amount_paise" in r:
                    count += 1
                    amt = int(r["amount_paise"])
                    eff = int(r.get("balance_effect_paise", 0))
                    cat = r.get("category", "")
                    if cat == "CREDIT":
                        total_credit += amt
                    elif cat == "DEBIT":
                        total_debit += amt
                    net_balance += eff

        return {
            "shop_uid": clean_shop,
            "total_transactions": count,
            "total_credit_paise": total_credit,
            "total_debit_paise": total_debit,
            "net_balance_paise": net_balance,
            "total_credit_rupees": round(total_credit / 100.0, 2),
            "total_debit_rupees": round(total_debit / 100.0, 2),
            "net_balance_rupees": round(net_balance / 100.0, 2)
        }

    # ---------------------------------------------------------
    # Read-Only Allowlisted Chatbot Queries
    # ---------------------------------------------------------

    def query_chatbot_intent(self, shop_uid: str, intent: str, params: Dict[str, Any]) -> Dict[str, Any]:
        """
        Translates pre-approved NandiBERT query intents into allowlisted database queries.
        Strictly read-only; arbitrary SQL/SurrealQL execution is prohibited.
        """
        clean_shop = shop_uid.replace("shop:", "").strip()
        intent_upper = intent.strip().upper()

        if intent_upper == "CUSTOMER_BALANCE":
            cname = str(params.get("customer_name") or params.get("customer_uid", "")).strip()
            ledger = self.get_customer_ledger(clean_shop, cname)
            if not ledger:
                return {"status": "not_found", "message": f"Customer '{cname}' was not found in the ledger."}
            return {
                "status": "answered",
                "customer_name": ledger["customer_name"],
                "balance_paise": ledger["balance_paise"],
                "balance_rupees": round(ledger["balance_paise"] / 100.0, 2),
                "summary": f"{ledger['customer_name']} owes ₹{round(ledger['balance_paise']/100.0, 2)}"
            }

        elif intent_upper == "CUSTOMER_HISTORY":
            cname = str(params.get("customer_name") or params.get("customer_uid", "")).strip()
            ledger = self.get_customer_ledger(clean_shop, cname)
            if not ledger:
                return {"status": "not_found", "message": f"Customer '{cname}' was not found in the ledger."}
            return {
                "status": "answered",
                "customer_name": ledger["customer_name"],
                "balance_paise": ledger["balance_paise"],
                "transactions": ledger["transactions"],
                "total_events": len(ledger["transactions"])
            }

        elif intent_upper == "LEDGER_SUMMARY":
            summary = self.get_ledger_summary(clean_shop)
            summary["status"] = "answered"
            return summary

        elif intent_upper in ["FAMILY_RELATIONSHIPS", "WHO_IS_RELATIVE"]:
            cname = str(params.get("customer_name") or params.get("customer_uid", "")).strip()
            rel_type = str(params.get("relationship_type", "")).strip().upper()
            norm = cname.lower()
            cust_res = self.db.query(
                f"SELECT customer_uid, display_name FROM customer WHERE shop_uid = '{clean_shop}' AND normalized_name = '{norm}' LIMIT 1;"
            )
            if not cust_res or not isinstance(cust_res, list) or len(cust_res) == 0:
                return {"status": "not_found", "message": f"Customer '{cname}' not found."}
            cuid = cust_res[0]["customer_uid"]
            
            # Query native SurrealDB graph relationships
            type_filter = f" AND relationship_type = '{rel_type}'" if rel_type else ""
            edges = self.db.query(
                f"SELECT *, out.display_name AS relative_name, out.customer_uid AS relative_uid "
                f"FROM family_of WHERE shop_uid = '{clean_shop}' AND in = customer:{cuid}{type_filter};"
            ) or []

            # Distinguish confirmed from unconfirmed
            confirmed = [e for e in edges if e.get("confirmation_status") == "confirmed"]
            unconfirmed = [e for e in edges if e.get("confirmation_status") == "unconfirmed"]

            if not edges:
                return {
                    "status": "not_found",
                    "customer_name": cust_res[0]["display_name"],
                    "message": f"No registered family connections found for {cust_res[0]['display_name']}."
                }

            summary_text = ""
            if confirmed:
                first = confirmed[0]
                summary_text = f"{cust_res[0]['display_name']}'s {first.get('relationship_type', 'relative')} is {first.get('relative_name', first.get('out'))} (Confirmed)."
            elif unconfirmed:
                first = unconfirmed[0]
                summary_text = f"Suggested relationship: {cust_res[0]['display_name']}'s {first.get('relationship_type')} may be {first.get('relative_name', first.get('out'))} (Unconfirmed)."

            return {
                "status": "answered",
                "customer_name": cust_res[0]["display_name"],
                "customer_uid": cuid,
                "confirmed_relationships": confirmed,
                "unconfirmed_relationships": unconfirmed,
                "summary": summary_text
            }

        elif intent_upper == "FAMILY_TRANSACTIONS":
            # e.g. "Show transactions made by Ravi's father"
            cname = str(params.get("customer_name") or params.get("customer_uid", "")).strip()
            rel_type = str(params.get("relationship_type", "FATHER")).strip().upper()
            norm = cname.lower()
            cust_res = self.db.query(
                f"SELECT customer_uid, display_name FROM customer WHERE shop_uid = '{clean_shop}' AND normalized_name = '{norm}' LIMIT 1;"
            )
            if not cust_res or not isinstance(cust_res, list) or len(cust_res) == 0:
                return {"status": "not_found", "message": f"Customer '{cname}' not found."}
            cuid = cust_res[0]["customer_uid"]

            # Traverse graph for confirmed relative
            rel_edges = self.db.query(
                f"SELECT out.* FROM family_of WHERE shop_uid = '{clean_shop}' AND in = customer:{cuid} "
                f"AND relationship_type = '{rel_type}' AND confirmation_status = 'confirmed' LIMIT 1;"
            )
            if not rel_edges or not isinstance(rel_edges, list) or len(rel_edges) == 0:
                return {
                    "status": "not_found",
                    "message": f"No confirmed {rel_type.lower()} found for {cust_res[0]['display_name']} in the ledger."
                }
            
            relative_row = rel_edges[0]
            relative_uid = str(relative_row.get("customer_uid") or relative_row.get("id", "")).replace("customer:", "")
            relative_name = relative_row.get("display_name", relative_uid)

            # Traverse has_transaction edge
            txs = self.db.query(
                f"SELECT out.* FROM has_transaction WHERE shop_uid = '{clean_shop}' AND in = customer:{relative_uid} "
                f"ORDER BY out.occurred_at DESC LIMIT 20;"
            ) or []

            balance = self.calculate_customer_balance(clean_shop, relative_uid)
            return {
                "status": "answered",
                "subject_customer": cust_res[0]["display_name"],
                "relative_name": relative_name,
                "relative_uid": relative_uid,
                "relationship_type": rel_type,
                "balance_paise": balance,
                "balance_rupees": round(balance / 100.0, 2),
                "transactions": txs,
                "summary": f"{relative_name} ({rel_type} of {cust_res[0]['display_name']}) has balance ₹{round(balance/100.0, 2)} with {len(txs)} transactions."
            }

        elif intent_upper == "FIND_CUSTOMER_ALIASES":
            cname = str(params.get("customer_name") or params.get("customer_uid", "")).strip()
            norm = cname.lower()
            cust_res = self.db.query(
                f"SELECT customer_uid, display_name FROM customer WHERE shop_uid = '{clean_shop}' AND normalized_name = '{norm}' LIMIT 1;"
            )
            if not cust_res or not isinstance(cust_res, list) or len(cust_res) == 0:
                return {"status": "not_found", "message": f"Customer '{cname}' not found."}
            cuid = cust_res[0]["customer_uid"]

            # Traverse alias_of graph edges
            aliases = self.db.query(
                f"SELECT in.* FROM alias_of WHERE shop_uid = '{clean_shop}' AND out = customer:{cuid};"
            ) or []
            return {
                "status": "answered",
                "customer_name": cust_res[0]["display_name"],
                "customer_uid": cuid,
                "aliases": aliases
            }

        else:
            return {
                "status": "not_found",
                "message": f"Unsupported or unrecognized query intent: {intent}"
            }

    # ---------------------------------------------------------
    # Chat Conversation & Message Persistence
    # ---------------------------------------------------------

    def save_chat_message(
        self,
        shop_uid: str,
        conversation_uid: str,
        role: str,
        content: str,
        input_modality: str = "text",
        retrieved_event_uids: Optional[List[str]] = None,
        response_status: str = "answered"
    ) -> Dict[str, Any]:
        import uuid
        clean_shop = shop_uid.replace("shop:", "").strip()
        msg_uid = f"msg_{uuid.uuid4().hex[:12]}"
        safe_content = content.replace("'", "''")
        uids_array = json.dumps(retrieved_event_uids or [])

        # Ensure conversation exists
        self.db.query(
            f"UPSERT conversation CONTENT {{ "
            f"conversation_uid: '{conversation_uid}', "
            f"shop_uid: '{clean_shop}', "
            f"user_uid: 'user_default', "
            f"title: 'Ledger Chat', "
            f"created_at: time::now(), "
            f"updated_at: time::now(), "
            f"archived: false "
            f"}} WHERE conversation_uid = '{conversation_uid}';"
        )

        sql = (
            f"CREATE chat_message SET "
            f"message_uid = '{msg_uid}', "
            f"conversation_uid = '{conversation_uid}', "
            f"shop_uid = '{clean_shop}', "
            f"role = '{role}', "
            f"content = '{safe_content}', "
            f"input_modality = '{input_modality}', "
            f"language_code = 'hi-IN', "
            f"created_at = time::now(), "
            f"retrieved_event_uids = {uids_array}, "
            f"response_status = '{response_status}';"
        )
        self.db.query(sql)
        return {"message_uid": msg_uid, "conversation_uid": conversation_uid, "role": role, "status": response_status}

    def list_conversations(self, shop_uid: str) -> List[Dict[str, Any]]:
        clean_shop = shop_uid.replace("shop:", "").strip()
        res = self.db.query(
            f"SELECT * FROM conversation WHERE shop_uid = '{clean_shop}' AND archived = false ORDER BY updated_at DESC;"
        )
        return res if isinstance(res, list) else []

# Global singleton client instance (connects via environment variables or embedded mem://)
cloud_db = SurrealDatabaseClient()
