-- KHATAWAS Cloud SurrealDB Migration: 003_query_indexes.surql
-- Version: 3
-- Description: Composite query performance indexes for ledger filtering and chat retrieval

-- Ledger Event lookup by Shop and Date Range
DEFINE INDEX IF NOT EXISTS idx_ledger_shop_occurred ON TABLE ledger_event COLUMNS shop_uid, occurred_at;

-- Ledger Event lookup by Customer, Shop, and Date Range
DEFINE INDEX IF NOT EXISTS idx_ledger_shop_customer ON TABLE ledger_event COLUMNS shop_uid, customer_uid, occurred_at;

-- Customer lookup by Shop and Normalized Name
DEFINE INDEX IF NOT EXISTS idx_customer_shop_name ON TABLE customer COLUMNS shop_uid, normalized_name;

-- Customer Aliases lookup by Shop and Customer UID
DEFINE INDEX IF NOT EXISTS idx_alias_customer_lookup ON TABLE customer_alias COLUMNS shop_uid, customer_uid;

-- Chat message chronological order within a conversation
DEFINE INDEX IF NOT EXISTS idx_chat_conversation_time ON TABLE chat_message COLUMNS conversation_uid, created_at;

-- Audit event lookup by Shop and Timestamp
DEFINE INDEX IF NOT EXISTS idx_audit_shop_time ON TABLE audit_event COLUMNS shop_uid, created_at;
