# KHATAWAS ANDROID — FINAL TEST REPORT & SHOPKEEPER PERSPECTIVE VERIFICATION

**Date:** 22 November 2026  
**Package:** `com.khatawas`  
**Test Devices:**
- Physical Device: `10BF8P109X003X2` (Audited in smoke suite)
- Target Emulator: `emulator-5554` (API 34, Android 14)
**Build Variant:** `debug` (`app/build/outputs/apk/debug/app-debug.apk`) / `release`

---

## 1. Scope & Objective

Conduct on-device ADB and unit test verification for the **Shopkeeper Perspective Cash-Flow UI Colour Correction**:
- **GIVE UDHAAR / ADD CREDIT**: **RED** (`#D32F2F`) — Shopkeeper gives goods/credit $\implies$ customer outstanding balance increases (`balance_effect_paise > 0`).
- **GOT PAYMENT / RECEIVE PAYMENT**: **GREEN** (`#2E7D32`) — Shopkeeper receives money $\implies$ customer outstanding balance decreases (`balance_effect_paise < 0`).
- **Customer Outstanding Balance**: **RED** (`#D32F2F`) for debt owed ($> 0$), **GREEN** (`#2E7D32`) for advance credit ($< 0$).
- **Transaction History & Badges**: Consistent RED for CREDIT entries, GREEN for PAYMENT/DEBIT entries.
- **Arithmetic Integrity**: Strict integer paise calculations across SQLite database, ViewModel, and presentation layer.

---

## 2. On-Device Acceptance Test Results

### Test Scenario:
Starting with an outstanding customer balance of ₹1,000:
1. Recording a ₹500 Give Udhaar transaction displays in RED and produces ₹1,500 outstanding.
2. Recording a Got Payment transaction displays in GREEN and reduces outstanding balance.

### Verified Results:
- **Baseline Outstanding Udhaar:** `₹1,000` rendered in **RED** (`#D32F2F`, pixel RGB `(218, 78, 77, 255)`).
- **Give Udhaar Dialog:** Title & Save button styled in **RED** (`#D32F2F`, pixel RGB `(211, 47, 47, 255)`).
- **Post-Give Udhaar Balance:** Updated to `₹1,500` rendered in **RED**.
- **Got Payment Dialog:** Title & Save button styled in **GREEN** (`#2E7D32`, pixel RGB `(46, 125, 50, 255)`).
- **Post-Got Payment Balance:** Updated to `₹500` rendered in **RED** (since debt owed $> 0$).
- **Customer Directory Screen:**
  - Debtor balance (`₹500`): **RED** text `(218, 78, 77, 255)`.
  - Advance balance (`-₹500`): **GREEN** text `(55, 131, 59, 255)`.
  - Quick action buttons: Give Udhaar = **RED**, Got Payment = **GREEN**.
- **History Tab:**
  - `CREDIT` entries: **RED** text `(208, 79, 79, 255)`.
  - `DEBIT` / `PAYMENT` entries: **GREEN** text `(53, 112, 57, 255)`.

---

## 3. Automated Test Suite

- Test class: `com.khatawas.ui.ShopkeeperPerspectiveColorTest`
- Status: **26 tests executed, 0 failures, 100% passed**.
- Build command: `./gradlew testDebugUnitTest` $\to$ `BUILD SUCCESSFUL in 15s`.

---

## 4. Screenshot Evidence

All screenshots captured from the runtime test are archived under `docs/screenshots/`:
1. `docs/screenshots/01_baseline_1000_red.png` — Baseline unmasked home screen showing ₹1,000 in RED.
2. `docs/screenshots/02_dialog_give_udhar.png` — Give Udhaar dialog with RED header and action button.
3. `docs/screenshots/03_give_udhar_500_result_1500_red.png` — Total outstanding increased to ₹1,500 in RED.
4. `docs/screenshots/04_dialog_got_payment.png` — Got Payment dialog with GREEN header and action button.
5. `docs/screenshots/05_got_payment_1000_result_500_green.png` — Recent payment recorded in GREEN badge.
6. `docs/screenshots/06_got_payment_500_result_500_green.png` — Final balance decreased to ₹500.
7. `docs/screenshots/07_customers_tab_verified.png` — Customers directory verifying debtor (RED) and advance (GREEN).
8. `docs/screenshots/08_history_tab_verified.png` — History tab verifying CREDIT (RED) and PAYMENT (GREEN).
