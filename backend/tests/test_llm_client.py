"""
Tests for NvidiaGemmaClient (google/gemma-4-31b-it integration).
"""

from backend.app.llm_client import NvidiaGemmaClient

def test_nvidia_client_configuration():
    client = NvidiaGemmaClient(api_key="test_key", model="google/gemma-4-31b-it")
    assert client.is_configured() is True
    assert client.model == "google/gemma-4-31b-it"
    assert client.base_url == "https://integrate.api.nvidia.com/v1"

def test_nvidia_client_unconfigured_fallback():
    client = NvidiaGemmaClient(api_key="", model="google/gemma-4-31b-it")
    assert client.is_configured() is False
    res = client.generate_grounded_response(
        query="What is Ravi's balance?",
        ledger_context={"balance": 500}
    )
    assert res["status"] == "fallback"
    assert "unconfigured" in res["content"]
