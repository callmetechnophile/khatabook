"""
KHATAWAS Backend - SurrealDB Cloud Schema Tests
Validates SCHEMAFULL constraints, assertions, unique indexes, and field typing.
"""

import pytest
from surrealdb import Surreal
from backend.database.surreal_client import SurrealDatabaseClient
from backend.database.migration_runner import MigrationRunner

@pytest.fixture(scope="module")
def db_client():
    client = SurrealDatabaseClient(url="mem://", namespace="test_schema_ns", database="test_schema_db")
    return client

def test_all_required_tables_exist(db_client):
    required_tables = [
        "schema_version", "app_user", "shop", "shop_member",
        "customer", "customer_alias", "customer_relationship",
        "device_registry", "ledger_event", "event_chain_head",
        "device_sync_state", "sync_receipt", "conversation",
        "chat_message", "audit_event", "conflict_record"
    ]
    # Verify by running INFO FOR DB or checking each table
    for tbl in required_tables:
        res = db_client.query(f"SELECT * FROM {tbl} LIMIT 1;")
        assert isinstance(res, list), f"Table {tbl} did not answer query"

def test_currency_assertion_inr_only(db_client):
    # INR should succeed
    valid_shop = (
        "CREATE shop SET "
        "owner_uid = 'user_inr_valid', "
        "shop_name = 'Valid INR Kirana', "
        "currency = 'INR';"
    )
    res_valid = db_client.query(valid_shop)
    assert len(res_valid) > 0

    # Non-INR currency must be rejected by SurrealDB ASSERT constraint
    invalid_shop = (
        "CREATE shop SET "
        "owner_uid = 'user_usd_invalid', "
        "shop_name = 'Invalid USD Kirana', "
        "currency = 'USD';"
    )
    with pytest.raises(Exception):
        db_client.query(invalid_shop)

def test_amount_paise_strictly_positive(db_client):
    # Non-positive amount must be rejected by ASSERT $value > 0
    invalid_event = (
        "CREATE ledger_event SET "
        "event_uid = 'neg_amt_001', "
        "shop_uid = 'shop_test', "
        "customer_uid = 'cust_test', "
        "originating_device_uid = 'dev_test', "
        "device_sequence = 0, "
        "event_type = 'TRANSACTION', "
        "category = 'CREDIT', "
        "amount_paise = -500, "
        "balance_effect_paise = -500, "
        "currency = 'INR', "
        "occurred_at = time::now(), "
        "previous_digest = '0000000000000000000000000000000000000000000000000000000000000000', "
        "event_digest = 'abc';"
    )
    with pytest.raises(Exception):
        db_client.query(invalid_event)

def test_invalid_category_rejected(db_client):
    # Invalid category must be rejected by ASSERT $value IN ['CREDIT', 'DEBIT', 'STUCK', 'ILLEGAL']
    invalid_cat_event = (
        "CREATE ledger_event SET "
        "event_uid = 'bad_cat_001', "
        "shop_uid = 'shop_test', "
        "customer_uid = 'cust_test', "
        "originating_device_uid = 'dev_test', "
        "device_sequence = 0, "
        "event_type = 'TRANSACTION', "
        "category = 'INVALID_CATEGORY', "
        "amount_paise = 1000, "
        "balance_effect_paise = 1000, "
        "currency = 'INR', "
        "occurred_at = time::now(), "
        "previous_digest = '0000000000000000000000000000000000000000000000000000000000000000', "
        "event_digest = 'abc';"
    )
    with pytest.raises(Exception):
        db_client.query(invalid_cat_event)

def test_unique_indexes_enforced(db_client):
    # Unique event_uid
    sql1 = (
        "CREATE ledger_event SET "
        "event_uid = 'unique_event_test_01', "
        "shop_uid = 'shop_test', "
        "customer_uid = 'cust_test', "
        "originating_device_uid = 'dev_test', "
        "device_sequence = 1, "
        "event_type = 'TRANSACTION', "
        "category = 'CREDIT', "
        "amount_paise = 1000, "
        "balance_effect_paise = 1000, "
        "currency = 'INR', "
        "occurred_at = time::now(), "
        "previous_digest = '0000000000000000000000000000000000000000000000000000000000000000', "
        "event_digest = 'digest_1';"
    )
    db_client.query(sql1)

    # Attempting to insert duplicate event_uid must fail unique index constraint
    sql2 = (
        "CREATE ledger_event SET "
        "event_uid = 'unique_event_test_01', "
        "shop_uid = 'shop_test', "
        "customer_uid = 'cust_test', "
        "originating_device_uid = 'dev_test', "
        "device_sequence = 2, "
        "event_type = 'TRANSACTION', "
        "category = 'CREDIT', "
        "amount_paise = 2000, "
        "balance_effect_paise = 2000, "
        "currency = 'INR', "
        "occurred_at = time::now(), "
        "previous_digest = 'digest_1', "
        "event_digest = 'digest_2';"
    )
    with pytest.raises(Exception):
        db_client.query(sql2)

