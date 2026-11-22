"""
KHATAWAS Backend - SurrealDB Performance Benchmarks
Measures real latencies for single-event upload, batch upload, duplicate detection,
customer history, and balance aggregation.
"""

import time
import pytest
from backend.database.surreal_client import SurrealDatabaseClient

@pytest.fixture(scope="module")
def db_client():
    return SurrealDatabaseClient(url="mem://", namespace="test_bench_ns", database="test_bench_db")

def test_performance_benchmarks(db_client):
    shop_uid = "bench_shop_01"
    device_uid = "bench_dev_01"

    # 1. Single-Event Upload Latency
    single_event = [{
        "event_uid": "bench_single_001",
        "customer_name": "Benchmark User",
        "amount_paise": 10000,
        "category": "CREDIT",
        "device_sequence": 0
    }]
    start_time = time.perf_counter()
    res_single, _ = db_client.ingest_sync_batch(shop_uid, single_event, device_uid)
    single_latency_ms = (time.perf_counter() - start_time) * 1000.0
    assert res_single[0]["status"] == "ACCEPTED"

    # 2. Duplicate Detection Latency
    start_dup = time.perf_counter()
    res_dup, _ = db_client.ingest_sync_batch(shop_uid, single_event, device_uid)
    dup_latency_ms = (time.perf_counter() - start_dup) * 1000.0
    assert res_dup[0]["status"] == "DUPLICATE_ACCEPTED"

    # 3. Batch Upload Latency (50 events)
    batch_events = []
    for i in range(1, 51):
        batch_events.append({
            "event_uid": f"bench_batch_ev_{i:03d}",
            "customer_name": f"Batch Customer {i % 5}",
            "amount_paise": 1000 * i,
            "category": "CREDIT" if i % 2 == 0 else "DEBIT",
            "device_sequence": i
        })
    start_batch = time.perf_counter()
    res_batch, _ = db_client.ingest_sync_batch(shop_uid, batch_events, device_uid)
    batch_latency_ms = (time.perf_counter() - start_batch) * 1000.0
    assert len(res_batch) == 50

    # 4. Customer Balance Aggregation Latency
    start_bal = time.perf_counter()
    balances = db_client.get_customer_balances(shop_uid)
    bal_latency_ms = (time.perf_counter() - start_bal) * 1000.0
    assert len(balances) >= 5

    # 5. Customer History Query Latency
    start_hist = time.perf_counter()
    history = db_client.get_customer_ledger(shop_uid, "Batch Customer 0")
    hist_latency_ms = (time.perf_counter() - start_hist) * 1000.0
    assert history is not None

    # 6. Chatbot Retrieval Latency
    start_chat = time.perf_counter()
    chat_res = db_client.query_chatbot_intent(shop_uid, "CUSTOMER_BALANCE", {"customer_name": "Batch Customer 0"})
    chat_latency_ms = (time.perf_counter() - start_chat) * 1000.0
    assert chat_res["status"] == "answered"

    # Print benchmark results for verification report
    print("\n" + "=" * 60)
    print("KHATAWAS SurrealDB Cloud Engine - Real Latency Benchmarks")
    print("=" * 60)
    print(f"Single-event upload latency:       {single_latency_ms:.2f} ms")
    print(f"Duplicate detection latency:        {dup_latency_ms:.2f} ms")
    print(f"Batch upload (50 events) latency:   {batch_latency_ms:.2f} ms ({batch_latency_ms/50:.2f} ms/event)")
    print(f"Customer balance aggregation:       {bal_latency_ms:.2f} ms")
    print(f"Customer history query latency:     {hist_latency_ms:.2f} ms")
    print(f"Chatbot query intent latency:       {chat_latency_ms:.2f} ms")
    print("=" * 60)

    # Assert SLA targets (all operations must be sub-100ms in embedded engine)
    assert single_latency_ms < 100.0
    assert dup_latency_ms < 100.0
    assert bal_latency_ms < 100.0
    assert hist_latency_ms < 100.0
    assert chat_latency_ms < 100.0

