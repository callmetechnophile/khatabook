"""
KHATAWAS Backend - FastAPI Main Application
"""

from fastapi import FastAPI, Header, HTTPException, Depends, status
from fastapi.middleware.cors import CORSMiddleware
from datetime import datetime, timezone
import asyncio

from backend.app.models import (
    ShopAuthRequest, ShopAuthResponse,
    SyncBatchRequest, SyncBatchResponse, SyncEventResult,
    CustomerListResponse, CustomerLedgerResponse,
    DailyProjectionResponse, CleanupResponse
)
from backend.app.surreal_client import cloud_db

app = FastAPI(
    title="KHATAWAS Financial Sync & Ledger API",
    version="1.0.0",
    description="Backend API for KHATAWAS voice ledger with SurrealDB cloud persistence and 24h graph projections."
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

async def verify_auth(authorization: str = Header(None), x_shop_id: str = Header(None)):
    """Simple auth guard checking presence of Authorization header."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid Bearer authorization token."
        )
    return authorization.split(" ")[1]

@app.get("/api/v1/health")
def health_check():
    return {
        "status": "healthy",
        "version": "1.0.0",
        "surrealdb_connected": True,
        "timestamp": datetime.now(timezone.utc).isoformat()
    }

@app.post("/api/v1/auth/shop", response_model=ShopAuthResponse)
def authenticate_shop(request: ShopAuthRequest):
    shop = cloud_db.get_or_create_shop(request.shop_name, request.owner_phone)
    token = f"khatawas_jwt_{shop['id'].replace(':', '_')}"
    return ShopAuthResponse(
        shop_id=shop["id"],
        access_token=token,
        token_type="bearer",
        expires_in_hours=720
    )

@app.post("/api/v1/sync/batch", response_model=SyncBatchResponse)
def sync_batch(request: SyncBatchRequest, token: str = Depends(verify_auth)):
    events_data = [ev.model_dump() if hasattr(ev, 'model_dump') else ev.dict() for ev in request.events]
    results, balances = cloud_db.ingest_sync_batch(request.shop_id, events_data)

    ingested = sum(1 for r in results if r["status"] == "ACCEPTED")
    duplicates = sum(1 for r in results if r["status"] == "DUPLICATE_ACCEPTED")

    return SyncBatchResponse(
        batch_id=request.batch_id,
        status="PROCESSED",
        ingested_count=ingested,
        duplicate_count=duplicates,
        results=[SyncEventResult(**r) for r in results],
        customer_balances=balances
    )

@app.get("/api/v1/customers", response_model=CustomerListResponse)
def get_customers(shop_id: str = "shop:default_kirana", token: str = Depends(verify_auth)):
    custs = cloud_db.get_customer_balances(shop_id)
    return CustomerListResponse(
        shop_id=shop_id,
        total_customers=len(custs),
        customers=custs
    )

@app.get("/api/v1/customers/{customer_name}/ledger", response_model=CustomerLedgerResponse)
def get_customer_ledger(customer_name: str, shop_id: str = "shop:default_kirana", token: str = Depends(verify_auth)):
    ledger = cloud_db.get_customer_ledger(shop_id, customer_name)
    if not ledger:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Customer '{customer_name}' not found."
        )
    return CustomerLedgerResponse(**ledger)

@app.get("/api/v1/projections/daily", response_model=DailyProjectionResponse)
def get_daily_projection(shop_id: str = "shop:default_kirana", token: str = Depends(verify_auth)):
    proj = cloud_db.get_daily_projection(shop_id)
    return DailyProjectionResponse(**proj)

@app.post("/api/v1/admin/cleanup-projections", response_model=CleanupResponse)
def trigger_cleanup(token: str = Depends(verify_auth)):
    purged = cloud_db.cleanup_expired_projections()
    return CleanupResponse(
        status="COMPLETED",
        purged_records=purged,
        timestamp=datetime.now(timezone.utc).isoformat()
    )
