# KHATAWAS — Responsible AI, Ethics & Compliance Audit Report

**Audit Date:** 2026-11-21  
**Audit Standard:** Hackathon Responsible AI Baseline & Kirana Merchant Protection Guidelines  

---

## 1. Compliance Verification Checklist

| Requirement | Audit Item | Verification Status | Evidence & Findings |
|---|---|:---:|---|
| **C1** | **AI Disclosure on First Interaction** | **NON-COMPLIANT (Critical)** | The application launches directly into the PTT button screen. There is NO dialog, notice, banner, or spoken audio notifying the merchant that their voice is processed by speech AI. |
| **C2** | **Disclosure in Selected User Language** | **NON-COMPLIANT** | No language selection interface or localized Hindi/Indic disclosure exists. |
| **C3** | **Synthetic / Consented Demo Data** | **COMPLIANT** | Unit tests and mock bridges strictly use generic synthetic Indian names (*"Ramesh"*, *"Suresh"*, *"Amit"*). No PII or customer records are embedded. |
| **C4** | **No Real-Person Voice Cloning** | **COMPLIANT** | The application utilizes generic native Android `TextToSpeech` or standard Gnani TTS. No voice cloning technologies are present. |
| **C5** | **No Predatory Lending Functionality** | **COMPLIANT** | KHATAWAS functions exclusively as a personal merchant ledger (Khata). It does not provide credit scoring, loans, APR calculations, or financing services. |
| **C6** | **No Harassing / Pressure Reminders** | **COMPLIANT** | Automated SMS/WhatsApp debt collection messages or threatening reminders are not implemented. |
| **C7** | **Encrypted Transcripts at Rest** | **PARTIALLY COMPLIANT** | Spoken transcripts are parsed in-memory and not stored in plaintext files; however, structured transaction data is stored in SQLCipher. No raw audio recordings are written to disk. |
| **C8** | **Transcript / Data Deletion Mechanism** | **NON-COMPLIANT** | The application provides NO "Clear Ledger", "Delete All Data", or post-judging purge button in the UI. Merchant data persists in SQLite indefinitely unless the app is uninstalled. |
| **C9** | **Secure API Key Management** | **COMPLIANT** | `GNANI_API_KEY` is loaded dynamically from `local.properties` via `app/build.gradle` BuildConfig. `local.properties` is verified ignored by Git. Zero API keys committed to repository history. |

---

## 2. Critical Remediation Actions Required

1. **Mandatory First-Run AI Disclosure Modal**:
   - Implement an alert dialog or audio prompt on first launch stating:
     *"यह ऐप आपकी आवाज़ को डिजिटल बहीखाते में बदलने के लिए AI का उपयोग करता है।"* (This app uses AI to convert your voice into a digital ledger).
2. **One-Tap Data Purge (Judging Compliance)**:
   - Provide a "Purge Test Data" button in settings or menu to completely shred the encrypted database and cryptographic keys upon request.
