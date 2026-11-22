# Confusing-sentence test list

Sentences with mixed languages, hidden negatives, ambiguous names and awkward amounts. Type them into the text box (the same parser runs as for speech) or say them with the mic.

**Setup for the same-name tests:** add these customers first: `Ramesh Kumar`, `Ramesh Gupta`, `Rahul Sharma`, `Rahul Doodhwala`, `Sunita`, `Imran`, `Geeta`.

**Status key:** ✅ checked by running the real parser and ledger (Qwen off, which is how the live site runs). Most are also locked in as tests in `test/confusing.test.js`. ⚠️ is a known limit, listed honestly in the last section.

## 1. Amounts spoken in awkward ways

| Say | Expected | |
|---|---|---|
| Imran ka sava sau udhaar | credit 125 | ✅ (was 100 before the fix) |
| Imran ka paune do sau udhaar | credit 175 | ✅ (was 200) |
| Imran ka dedh hazaar udhaar | credit 1500 | ✅ |
| Imran ka dhai hazaar udhaar | credit 2500 | ✅ |
| Imran ne 2.5 hazaar diye | payment 2500 | ✅ (was a 2 and a 5000) |
| Imran ne 250.50 rupaye diye | payment 250, one entry | ✅ |
| Imran ka 1 lakh udhaar | credit 100000 | ✅ |
| Imran ne 2,000 diye | payment 2000 | ✅ |
| Imran ka 5 sau udhaar | credit 500 | ✅ |
| Imran ka ek sau pachaas udhaar | credit 150 | ✅ |
| Sunita ka 0 udhaar | nothing booked, asks to repeat | ✅ |
| इमरान का ३०० उधार (Devanagari digits) | credit 300 | ✅ |

## 2. Hidden negatives ("do NOT write this")

| Say | Expected | |
|---|---|---|
| Sunita ko 500 rupaye udhaar mat dena | nothing booked, "nothing was written" | ✅ (was booked as credit) |
| don't add 500 for Sunita | nothing booked | ✅ |
| never write 500 for Sunita | nothing booked | ✅ |
| Imran ka 300 udhaar do hazaar nahi | only 300 booked, the "not 2000" is ignored | ✅ (was changed to 2000) |

## 3. Self-corrections in the middle of a sentence

| Say | Expected | |
|---|---|---|
| Imran ka udhaar 300 nahi 350 | one credit of 350 | ✅ |
| Imran ne paanch sau diye, nahi chaar sau | payment 500, then corrected to 400 | ✅ |
| Imran ne paanch sau diye the lekin abhi sirf teen sau diye | one payment of 300 (the later amount wins) | ✅ |
| nahi, doosra Rahul | moves the last entry to the other Rahul | ✅ |

## 4. Two things in one breath

| Say | Expected | |
|---|---|---|
| Imran ko do sau udhaar aur Geeta ne sau rupaye diye | credit 200 Imran, payment 100 Geeta | ✅ |
| Imran ne do sau pachaas diye aur teen sau udhaar liya | payment 250, credit 300 | ✅ |
| Sunita ne kal do sau aur aaj teen sau diye | two payments, yesterday and today | ✅ (first was a "udhaar ya jama?" question) |
| Geeta paid 300 yesterday and took 200 today | payment 300, credit 200, both Geeta | ✅ (was creating a customer "Took") |
| Aaj kitna udhaar gaya aur kitna aaya | two spoken answers | ✅ |

## 5. Same name, different people

| Say | Expected | |
|---|---|---|
| Rahul ka 200 udhaar | asks "Kaun sa Rahul? Sharma ya Doodhwala?" | ✅ |
| Rahul Doodhwala ka 200 udhaar | books to Rahul Doodhwala | ✅ |
| Doodhwala ka 200 udhaar | books to Rahul Doodhwala | ✅ (was creating a new customer "Doodhwala") |
| Ramesh ne 500 diye (nobody mentioned before) | asks "Kaun sa Ramesh? Ramesh Kumar ya Ramesh Gupta?" | ✅ |
| Ramesh Kumar ka 300 udhaar, then Ramesh ne 500 diye | picks Ramesh Kumar (just in the conversation) and says so | ✅ by design |
| (then) nahi, doosra Ramesh | moves that entry to Ramesh Gupta | ✅ (needs an auto-pick first; with a pending question, answer it by name instead) |
| रमेश ने पांच सौ दिए | same, in Devanagari | ✅ |
| Mehta ka 300 udhaar | creates a new customer Mehta | ✅ |

## 6. Pronouns and filler words

| Say | Expected | |
|---|---|---|
| Sunita owes me nothing, she paid 500 | payment 500 on Sunita | ✅ (was creating a customer "She") |
| add three hundred to Geeta's account | credit 300 Geeta | ✅ |
| Geeta ke khate me teen sau ka atta chadha do | credit 300 Geeta, by the rules | ✅ (needed Qwen before, which the live site does not have) |
| Imran Imran Imran 300 | asks "udhaar ya jama?" | ✅ |
| hello kaise ho | "Samajh nahi aaya", nothing booked | ✅ |

## 7. Dates

| Say | Expected | |
|---|---|---|
| Imran ne kal teen sau udhaar liya tha | credit 300 dated yesterday | ✅ |
| parso Imran ne sau diye | payment 100 dated two days ago | ✅ (was creating a customer "O Imran") |

## 8. Questions

| Say | Expected | |
|---|---|---|
| Imran ka hisaab batao | Imran's balance | ✅ |
| Ramesh Kumar ka total kitna hua | Ramesh Kumar's balance | ✅ |
| sab ka total kitna baaki hai | grand total | ✅ |
| how much does Sunita owe | Sunita's balance in English | ✅ |

## 9. Other languages (typed or spoken)

| Language | Say | Expected | |
|---|---|---|---|
| Kannada | ಇಮ್ರಾನ್ ಗೆ 500 ರೂಪಾಯಿ ಸಾಲ | credit 500 Imran | ✅ |
| Kannada | ಸುನೀತಾ 300 ರೂಪಾಯಿ ಕೊಟ್ಟರು | payment 300 Sunita | ✅ |
| Marathi | इमरानला 500 रुपये उधार | credit 500 Imran | ✅ |
| Marathi | इमरान किती देणं आहे | Imran's balance | ✅ |
| Tamil | இம்ரான் 500 ரூபாய் கடன் | credit 500 Imran | ✅ (was creating a second customer "Imra") |
| Malayalam | ഇമ്രാൻ 500 രൂപ കടം | credit 500 Imran | ✅ (was "Imra") |
| Telugu, Gujarati, Bengali, Punjabi | credit sentences | credit 500 Imran | ✅ |

## 10. Known limits (not fixed, be ready to say so)

| Say | What happens | Why |
|---|---|---|
| ek sau (a number alone, no name) | rewrites the amount of the last entry | Deliberate: Prisma often drops the leading "nahi". A stray number from noise could change the last entry |
| pichla entry delete karo / undo | "Samajh nahi aaya" | There is no undo or delete command yet. Use "nahi, <amount>" to correct |
| Imran ka paisa wapas karo | "Samajh nahi aaya" | "Return the money" can mean either direction, so it is not guessed |
| Imran ko teen sau udhaar diya aur wapas le liya | books the 300 credit only | "Took it back" is not understood as a cancel |
| kya Imran ne paisa diya? (a yes/no question) | "Samajh nahi aaya" | Safe: nothing is booked |
| Imran (name only) | "Samajh nahi aaya" | Could answer with the balance |
| Same sentence said twice | booked twice | A repeat can be a real second sale, so it is not blocked |
| Payment larger than the balance | balance goes negative (shown as advance) | No warning yet |
| Spoken number words in Tamil, Telugu, Gujarati, Bengali, Malayalam, Punjabi | not understood | Prisma returns digits for these, so it only matters for typed text |
| Qwen fallback on the live site | not available | The Render image has no Ollama. Rules only |
