-- KHATAWAS Cloud SurrealDB Migration: 001_initial_schema.surql
-- Version: 1
-- Description: Initial SCHEMAFULL schema definition for KHATAWAS financial ledger

-- 0. Schema Version Tracking
DEFINE TABLE IF NOT EXISTS schema_version SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS version ON TABLE schema_version TYPE int;
DEFINE FIELD IF NOT EXISTS description ON TABLE schema_version TYPE string;
DEFINE FIELD IF NOT EXISTS applied_at ON TABLE schema_version TYPE datetime DEFAULT time::now();

-- 1. App User
DEFINE TABLE IF NOT EXISTS app_user SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS external_uid ON TABLE app_user TYPE string;
DEFINE FIELD IF NOT EXISTS display_name ON TABLE app_user TYPE string;
DEFINE FIELD IF NOT EXISTS phone_e164 ON TABLE app_user TYPE string;
DEFINE FIELD IF NOT EXISTS account_status ON TABLE app_user TYPE string DEFAULT 'active';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE app_user TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE app_user TYPE datetime DEFAULT time::now();

-- 2. Shop (Tenant)
DEFINE TABLE IF NOT EXISTS shop SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS owner_uid ON TABLE shop TYPE string;
DEFINE FIELD IF NOT EXISTS shop_name ON TABLE shop TYPE string;
DEFINE FIELD IF NOT EXISTS preferred_language ON TABLE shop TYPE string DEFAULT 'hi-IN';
DEFINE FIELD IF NOT EXISTS currency ON TABLE shop TYPE string DEFAULT 'INR' ASSERT $value = 'INR';
DEFINE FIELD IF NOT EXISTS timezone ON TABLE shop TYPE string DEFAULT 'Asia/Kolkata';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE shop TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE shop TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS archived ON TABLE shop TYPE bool DEFAULT false;

-- 3. Shop Member (Role-based access)
DEFINE TABLE IF NOT EXISTS shop_member SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE shop_member TYPE string;
DEFINE FIELD IF NOT EXISTS user_uid ON TABLE shop_member TYPE string;
DEFINE FIELD IF NOT EXISTS role ON TABLE shop_member TYPE string ASSERT $value IN ['owner', 'admin', 'operator', 'viewer'];
DEFINE FIELD IF NOT EXISTS status ON TABLE shop_member TYPE string DEFAULT 'active';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE shop_member TYPE datetime DEFAULT time::now();

-- 4. Customer
DEFINE TABLE IF NOT EXISTS customer SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS customer_uid ON TABLE customer TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE customer TYPE string;
DEFINE FIELD IF NOT EXISTS display_name ON TABLE customer TYPE string;
DEFINE FIELD IF NOT EXISTS normalized_name ON TABLE customer TYPE string;
DEFINE FIELD IF NOT EXISTS phone_e164 ON TABLE customer TYPE option<string>;
DEFINE FIELD IF NOT EXISTS notes ON TABLE customer TYPE option<string>;
DEFINE FIELD IF NOT EXISTS preferred_language ON TABLE customer TYPE string DEFAULT 'hi-IN';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE customer TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE customer TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS archived ON TABLE customer TYPE bool DEFAULT false;

-- 5. Customer Alias (Phonetic & spoken aliases for voice)
DEFINE TABLE IF NOT EXISTS customer_alias SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS alias_uid ON TABLE customer_alias TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE customer_alias TYPE string;
DEFINE FIELD IF NOT EXISTS customer_uid ON TABLE customer_alias TYPE string;
DEFINE FIELD IF NOT EXISTS alias_text ON TABLE customer_alias TYPE string;
DEFINE FIELD IF NOT EXISTS normalized_alias ON TABLE customer_alias TYPE string;
DEFINE FIELD IF NOT EXISTS language_code ON TABLE customer_alias TYPE string DEFAULT 'hi-IN';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE customer_alias TYPE datetime DEFAULT time::now();

-- 6. Customer Relationship
DEFINE TABLE IF NOT EXISTS customer_relationship SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS relationship_uid ON TABLE customer_relationship TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE customer_relationship TYPE string;
DEFINE FIELD IF NOT EXISTS subject_customer_uid ON TABLE customer_relationship TYPE string;
DEFINE FIELD IF NOT EXISTS related_customer_uid ON TABLE customer_relationship TYPE string;
DEFINE FIELD IF NOT EXISTS relationship_type ON TABLE customer_relationship TYPE string;
DEFINE FIELD IF NOT EXISTS confirmation_status ON TABLE customer_relationship TYPE string ASSERT $value IN ['confirmed', 'unconfirmed', 'rejected'];
DEFINE FIELD IF NOT EXISTS created_at ON TABLE customer_relationship TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS supersedes_uid ON TABLE customer_relationship TYPE option<string>;

-- 7. Device Registry
DEFINE TABLE IF NOT EXISTS device_registry SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS device_uid ON TABLE device_registry TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE device_registry TYPE string;
DEFINE FIELD IF NOT EXISTS user_uid ON TABLE device_registry TYPE string;
DEFINE FIELD IF NOT EXISTS device_label ON TABLE device_registry TYPE string;
DEFINE FIELD IF NOT EXISTS platform ON TABLE device_registry TYPE string DEFAULT 'android' ASSERT $value = 'android';
DEFINE FIELD IF NOT EXISTS status ON TABLE device_registry TYPE string DEFAULT 'active';
DEFINE FIELD IF NOT EXISTS registered_at ON TABLE device_registry TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS last_seen_at ON TABLE device_registry TYPE datetime DEFAULT time::now();

-- 8. Immutable Append-Only Ledger Event
DEFINE TABLE IF NOT EXISTS ledger_event SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS event_uid ON TABLE ledger_event TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE ledger_event TYPE string;
DEFINE FIELD IF NOT EXISTS customer_uid ON TABLE ledger_event TYPE string;
DEFINE FIELD IF NOT EXISTS originating_device_uid ON TABLE ledger_event TYPE string;
DEFINE FIELD IF NOT EXISTS device_sequence ON TABLE ledger_event TYPE int ASSERT $value >= 0;
DEFINE FIELD IF NOT EXISTS event_type ON TABLE ledger_event TYPE string ASSERT $value IN ['TRANSACTION', 'REVERSAL', 'CORRECTION'];
DEFINE FIELD IF NOT EXISTS category ON TABLE ledger_event TYPE string ASSERT $value IN ['CREDIT', 'DEBIT', 'STUCK', 'ILLEGAL'];
DEFINE FIELD IF NOT EXISTS amount_paise ON TABLE ledger_event TYPE int ASSERT $value > 0;
DEFINE FIELD IF NOT EXISTS balance_effect_paise ON TABLE ledger_event TYPE int;
DEFINE FIELD IF NOT EXISTS currency ON TABLE ledger_event TYPE string DEFAULT 'INR' ASSERT $value = 'INR';
DEFINE FIELD IF NOT EXISTS occurred_at ON TABLE ledger_event TYPE datetime;
DEFINE FIELD IF NOT EXISTS recorded_at ON TABLE ledger_event TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS description ON TABLE ledger_event TYPE option<string>;
DEFINE FIELD IF NOT EXISTS source_language ON TABLE ledger_event TYPE string DEFAULT 'hi-IN';
DEFINE FIELD IF NOT EXISTS source_type ON TABLE ledger_event TYPE string DEFAULT 'voice' ASSERT $value IN ['voice', 'manual', 'correction'];
DEFINE FIELD IF NOT EXISTS reverses_event_uid ON TABLE ledger_event TYPE option<string>;
DEFINE FIELD IF NOT EXISTS corrects_event_uid ON TABLE ledger_event TYPE option<string>;
DEFINE FIELD IF NOT EXISTS schema_version ON TABLE ledger_event TYPE int DEFAULT 1;
DEFINE FIELD IF NOT EXISTS canonicalization_version ON TABLE ledger_event TYPE int DEFAULT 1;
DEFINE FIELD IF NOT EXISTS hash_algorithm ON TABLE ledger_event TYPE string DEFAULT 'SHA-256';
DEFINE FIELD IF NOT EXISTS previous_digest ON TABLE ledger_event TYPE string;
DEFINE FIELD IF NOT EXISTS event_digest ON TABLE ledger_event TYPE string;
DEFINE FIELD IF NOT EXISTS sync_received_at ON TABLE ledger_event TYPE datetime DEFAULT time::now();

-- 9. Event Chain Head (Tracks integrity state per device)
DEFINE TABLE IF NOT EXISTS event_chain_head SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE event_chain_head TYPE string;
DEFINE FIELD IF NOT EXISTS device_uid ON TABLE event_chain_head TYPE string;
DEFINE FIELD IF NOT EXISTS last_sequence ON TABLE event_chain_head TYPE int;
DEFINE FIELD IF NOT EXISTS last_event_uid ON TABLE event_chain_head TYPE string;
DEFINE FIELD IF NOT EXISTS last_digest ON TABLE event_chain_head TYPE string;
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE event_chain_head TYPE datetime DEFAULT time::now();

-- 10. Device Sync State
DEFINE TABLE IF NOT EXISTS device_sync_state SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE device_sync_state TYPE string;
DEFINE FIELD IF NOT EXISTS device_uid ON TABLE device_sync_state TYPE string;
DEFINE FIELD IF NOT EXISTS last_accepted_event_uid ON TABLE device_sync_state TYPE string;
DEFINE FIELD IF NOT EXISTS last_successful_sync_at ON TABLE device_sync_state TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE device_sync_state TYPE datetime DEFAULT time::now();

-- 11. Sync Receipt
DEFINE TABLE IF NOT EXISTS sync_receipt SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS receipt_uid ON TABLE sync_receipt TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE sync_receipt TYPE string;
DEFINE FIELD IF NOT EXISTS device_uid ON TABLE sync_receipt TYPE string;
DEFINE FIELD IF NOT EXISTS event_uid ON TABLE sync_receipt TYPE string;
DEFINE FIELD IF NOT EXISTS accepted_digest ON TABLE sync_receipt TYPE string;
DEFINE FIELD IF NOT EXISTS result ON TABLE sync_receipt TYPE string ASSERT $value IN ['accepted', 'duplicate', 'rejected', 'conflict'];
DEFINE FIELD IF NOT EXISTS received_at ON TABLE sync_receipt TYPE datetime DEFAULT time::now();

-- 12. Conversation
DEFINE TABLE IF NOT EXISTS conversation SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS conversation_uid ON TABLE conversation TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE conversation TYPE string;
DEFINE FIELD IF NOT EXISTS user_uid ON TABLE conversation TYPE string;
DEFINE FIELD IF NOT EXISTS title ON TABLE conversation TYPE string;
DEFINE FIELD IF NOT EXISTS created_at ON TABLE conversation TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE conversation TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS archived ON TABLE conversation TYPE bool DEFAULT false;

-- 13. Chat Message
DEFINE TABLE IF NOT EXISTS chat_message SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS message_uid ON TABLE chat_message TYPE string;
DEFINE FIELD IF NOT EXISTS conversation_uid ON TABLE chat_message TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE chat_message TYPE string;
DEFINE FIELD IF NOT EXISTS role ON TABLE chat_message TYPE string ASSERT $value IN ['user', 'assistant', 'system'];
DEFINE FIELD IF NOT EXISTS content ON TABLE chat_message TYPE string;
DEFINE FIELD IF NOT EXISTS input_modality ON TABLE chat_message TYPE string DEFAULT 'text' ASSERT $value IN ['text', 'audio'];
DEFINE FIELD IF NOT EXISTS language_code ON TABLE chat_message TYPE string DEFAULT 'hi-IN';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE chat_message TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS retrieved_event_uids ON TABLE chat_message TYPE array<string> DEFAULT [];
DEFINE FIELD IF NOT EXISTS response_status ON TABLE chat_message TYPE string DEFAULT 'answered' ASSERT $value IN ['answered', 'clarification', 'not_found', 'incomplete'];

-- 14. Audit Event
DEFINE TABLE IF NOT EXISTS audit_event SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS audit_uid ON TABLE audit_event TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE audit_event TYPE string;
DEFINE FIELD IF NOT EXISTS actor_uid ON TABLE audit_event TYPE string;
DEFINE FIELD IF NOT EXISTS device_uid ON TABLE audit_event TYPE string;
DEFINE FIELD IF NOT EXISTS action ON TABLE audit_event TYPE string;
DEFINE FIELD IF NOT EXISTS target_uid ON TABLE audit_event TYPE string;
DEFINE FIELD IF NOT EXISTS outcome ON TABLE audit_event TYPE string ASSERT $value IN ['success', 'failure', 'rejected'];
DEFINE FIELD IF NOT EXISTS created_at ON TABLE audit_event TYPE datetime DEFAULT time::now();

-- 15. Conflict Record
DEFINE TABLE IF NOT EXISTS conflict_record SCHEMAFULL;
DEFINE FIELD IF NOT EXISTS conflict_uid ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS event_uid ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS device_uid ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS conflict_type ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS existing_digest ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS incoming_digest ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS payload_json ON TABLE conflict_record TYPE string;
DEFINE FIELD IF NOT EXISTS detected_at ON TABLE conflict_record TYPE datetime DEFAULT time::now();
