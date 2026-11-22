-- KHATAWAS Cloud SurrealDB Migration: 002_integrity_constraints.surql
-- Version: 2
-- Description: Primary identification constraints and unique indexes

-- Unique Schema Version
DEFINE INDEX IF NOT EXISTS idx_schema_version ON TABLE schema_version COLUMNS version UNIQUE;

-- Unique User Identifier
DEFINE INDEX IF NOT EXISTS idx_user_external_uid ON TABLE app_user COLUMNS external_uid UNIQUE;

-- Unique Shop Membership (Enforce 1 membership per user per shop)
DEFINE INDEX IF NOT EXISTS idx_shop_member_unique ON TABLE shop_member COLUMNS shop_uid, user_uid UNIQUE;

-- Unique Customer within a Shop
DEFINE INDEX IF NOT EXISTS idx_customer_uid_unique ON TABLE customer COLUMNS shop_uid, customer_uid UNIQUE;

-- Unique Normalized Customer Alias within a Shop
DEFINE INDEX IF NOT EXISTS idx_alias_normalized_unique ON TABLE customer_alias COLUMNS shop_uid, normalized_alias UNIQUE;

-- Unique Device within a Shop
DEFINE INDEX IF NOT EXISTS idx_device_unique ON TABLE device_registry COLUMNS shop_uid, device_uid UNIQUE;

-- Unique Ledger Event across the entire ledger
DEFINE INDEX IF NOT EXISTS idx_ledger_event_uid_unique ON TABLE ledger_event COLUMNS event_uid UNIQUE;

-- Unique Device Sequence per Shop and Device
DEFINE INDEX IF NOT EXISTS idx_ledger_device_seq_unique ON TABLE ledger_event COLUMNS shop_uid, originating_device_uid, device_sequence UNIQUE;

-- Unique Chain Head per Shop and Device
DEFINE INDEX IF NOT EXISTS idx_chain_head_unique ON TABLE event_chain_head COLUMNS shop_uid, device_uid UNIQUE;

-- Unique Device Sync State per Shop and Device
DEFINE INDEX IF NOT EXISTS idx_device_sync_unique ON TABLE device_sync_state COLUMNS shop_uid, device_uid UNIQUE;

-- Unique Sync Receipt per Shop and Event
DEFINE INDEX IF NOT EXISTS idx_sync_receipt_unique ON TABLE sync_receipt COLUMNS shop_uid, event_uid UNIQUE;

-- Unique Conversation UID
DEFINE INDEX IF NOT EXISTS idx_conversation_uid_unique ON TABLE conversation COLUMNS conversation_uid UNIQUE;

-- Unique Chat Message UID
DEFINE INDEX IF NOT EXISTS idx_chat_message_uid_unique ON TABLE chat_message COLUMNS message_uid UNIQUE;

-- Unique Audit Event UID
DEFINE INDEX IF NOT EXISTS idx_audit_uid_unique ON TABLE audit_event COLUMNS audit_uid UNIQUE;

-- Unique Conflict Record UID
DEFINE INDEX IF NOT EXISTS idx_conflict_uid_unique ON TABLE conflict_record COLUMNS conflict_uid UNIQUE;
