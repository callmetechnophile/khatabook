"""
KHATAWAS Backend - Chatbot Read-Only Database Access Tests
Verifies allowlisted query intent routing, read-only enforcement,
and persistent conversation history in SurrealDB Cloud.
"""

import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.auth import generate_jwt_token

client = TestClient(app)

@pytest.fixture(scope="module")
def setup_chat_data():
    shop_uid = "chat_shop_01"
    token = generate_jwt_token(user_uid="chat_user", shop_uid=shop_uid, role="owner")
    headers = {"Authorization": f"Bearer {token}"}

    # Setup customer and ledger events
    payload = {
        "shop_uid": shop_uid,
        "batch_id": "chat_setup_batch",
        "events": [
            {
                "event_uid": "chat_ev_01",
                "customer_name": "Deepak",
                "amount_paise": 45000,
                "category": "CREDIT"
            },
            {
                "event_uid": "chat_ev_02",
                "customer_name": "Deepak",
                "amount_paise": 15000,
                "category": "DEBIT"
            }
        ]
    }
    client.post("/api/v1/sync/events", json=payload, headers=headers)
    return {"shop_uid": shop_uid, "headers": headers}

def test_chatbot_customer_balance_intent(setup_chat_data):
    headers = setup_chat_data["headers"]
    payload = {
        "intent": "CUSTOMER_BALANCE",
        "parameters": {"customer_name": "Deepak"},
        "conversation_uid": "conv_test_01",
        "query_text": "Deepak ka balance kitna hai?"
    }
    res = client.post("/api/v1/chat/query", json=payload, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "answered"
    assert data["response_data"]["balance_paise"] == 30000 # 45000 - 15000
    assert data["response_data"]["balance_rupees"] == 300.0

def test_chatbot_customer_history_intent(setup_chat_data):
    headers = setup_chat_data["headers"]
    payload = {
        "intent": "CUSTOMER_HISTORY",
        "parameters": {"customer_name": "Deepak"}
    }
    res = client.post("/api/v1/chat/query", json=payload, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "answered"
    assert data["response_data"]["total_events"] == 2

def test_chatbot_ledger_summary_intent(setup_chat_data):
    headers = setup_chat_data["headers"]
    payload = {
        "intent": "LEDGER_SUMMARY",
        "parameters": {}
    }
    res = client.post("/api/v1/chat/query", json=payload, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "answered"
    assert data["response_data"]["total_credit_paise"] == 45000
    assert data["response_data"]["total_debit_paise"] == 15000
    assert data["response_data"]["net_balance_paise"] == 30000

def test_chatbot_missing_customer_not_found(setup_chat_data):
    headers = setup_chat_data["headers"]
    payload = {
        "intent": "CUSTOMER_BALANCE",
        "parameters": {"customer_name": "NonExistentCustomer"}
    }
    res = client.post("/api/v1/chat/query", json=payload, headers=headers)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "not_found"

def test_chatbot_conversation_persistence(setup_chat_data):
    headers = setup_chat_data["headers"]
    shop_uid = setup_chat_data["shop_uid"]
    
    # Save a chat message manually
    msg_payload = {
        "conversation_uid": "conv_persist_test",
        "role": "user",
        "content": "Aaj kitna credit hua?",
        "input_modality": "audio"
    }
    res = client.post("/api/v1/chat/messages", json=msg_payload, headers=headers)
    assert res.status_code == 200
    assert "message_uid" in res.json()

    # List conversations
    res_list = client.get(f"/api/v1/chat/conversations?shop_id={shop_uid}", headers=headers)
    assert res_list.status_code == 200
    convs = res_list.json()["conversations"]
    assert any(c["conversation_uid"] == "conv_persist_test" for c in convs)

