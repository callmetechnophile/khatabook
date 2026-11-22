"""
KHATAWAS Backend - NVIDIA NIM API Integration for Google Gemma-4-31B-IT.

Connects to NVIDIA API (https://integrate.api.nvidia.com/v1) using the configured
NVIDIA_API_KEY to provide high-fidelity grounded reasoning and multi-turn conversational
answers for the KHATAWAS financial ledger.
"""

import os
import json
import logging
from typing import Dict, Any, Optional, List
import urllib.request
import urllib.error

logger = logging.getLogger("khatawas.llm")

NVIDIA_API_KEY = os.getenv("NVIDIA_API_KEY", "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")
NVIDIA_BASE_URL = os.getenv("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1")
NVIDIA_MODEL = os.getenv("NVIDIA_MODEL", "google/gemma-4-31b-it")

GROUNDED_SYSTEM_PROMPT = """You are KHATAWAS AI, a private, database-grounded financial assistant for Indian kirana shopkeepers.

STRICT OPERATIONAL RULES:
1. FACTUAL GROUNDING: Your entire factual knowledge during a conversation comes solely from the provided KHATAWAS ledger data.
2. NO HALLUCINATION: If the information does not exist in the ledger data, state clearly that you cannot find the record. Never invent transactions, balances, or customer names.
3. LANGUAGE: Respond in the language of the user's question (Devanagari Hindi, Kannada, or clear Indian English).
4. CONCISENESS: Keep answers short, polite, and actionable for busy shopkeepers.
"""

class NvidiaGemmaClient:
    """Client for invoking NVIDIA NIM endpoints hosting google/gemma-4-31b-it."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None
    ):
        self.api_key = api_key if api_key is not None else os.getenv("NVIDIA_API_KEY", NVIDIA_API_KEY)
        self.base_url = (base_url if base_url is not None else os.getenv("NVIDIA_BASE_URL", NVIDIA_BASE_URL)).rstrip("/")
        self.model = model if model is not None else os.getenv("NVIDIA_MODEL", NVIDIA_MODEL)

    def is_configured(self) -> bool:
        return bool(self.api_key and not self.api_key.startswith("your_"))

    def generate_grounded_response(
        self,
        query: str,
        ledger_context: Dict[str, Any],
        conversation_history: Optional[List[Dict[str, str]]] = None,
        max_tokens: int = 250,
        temperature: float = 0.2,
        timeout: int = 35
    ) -> Dict[str, Any]:
        """
        Generate a database-grounded response using google/gemma-4-31b-it on NVIDIA NIM.
        """
        if not self.is_configured():
            logger.warning("NVIDIA API key not configured. Using deterministic rule fallback.")
            return {
                "content": "Ledger data found, but NVIDIA cloud reasoning is unconfigured.",
                "model": "fallback-local",
                "status": "fallback"
            }

        endpoint = f"{self.base_url}/chat/completions"
        context_str = json.dumps(ledger_context, ensure_ascii=False, indent=2)

        messages = [
            {"role": "system", "content": f"{GROUNDED_SYSTEM_PROMPT}\n\nAUTHORITATIVE LEDGER DATA:\n{context_str}"}
        ]

        if conversation_history:
            for turn in conversation_history[-4:]:  # last 4 turns for context
                messages.append({"role": turn.get("role", "user"), "content": turn.get("content", "")})

        messages.append({"role": "user", "content": query})

        payload = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "top_p": 0.95
        }

        req = urllib.request.Request(
            endpoint,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "User-Agent": "KHATAWAS-Backend/1.0"
            },
            data=json.dumps(payload).encode("utf-8")
        )

        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                result = json.loads(response.read().decode("utf-8"))
                choice = result["choices"][0]
                answer = choice["message"]["content"].strip()
                return {
                    "content": answer,
                    "model": self.model,
                    "status": "success",
                    "usage": result.get("usage", {})
                }
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="ignore")
            logger.error(f"NVIDIA API HTTP Error {e.code}: {err_body}")
            return {
                "content": f"AI service temporarily unavailable (HTTP {e.code}).",
                "model": self.model,
                "status": "error",
                "detail": err_body
            }
        except Exception as e:
            logger.error(f"NVIDIA API Error: {str(e)}")
            return {
                "content": "AI reasoning service timed out. Please try again.",
                "model": self.model,
                "status": "error",
                "detail": str(e)
            }

# Global singleton client
nvidia_client = NvidiaGemmaClient()
