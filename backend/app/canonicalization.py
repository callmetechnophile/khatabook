"""
KHATAWAS Backend - Deterministic Canonicalization & SHA-256 Event Integrity
Ensures bit-exact cryptographic hashing and append-only hash-chain verification
compatible with Android EventIntegrity.kt and SurrealDB Cloud.
"""

import hashlib
import json
from typing import Dict, Any, Tuple

GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

def canonical_serialize_v1(
    event_id: str,
    customer_name: str,
    amount_paise: int,
    event_type: str,
    timestamp: int,
    note: str = "",
    previous_event_hash: str = GENESIS_HASH,
    schema_version: int = 1
) -> str:
    """
    Bit-exact match with Android Kotlin EventIntegrity.canonicalSerialize (Version 1).
    Format: {"amount_paise":<int>,"customer_name":"<str>","event_id":"<str>","note":"<str>","previous_event_hash":"<str>","schema_version":<int>,"timestamp":<int>,"type":"<str>"}
    """
    clean_prev = previous_event_hash.strip() if previous_event_hash and previous_event_hash.strip() else GENESIS_HASH
    clean_type = event_type.strip().upper()
    
    canonical_dict = {
        "amount_paise": int(amount_paise),
        "customer_name": customer_name.strip(),
        "event_id": event_id.strip(),
        "note": (note or "").strip(),
        "previous_event_hash": clean_prev,
        "schema_version": int(schema_version),
        "timestamp": int(timestamp),
        "type": clean_type
    }
    return json.dumps(canonical_dict, separators=(',', ':'), sort_keys=True, ensure_ascii=False)

def canonical_serialize_v2(event_data: Dict[str, Any]) -> str:
    """
    Full enterprise canonicalization (Version 2) for multi-device tenant events.
    Includes device sequence, signed balance effect, shop_uid, customer_uid, etc.
    """
    prev = str(event_data.get("previous_digest") or event_data.get("previous_event_hash") or GENESIS_HASH).strip()
    
    canonical_dict = {
        "amount_paise": int(event_data["amount_paise"]),
        "balance_effect_paise": int(event_data["balance_effect_paise"]),
        "canonicalization_version": int(event_data.get("canonicalization_version", 2)),
        "category": str(event_data.get("category", "CREDIT")).strip().upper(),
        "currency": str(event_data.get("currency", "INR")).strip().upper(),
        "customer_uid": str(event_data["customer_uid"]).strip(),
        "device_sequence": int(event_data["device_sequence"]),
        "event_type": str(event_data.get("event_type", "TRANSACTION")).strip().upper(),
        "event_uid": str(event_data["event_uid"]).strip(),
        "occurred_at": str(event_data["occurred_at"]).strip(),
        "originating_device_uid": str(event_data["originating_device_uid"]).strip(),
        "previous_digest": prev,
        "schema_version": int(event_data.get("schema_version", 1)),
        "shop_uid": str(event_data["shop_uid"]).strip()
    }
    if event_data.get("corrects_event_uid"):
        canonical_dict["corrects_event_uid"] = str(event_data["corrects_event_uid"]).strip()
    if event_data.get("reverses_event_uid"):
        canonical_dict["reverses_event_uid"] = str(event_data["reverses_event_uid"]).strip()

    return json.dumps(canonical_dict, separators=(',', ':'), sort_keys=True, ensure_ascii=False)

def compute_sha256(text: str) -> str:
    """Compute standard hexadecimal SHA-256 digest of UTF-8 encoded text."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

def compute_canonical_hash(event_input: Any) -> str:
    """
    Computes canonical SHA-256 digest whether input is already a string or a dictionary.
    """
    if isinstance(event_input, dict):
        canon_json = canonical_serialize_v1(
            event_id=event_input.get("event_uid") or event_input.get("event_id", ""),
            customer_name=event_input.get("customer_name") or event_input.get("customer_uid", ""),
            amount_paise=int(event_input.get("amount_paise", 0)),
            event_type=event_input.get("type") or event_input.get("category") or event_input.get("event_type", "CREDIT"),
            timestamp=int(event_input.get("timestamp", 0)),
            note=event_input.get("note") or event_input.get("description", ""),
            previous_event_hash=event_input.get("previous_event_hash") or event_input.get("previous_digest", GENESIS_HASH),
            schema_version=int(event_input.get("schema_version", 1))
        )
        return compute_sha256(canon_json)
    elif isinstance(event_input, str):
        return compute_sha256(event_input)
    else:
        raise TypeError(f"Unsupported event input type: {type(event_input)}")

def compute_chained_digest(previous_digest: str, canonical_json: str) -> str:
    """
    Formula: H(n) = SHA256(H(n-1) || CanonicalJSON(Event(n)))
    """
    payload = f"{previous_digest.strip()}{canonical_json}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()

def verify_event_integrity(event_dict: Dict[str, Any]) -> Tuple[bool, str, str]:
    """
    Verifies that the provided digest matches the independently computed digest.
    Supports both V1 and V2 canonical formats.
    Returns: (is_valid, computed_digest, canonical_json)
    """
    version = int(event_dict.get("canonicalization_version", 1))
    provided_digest = (
        event_dict.get("event_digest") or 
        event_dict.get("event_hash") or 
        ""
    ).strip()

    if version == 2 and "originating_device_uid" in event_dict:
        canonical_json = canonical_serialize_v2(event_dict)
        prev_digest = str(event_dict.get("previous_digest", GENESIS_HASH)).strip()
        computed_digest = compute_chained_digest(prev_digest, canonical_json)
    else:
        # Fallback to V1 canonical serialization for Android backward compatibility
        event_id = event_dict.get("event_uid") or event_dict.get("event_id", "")
        cust_name = event_dict.get("customer_name") or event_dict.get("customer_uid", "")
        amount = int(event_dict.get("amount_paise", 0))
        tx_type = event_dict.get("type") or event_dict.get("category") or event_dict.get("event_type", "CREDIT")
        timestamp = int(event_dict.get("timestamp") or 0)
        note = event_dict.get("note") or event_dict.get("description", "")
        prev = event_dict.get("previous_event_hash") or event_dict.get("previous_digest", GENESIS_HASH)
        schema_ver = int(event_dict.get("schema_version", 1))

        canonical_json = canonical_serialize_v1(
            event_id=event_id,
            customer_name=cust_name,
            amount_paise=amount,
            event_type=tx_type,
            timestamp=timestamp,
            note=note,
            previous_event_hash=prev,
            schema_version=schema_ver
        )
        computed_digest = compute_sha256(canonical_json)

    is_valid = provided_digest.lower() == computed_digest.lower() if provided_digest else False
    return is_valid, computed_digest, canonical_json

