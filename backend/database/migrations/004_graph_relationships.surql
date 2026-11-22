-- KHATAWAS Cloud SurrealDB Migration: 004_graph_relationships.surql
-- Version: 4
-- Description: Native SurrealDB Graph Relations for Customer Family, Aliases, Transactions, Provenance, Membership, and Shop Ownership

-- 1. Customer Family Relationships
DEFINE TABLE IF NOT EXISTS family_of SCHEMAFULL TYPE RELATION IN customer OUT customer;
DEFINE FIELD IF NOT EXISTS relationship_uid ON TABLE family_of TYPE string;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE family_of TYPE string;
DEFINE FIELD IF NOT EXISTS relationship_type ON TABLE family_of TYPE string ASSERT $value IN ['FATHER', 'MOTHER', 'SON', 'DAUGHTER', 'BROTHER', 'SISTER', 'SPOUSE', 'GRANDFATHER', 'GRANDMOTHER', 'UNCLE', 'AUNT', 'COUSIN', 'OTHER'];
DEFINE FIELD IF NOT EXISTS confirmation_status ON TABLE family_of TYPE string DEFAULT 'unconfirmed' ASSERT $value IN ['confirmed', 'unconfirmed', 'rejected'];
DEFINE FIELD IF NOT EXISTS confidence ON TABLE family_of TYPE float DEFAULT 1.0;
DEFINE FIELD IF NOT EXISTS source ON TABLE family_of TYPE string DEFAULT 'manually_entered' ASSERT $value IN ['user_confirmed', 'manually_entered', 'model_suggested', 'imported'];
DEFINE FIELD IF NOT EXISTS created_at ON TABLE family_of TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS updated_at ON TABLE family_of TYPE datetime DEFAULT time::now();
DEFINE FIELD IF NOT EXISTS supersedes_uid ON TABLE family_of TYPE option<string>;
DEFINE INDEX IF NOT EXISTS idx_family_rel_uid ON TABLE family_of COLUMNS shop_uid, relationship_uid UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_family_traverse ON TABLE family_of COLUMNS shop_uid, in, out, confirmation_status;

-- 2. Customer Alias Relationships
DEFINE TABLE IF NOT EXISTS alias_of SCHEMAFULL TYPE RELATION IN customer_alias OUT customer;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE alias_of TYPE string;
DEFINE FIELD IF NOT EXISTS confirmation_status ON TABLE alias_of TYPE string DEFAULT 'confirmed' ASSERT $value IN ['confirmed', 'unconfirmed', 'rejected'];
DEFINE FIELD IF NOT EXISTS source ON TABLE alias_of TYPE string DEFAULT 'manually_entered';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE alias_of TYPE datetime DEFAULT time::now();
DEFINE INDEX IF NOT EXISTS idx_alias_traverse ON TABLE alias_of COLUMNS shop_uid, in, out;

-- 3. Customer Transaction Navigation Relationships
DEFINE TABLE IF NOT EXISTS has_transaction SCHEMAFULL TYPE RELATION IN customer OUT ledger_event;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE has_transaction TYPE string;
DEFINE FIELD IF NOT EXISTS created_at ON TABLE has_transaction TYPE datetime DEFAULT time::now();
DEFINE INDEX IF NOT EXISTS idx_has_transaction_unique ON TABLE has_transaction COLUMNS out UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_has_transaction_cust ON TABLE has_transaction COLUMNS shop_uid, in, created_at;

-- 4. Transaction Device Provenance Relationships
DEFINE TABLE IF NOT EXISTS created_on_device SCHEMAFULL TYPE RELATION IN ledger_event OUT device_registry;
DEFINE FIELD IF NOT EXISTS shop_uid ON TABLE created_on_device TYPE string;
DEFINE FIELD IF NOT EXISTS created_at ON TABLE created_on_device TYPE datetime DEFAULT time::now();
DEFINE INDEX IF NOT EXISTS idx_created_on_device_unique ON TABLE created_on_device COLUMNS in UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_device_provenance ON TABLE created_on_device COLUMNS shop_uid, out, created_at;

-- 5. User Shop Membership Relationships
DEFINE TABLE IF NOT EXISTS member_of SCHEMAFULL TYPE RELATION IN app_user OUT shop;
DEFINE FIELD IF NOT EXISTS role ON TABLE member_of TYPE string ASSERT $value IN ['owner', 'admin', 'operator', 'viewer'];
DEFINE FIELD IF NOT EXISTS status ON TABLE member_of TYPE string DEFAULT 'active';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE member_of TYPE datetime DEFAULT time::now();
DEFINE INDEX IF NOT EXISTS idx_member_of_unique ON TABLE member_of COLUMNS in, out UNIQUE;

-- 6. Shop Customer Ownership Relationships
DEFINE TABLE IF NOT EXISTS owns_customer SCHEMAFULL TYPE RELATION IN shop OUT customer;
DEFINE FIELD IF NOT EXISTS status ON TABLE owns_customer TYPE string DEFAULT 'active';
DEFINE FIELD IF NOT EXISTS created_at ON TABLE owns_customer TYPE datetime DEFAULT time::now();
DEFINE INDEX IF NOT EXISTS idx_owns_customer_unique ON TABLE owns_customer COLUMNS out UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_shop_customers ON TABLE owns_customer COLUMNS in, status;
