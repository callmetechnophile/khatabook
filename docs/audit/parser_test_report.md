# KHATAWAS — Speech & Transaction Intelligence Test Report

**Audit Date:** 2026-11-21  
**Test Suite:** `com.khatawas.logic.ParserAuditTest` executed against `LedgerParser.kt`  
**Evaluation Target:** 20 Standard Kirana Hinglish utterances across 8 functional categories  

---

## 1. Test Results Matrix

| Category | Input Utterance | Expected Interpretation | Actual Parser Output | Status | Defect Description |
|---|---|---|---|:---:|---|
| **Credit** | `"Ramesh ka 340 udhaar"` | Customer: Ramesh<br>Amount: ₹340<br>Type: CREDIT | `Transaction(name=Ramesh, amount=340, type=CREDIT, dateOffset=0)` | **PASS** | Successfully parsed single direct pattern. |
| **Credit** | `"Suresh ke 500 rupaye baaki"` | Customer: Suresh<br>Amount: ₹500<br>Type: CREDIT | `Transaction(name=Suresh, amount=500, type=CREDIT, dateOffset=0)` | **PASS** | Successfully matched amount and defaulted to CREDIT. |
| **Credit** | `"Amit ko 250 ka samaan diya"` | Customer: Amit<br>Amount: ₹250<br>Type: CREDIT *(goods given)* | `Transaction(name=Amit, amount=250, type=DEBIT, dateOffset=0)` | **FAIL (P1)** | **Financial Inversion:** Keyword `"diya"` triggered `DEBIT` (payment). Recorded as customer paying instead of receiving goods. |
| **Payment** | `"Ramesh ne 200 diya"` | Customer: Ramesh<br>Amount: ₹200<br>Type: DEBIT | `Transaction(name=Ramesh, amount=200, type=DEBIT, dateOffset=0)` | **PASS** | Successfully matched. |
| **Payment** | `"Suresh ne 500 rupaye jama kiye"` | Customer: Suresh<br>Amount: ₹500<br>Type: DEBIT | `Transaction(name=Suresh, amount=500, type=DEBIT, dateOffset=0)` | **PASS** | Matched `"jama"` $\to$ `DEBIT`. |
| **Payment** | `"Amit ka payment mil gaya"` | Customer: Amit<br>Amount: Inquire / DEBIT | `null` | **FAIL (P2)** | No numeric value exists; rejected without conversational prompt. |
| **Multi-Tx** | `"Ramesh ka 340 udhaar, kal 200 diya tha"` | Tx1: Ramesh Credit ₹340 (today)<br>Tx2: Ramesh Debit ₹200 (yesterday)<br>Bal: ₹140 | `Transaction(name=Ramesh, amount=340, type=DEBIT, dateOffset=-1)` | **FAIL (P1)** | **Data Corruption:** Mashed two transactions into one: took first amount (340), last type (`diya` $\to$ DEBIT), and date (-1). Incorrectly logged Ramesh paid ₹340 yesterday! |
| **Numbers** | `"Teen sau chaalis"` | Customer: Unknown / Prompt<br>Amount: ₹340 | `Transaction(name=Teen, amount=340, type=CREDIT, dateOffset=0)` | **FAIL (P1)** | Number word `"Teen"` recorded as customer name! |
| **Numbers** | `"340"` | Customer: Unknown / Prompt<br>Amount: ₹340 | `Transaction(name=340, amount=340, type=CREDIT, dateOffset=0)` | **FAIL (P1)** | Digits `"340"` recorded as customer name! |
| **Numbers** | `"Three hundred forty"` | Amount: ₹340 | `null` | **FAIL (P2)** | English words completely unsupported in number extractor. |
| **Numbers** | `"Three-forty"` | Amount: ₹340 | `null` | **FAIL (P2)** | Colloquial numbers unsupported. |
| **Numbers** | `"Ek hazaar do sau"` | Amount: ₹1200 | `Transaction(name=Ek, amount=1200, type=CREDIT, dateOffset=0)` | **PARTIAL** | Amount ₹1200 correct, but customer name captured as `"Ek"`. |
| **Numbers** | `"Barah sau pachaas"` | Amount: ₹1250 | `Transaction(name=Barah, amount=150, type=CREDIT, dateOffset=0)` | **FAIL (P1)** | **Math Calculation Error:** Extracted ₹150 instead of ₹1250. `"Barah"` omitted due to dictionary key spelling mismatch (`baarah`). |
| **Dates** | `"Kal Ramesh ka 100 udhaar"` | Customer: Ramesh<br>Amount: ₹100<br>Offset: -1 | `Transaction(name=Kal, amount=100, type=CREDIT, dateOffset=-1)` | **FAIL (P1)** | **Name Corruption:** First word `"Kal"` (yesterday) extracted as customer name! |
| **Dates** | `"Parso Suresh ne 200 diya"` | Customer: Suresh<br>Amount: ₹200<br>Offset: -2 | `Transaction(name=Parso, amount=200, type=DEBIT, dateOffset=-2)` | **FAIL (P1)** | First word `"Parso"` (day before yesterday) extracted as customer name! |
| **Dates** | `"Aaj Amit ka 300 jama"` | Customer: Amit<br>Amount: ₹300<br>Offset: 0 | `Transaction(name=Aaj, amount=300, type=DEBIT, dateOffset=0)` | **FAIL (P1)** | First word `"Aaj"` (today) extracted as customer name! |
| **Dates** | `"Pichhle hafte Ramesh ne 400 diya"` | Customer: Ramesh<br>Amount: ₹400<br>Offset: -7 | `Transaction(name=Pichhle, amount=400, type=DEBIT, dateOffset=0)` | **FAIL (P2)** | `"Pichhle hafte"` unrecognized (offset 0), name captured as `"Pichhle"`. |
| **Correction**| `"Nahi, 430"` | Action: Amend pending Tx amount to ₹430 | `Transaction(name=Nahi,, amount=430, type=CREDIT, dateOffset=0)` | **FAIL (P1)** | Creates a new transaction under customer name `"Nahi,"`! |
| **Queries** | `"Aaj kitna udhaar gaya?"` | Ledger Query: Total daily credit | `null` | **FAIL (P2)** | Query intents not implemented. |
| **Queries** | `"Ramesh ka total?"` | Ledger Query: Outstanding balance | `null` | **FAIL (P2)** | Query intents not implemented. |
| **Queries** | `"Aaj kitna payment mila?"` | Ledger Query: Total daily payments | `null` | **FAIL (P2)** | Query intents not implemented. |
| **Queries** | `"Sabse zyada kis customer ka baaki hai?"` | Ledger Query: Max balance inquiry | `null` | **FAIL (P2)** | Query intents not implemented. |

---

## 2. Root Cause Analysis

1. **Primitive Customer Name Heuristic**:
   ```kotlin
   // LedgerParser.kt line 106:
   val rawName = trimmed.split(" ")[0]
   ```
   Assuming the first token is always the customer name breaks every utterance starting with a date (*"Kal"*, *"Parso"*, *"Aaj"*), correction (*"Nahi"*), or amount (*"Teen"*, *"340"*).
2. **Context-Blind Direction Mapping**:
   Mapping the single token `"diya"` to `DEBIT` ignores grammatical context:
   - *"Samaan diya"* = goods given $\to$ Credit.
   - *"Paise diya"* = payment made $\to$ Debit.
3. **No Multi-Clause Parsing**:
   `LedgerParser.parseUtterance` returns a single `Transaction?`. Complex compound sentences with conjunctions (*"aur"*, *","*, *"lekin"*) are conflated into a single distorted transaction.
4. **Stateless Processing**:
   The parser maintains no conversational state or pending transaction buffer. It cannot process corrections (*"Nahi, 430"*), clarifications, or disambiguation answers.
