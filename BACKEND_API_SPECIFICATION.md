# KHATAWAS — Backend API Specification

**Document Version:** 1.0.0  
**Date:** 2026-11-21  
**Protocol:** HTTPS / JSON REST  
**Base URL:** `/api/v1`  

---

## 1. Authentication & Security

All requests to `/api/v1` except health check must include an API Key or Bearer Token in the `Authorization` header:

```http
Authorization: Bearer <JWT_OR_SHOP_API_KEY>
X-Shop-ID: shop_kirana_001
```

---

## 2. Endpoints

### 2.1 Health & Status Check
- **Endpoint:** `GET /api/v1/health`
- **Auth Required:** No
- **Response `200 OK`:**
```json
{
  "status": "healthy",
  "version": "1.0.0",
  "surrealdb_connected": true,
  "timestamp": "2026-11-21T19:00:00Z"
}
```

---

### 2.2 Shop Authentication / Registration
- **Endpoint:** `POST /api/v1/auth/shop`
- **Request Body:**
```json
{
  "shop_name": "Sharma Kirana Store",
  "owner_phone": "+919876543210",
  "secret_pin": "1234"
}
```
- **Response `200 OK`:**
```json
{
  "shop_id": "shop:sharma_kirana",
  "access_token": "khatawas_jwt_token_here",
  "token_type": "bearer",
  "expires_in_hours": 720
}
```

---

### 2.3 Idempotent Batch Event Sync (Android Client $\to$ Cloud)
- **Endpoint:** `POST /api/v1/sync/batch`
- **Description:** Receives a batch of offline/online financial transactions from the Android client. Every transaction includes a client-generated UUID (`event_id`). Ingestion is strictly idempotent.
- **Request Body:**
```json
{
  "shop_id": "shop:sharma_kirana",
  "batch_id": "8fa134d4-4bb7-4f9e-8c31-c42323f46f41",
  "events": [
    {
      "event_id": "e987c2b5-3d84-4861-bb1c-843c08f43162",
      "customer_name": "Ramesh",
      "amount_paise": 34000,
      "type": "CREDIT",
      "timestamp": 1732204800000,
      "note": "Ramesh ka 340 udhaar"
    },
    {
      "event_id": "761ba3b8-3f59-4bf4-b153-f0fa80be36c0",
      "customer_name": "Ramesh",
      "amount_paise": 20000,
      "type": "PAYMENT",
      "timestamp": 1732118400000,
      "note": "kal 200 diya tha"
    }
  ]
}
```
- **Response `200 OK`:**
```json
{
  "batch_id": "8fa134d4-4bb7-4f9e-8c31-c42323f46f41",
  "status": "PROCESSED",
  "ingested_count": 2,
  "duplicate_count": 0,
  "results": [
    {
      "event_id": "e987c2b5-3d84-4861-bb1c-843c08f43162",
      "status": "ACCEPTED",
      "server_timestamp": "2026-11-21T19:01:00Z"
    },
    {
      "event_id": "761ba3b8-3f59-4bf4-b153-f0fa80be36c0",
      "status": "ACCEPTED",
      "server_timestamp": "2026-11-21T19:01:01Z"
    }
  ],
  "customer_balances": {
    "Ramesh": 14000
  }
}
```
- **Duplicate Event Handling:** If an `event_id` already exists, the server acknowledges it with status `DUPLICATE_ACCEPTED` and does not re-apply financial ledger increments.

---

### 2.4 Query Customer Balances
- **Endpoint:** `GET /api/v1/customers`
- **Query Params:** `?limit=50&offset=0`
- **Response `200 OK`:**
```json
{
  "shop_id": "shop:sharma_kirana",
  "total_customers": 1,
  "customers": [
    {
      "customer_id": "customer:ramesh_01",
      "name": "Ramesh",
      "balance_paise": 14000,
      "balance_rupees": 140.0,
      "status": "OWES"
    }
  ]
}
```

---

### 2.5 Query Customer Transaction Ledger
- **Endpoint:** `GET /api/v1/customers/{customer_name}/ledger`
- **Response `200 OK`:**
```json
{
  "customer_name": "Ramesh",
  "balance_paise": 14000,
  "transactions": [
    {
      "event_id": "e987c2b5-3d84-4861-bb1c-843c08f43162",
      "type": "CREDIT",
      "amount_paise": 34000,
      "timestamp": "2026-11-21T10:00:00Z"
    },
    {
      "event_id": "761ba3b8-3f59-4bf4-b153-f0fa80be36c0",
      "type": "PAYMENT",
      "amount_paise": 20000,
      "timestamp": "2026-11-20T10:00:00Z"
    }
  ]
}
```

---

### 2.6 Daily Graph Projections (24-Hour Active Window)
- **Endpoint:** `GET /api/v1/projections/daily`
- **Description:** Returns temporary graph projections of transactions created in the past 24 hours.
- **Response `200 OK`:**
```json
{
  "shop_id": "shop:sharma_kirana",
  "window": "24_HOURS",
  "total_credit_paise": 34000,
  "total_payment_paise": 20000,
  "active_events_count": 2,
  "recent_events": [
    {
      "event_id": "e987c2b5-3d84-4861-bb1c-843c08f43162",
      "customer_name": "Ramesh",
      "amount_paise": 34000,
      "type": "CREDIT",
      "expires_at": "2026-11-22T19:01:00Z"
    }
  ]
}
```

---

### 2.7 Expiry Worker Trigger (Admin/Cron)
- **Endpoint:** `POST /api/v1/admin/cleanup-projections`
- **Description:** Deletes expired records from `temporary_event` and `recent_activity`. Leaves permanent records untouched.
- **Response `200 OK`:**
```json
{
  "status": "COMPLETED",
  "purged_records": 14,
  "timestamp": "2026-11-21T19:05:00Z"
}
```
