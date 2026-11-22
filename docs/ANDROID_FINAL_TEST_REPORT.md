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

---

## 5. Language Settings UI State Synchronization Verification

### 5.1 Problem Statement & Root Cause
Prior to this fix, selecting a language on the Settings screen updated application resources and translated interface strings, but the language selector controls failed to visually indicate the active language (e.g. selecting Kannada showed Kannada text across the app, but English remained visually selected or no indicator was checked).
- **Root Cause**: The language selector buttons/radio buttons were not bound to an authoritative reactive state source of truth, lacked bidirectional state propagation upon Activity recreation, and tab state was lost during configuration changes.

### 5.2 Architecture: `LanguageManager` as Single Source of Truth
1. **Authoritative State Singleton**: `com.khatawas.util.LanguageManager` manages the active language (`en`, `hi`, `kn`), persisting choices synchronously via `SharedPreferences` (`apply()` / `commit()`).
2. **Context Wrapping & Resource Application**:
   - `wrapContext(context)` applies the selected `LocaleList` at the `attachBaseContext` level for API 24+ compatibility.
   - `applyToResources(resources, context)` guarantees runtime resource configuration synchronization.
3. **Reactive UI State Flow**:
   - `currentLanguageFlow: StateFlow<String>` provides reactive emissions whenever language state shifts.
   - Selection UI maintains listener detachment/reattachment during updates to prevent circular event cascades.
4. **Lifecycle & Tab State Preservation**:
   - `onSaveInstanceState` and `onCreate` preserve active tab (`KEY_ACTIVE_TAB`), preventing tab reset during `recreate()`.
   - `onResume()` synchronizes the UI controls with `LanguageManager.getLanguage()`.

### 5.3 Automated Unit Tests (`LanguageManagerTest.kt`)
Ran 8 dedicated unit tests with `gradlew testDebugUnitTest`:
1. `testDefaultLanguageIsEnglish` — Verifies clean-state default to English (`en`).
2. `testSetLanguageHindi` — Verifies Hindi persistence and state reflection.
3. `testSetLanguageKannada` — Verifies Kannada persistence and state reflection.
4. `testTransitionCycleEnglishToHindiToKannadaToEnglish` — Validates complete language transition loops without state drift.
5. `testPersistenceSurvivesProcessRestartSimulation` — Simulates app termination and verifies saved preferences reload correctly.
6. `testUnsupportedLanguageFallsBackToEnglish` — Gracefully degrades unsupported locales to English.
7. `testRapidRepeatedSelectionStability` — Rapid consecutive changes without race conditions or memory corruption.
8. `testSupportedLanguagesIntegrity` — Validates `SUPPORTED_LANGUAGES` set integrity.
**Result**: **32 total project tests passing, 0 failures, 100% success rate.**

### 5.4 Live On-Device ADB Test Suite (`emulator-5554`)
The on-device test suite executed against `com.khatawas` on Android 14 (`emulator-5554`):

| Test Case | Description | Verification Method | Status | Evidence |
|---|---|---|---|---|
| **Test 1** | English Baseline | Inspect UI hierarchy on Settings tab; verify `rb_lang_en.isChecked == true`. | **PASS** | `docs/screenshots/lang_01_english_active.png` |
| **Test 2** | EN $\to$ HI Transition | Tap Hindi option; verify `recreate()`, `rb_lang_hi.isChecked == true`, and Hindi strings displayed. | **PASS** | `docs/screenshots/lang_02_hindi_active.png` |
| **Test 3** | HI $\to$ KN Transition | Tap Kannada option; verify `rb_lang_kn.isChecked == true`, strings displayed in Kannada script. | **PASS** | `docs/screenshots/lang_03_kannada_active.png` |
| **Test 4** | KN $\to$ EN Transition | Tap English option; verify complete restoration of English language and `rb_lang_en.isChecked == true`. | **PASS** | `docs/screenshots/lang_04_english_restored.png` |
| **Test 5** | Process Restart Persistence | Set to Kannada $\to$ force-stop app (`am force-stop`) $\to$ cold launch app $\to$ verify Kannada persists. | **PASS** | `docs/screenshots/lang_05_restart_persistence_kannada.png` |
| **Test 6** | Navigation Persistence | Set to Hindi $\to$ navigate through all 5 tabs (Home, Customers, History, Settings) $\to$ verify Hindi intact. | **PASS** | `docs/screenshots/lang_06_navigation_persistence_hindi.png` |
| **Test 7** | Rapid Selection Stability | Rapid taps EN $\to$ HI $\to$ KN $\to$ HI in rapid succession; verify zero crashes and single checked state. | **PASS** | `docs/screenshots/lang_07_rapid_selection_stable.png` |

