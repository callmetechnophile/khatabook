#!/usr/bin/env python3
"""
KHATAWAS Multilingual Dataset Generator for Financial Transaction Understanding.

Generates structured synthetic and template-augmented utterances across:
- Hindi (Devanagari)
- Kannada (Kannada script)
- English
- Hinglish (Hindi in Latin script / mixed)
- Kanglish (Kannada in Latin script / mixed)

Labels:
- Customer Reference & Base Name
- Relationship (FATHER, MOTHER, BROTHER, SISTER, SON, DAUGHTER, UNCLE)
- Amount in integer paise
- Direction (CREDIT / DEBIT)
- Category (CREDIT, DEBIT, STUCK, ILLEGAL)
- Clarification requirement (Ambiguity detection)
- Token-level BIO tags for entity extraction
"""

import json
import random
import os
import re
from typing import Dict, List, Any, Optional, Tuple

SEED = 42
random.seed(SEED)

BASE_NAMES = [
    ("रवि", "ರವಿ", "Ravi"),
    ("सुरेश", "ಸುರೇಶ್", "Suresh"),
    ("रमेश", "ರಮೇಶ್", "Ramesh"),
    ("प्रिया", "ಪ್ರಿಯಾ", "Priya"),
    ("अनीता", "ಅನಿತಾ", "Anitha"),
    ("मंजುನಾಥ್", "ಮಂಜುನಾಥ್", "Manjunath"),
    ("वेंकटेश", "ವೆಂಕಟೇಶ್", "Venkatesh"),
    ("लक्ष्मी", "ಲಕ್ಷ್ಮಿ", "Lakshmi"),
    ("कुमार", "ಕುಮಾರ್", "Kumar"),
    ("राहुल", "ರಾಹುಲ್", "Rahul"),
    ("अमित", "ಅಮಿತ್", "Amit"),
    ("पूजा", "ಪೂಜಾ", "Pooja"),
    ("सुनीता", "ಸುನೀತಾ", "Sunita"),
    ("दीपक", "ದೀಪಕ್", "Deepak"),
    ("राजेश", "ರಾಜೇಶ್", "Rajesh"),
    ("शिवन्ना", "ಶಿವಣ್ಣ", "Shivanna"),
    ("ನಿಂಗಪ್ಪ", "ನಿಂಗಪ್ಪ", "Ningappa"),
    ("ಬಸವರಾಜ್", "ಬಸವರಾಜ್", "Basavaraj"),
    ("ಗಣೇಶ್", "ಗಣೇಶ್", "Ganesh"),
    ("ಆನಂದ್", "ಆನಂದ್", "Anand"),
    ("ಕಾವ್ಯ", "ಕಾವ್ಯ", "Kavya"),
    ("ಚೇತನ್", "ಚೇತನ್", "Chetan"),
    ("ದರ್ಶನ್", "ದರ್ಶನ್", "Darshan"),
    ("ಹರೀಶ್", "ಹರೀಶ್", "Harish"),
    ("ಮಹೇಶ್", "ಮಹೇಶ್", "Mahesh"),
    ("ನವೀನ್", "ನವೀನ್", "Naveen"),
    ("ಸಂತೋಷ್", "ಸಂತೋಷ್", "Santosh"),
    ("ಪ್ರವೀಣ್", "ಪ್ರವೀಣ್", "Praveen"),
    ("ವಿನೋದ್", "ವಿನೋದ್", "Vinod"),
    ("ಸುನಿಲ್", "ಸುನಿಲ್", "Sunil")
]

# Relationships: (rel_enum, hi_suffix, kn_suffix, hinglish_suffix, kanglish_suffix, en_suffix)
RELATIONSHIPS = [
    ("FATHER", "के पापा", "ತಂದೆ", "ke papa", "thande", "'s father"),
    ("FATHER", "के पिताजी", "ಅಪ್ಪ", "ke pitaji", "appa", "'s dad"),
    ("MOTHER", "की माँ", "ತಾಯಿ", "ki maa", "taayi", "'s mother"),
    ("MOTHER", "की मम्मी", "ಅಮ್ಮ", "ki mummy", "amma", "'s mom"),
    ("BROTHER", "का भाई", "ಅಣ್ಣ", "ka bhai", "anna", "'s elder brother"),
    ("BROTHER", "का छोटा भाई", "ತಮ್ಮ", "ka chhota bhai", "thamma", "'s younger brother"),
    ("SISTER", "की बहन", "ಅಕ್ಕ", "ki behen", "akka", "'s elder sister"),
    ("SISTER", "की छोटी बहन", "ತಂಗಿ", "ki chhoti behen", "thangi", "'s younger sister"),
    ("SON", "का बेटा", "ಮಗ", "ka beta", "maga", "'s son"),
    ("DAUGHTER", "की बेटी", "ಮಗಳು", "ki beti", "magalu", "'s daughter"),
    ("UNCLE", "के चाचा", "ಚಿಕ್ಕಪ್ಪ", "ke chacha", "chikkappa", "'s uncle")
]

# Currency amount templates (amount_rs, amount_paise, hi_words, kn_words, hinglish_words, kanglish_words, en_words)
AMOUNTS = [
    (10, 1000, "दस रुपये", "ಹತ್ತು ರೂಪಾಯಿ", "dus rupaye", "hattu rupaayi", "10 rupees"),
    (20, 2000, "बीस रुपये", "ಇಪ್ಪತ್ತು ರೂಪಾಯಿ", "bees rupaye", "ippattu rupaayi", "20 rupees"),
    (50, 5000, "पचास रुपये", "ಐವತ್ತು ರೂಪಾಯಿ", "pachaas rupaye", "aivattu rupaayi", "50 rupees"),
    (75, 7500, "पचहत्तर रुपये", "ಎಪ್ಪತ್ತೈದು ರೂಪಾಯಿ", "pachhattar rupaye", "eppattaidu rupaayi", "75 rupees"),
    (100, 10000, "सौ रुपये", "ನೂರು ರೂಪಾಯಿ", "sau rupaye", "nooru rupaayi", "100 rupees"),
    (150, 15000, "डेढ़ सौ रुपये", "ಒಂದೂವರೆ ನೂರು ರೂಪಾಯಿ", "dedh sau rupaye", "ondoovare nooru rupaayi", "150 rupees"),
    (200, 20000, "दो सौ रुपये", "ಇನ್ನೂರು ರೂಪಾಯಿ", "do sau rupaye", "innooru rupaayi", "200 rupees"),
    (250, 25000, "ढाई सौ रुपये", "ಎರಡೂವರೆ ನೂರು ರೂಪಾಯಿ", "dhai sau rupaye", "eradoovare nooru rupaayi", "250 rupees"),
    (300, 30000, "तीन सौ रुपये", "ಮುನ್ನೂರು ರೂಪಾಯಿ", "teen sau rupaye", "munnooru rupaayi", "300 rupees"),
    (500, 50000, "पांच सौ रुपये", "ಐನೂರು ರೂಪಾಯಿ", "paanch sau rupaye", "ainooru rupaayi", "500 rupees"),
    (750, 75000, "साढ़े सात सौ रुपये", "ಏಳೂವರೆ ನೂರು ರೂಪಾಯಿ", "saadhe saat sau rupaye", "yeloovare nooru rupaayi", "750 rupees"),
    (1000, 100000, "एक हजार रुपये", "ಒಂದು ಸಾವಿರ ರೂಪಾಯಿ", "ek hazaar rupaye", "ondu saavira rupaayi", "1000 rupees"),
    (1250, 125000, "बारह सौ पचास रुपये", "ಒಂದು ಸಾವಿರದ ಇನ್ನೂರೈವತ್ತು ರೂಪಾಯಿ", "baarah sau pachaas rupaye", "ondu saavirada innooraivattu rupaayi", "1250 rupees"),
    (2000, 200000, "दो हजार रुपये", "ಎರಡು ಸಾವಿರ ರೂಪಾಯಿ", "do hazaar rupaye", "eradu saavira rupaayi", "2000 rupees"),
    (3500, 350000, "साढ़े तीन हजार रुपये", "ಮೂರೂವರೆ ಸಾವಿರ ರೂಪಾಯಿ", "saadhe teen hazaar rupaye", "mooroovare saavira rupaayi", "3500 rupees"),
    (5000, 500000, "पांच हजार रुपये", "ಐದು ಸಾವಿರ ರೂಪಾಯಿ", "paanch hazaar rupaye", "aidu saavira rupaayi", "5000 rupees"),
    (10000, 1000000, "दस हजार रुपये", "ಹತ್ತು ಸಾವಿರ ರೂಪಾಯಿ", "dus hazaar rupaye", "hattu saavira rupaayi", "10000 rupees"),
    # Paise fractional amounts
    (10.50, 1050, "दस रुपये पचास पैसे", "ಹತ್ತು ರೂಪಾಯಿ ಐವತ್ತು ಪೈಸೆ", "10 rupaye 50 paise", "10 rupaayi 50 paise", "10 rupees 50 paise"),
    (50.25, 5025, "पचास रुपये पच्चीस पैसे", "ಐವತ್ತು ರೂಪಾಯಿ ಇಪ್ಪತ್ತೈದು ಪೈಸೆ", "50 rupaye 25 paise", "50 rupaayi 25 paise", "50 rupees 25 paise"),
    (99.50, 9950, "निन्यानवे रुपये पचास पैसे", "ತೊಂಬತ್ತೊಂಬತ್ತು ರೂಪಾಯಿ ಐವತ್ತು ಪೈಸೆ", "99 rupaye 50 paise", "99 rupaayi 50 paise", "99 rupees 50 paise"),
    (500.75, 50075, "पांच सौ रुपये पचहत्तर पैसे", "ಐನೂರು ರೂಪಾಯಿ ಎಪ್ಪತ್ತೈದು ಪೈಸೆ", "500 rupaye 75 paise", "500 rupaayi 75 paise", "500 rupees 75 paise"),
]

def generate_sample(sample_id: int, split_type: str = "train") -> Dict[str, Any]:
    """Generate a single labelled training/eval sample."""
    lang = random.choice(["hi", "kn", "en", "hinglish", "kanglish"])
    cat = random.choices(["CREDIT", "DEBIT", "STUCK", "ILLEGAL", "AMBIGUOUS"], weights=[0.40, 0.40, 0.08, 0.04, 0.08])[0]
    
    # Select customer
    base_hi, base_kn, base_en = random.choice(BASE_NAMES)
    has_rel = random.random() < 0.35
    rel_enum = None
    rel_suffix_hi = rel_suffix_kn = rel_suffix_hinglish = rel_suffix_kanglish = rel_suffix_en = ""
    
    if has_rel:
        rel_enum, rel_suffix_hi, rel_suffix_kn, rel_suffix_hinglish, rel_suffix_kanglish, rel_suffix_en = random.choice(RELATIONSHIPS)
    
    # Pick amount
    amt_item = random.choice(AMOUNTS)
    amt_rs, amt_paise, amt_hi_w, amt_kn_w, amt_hinglish_w, amt_kanglish_w, amt_en_w = amt_item
    # Randomly use digit or word representation
    use_digits = random.random() < 0.65
    
    requires_clarification = False
    clarification_reason = None
    
    # Setup by Category
    if cat == "AMBIGUOUS":
        amb_kind = random.choice(["MISSING_CUSTOMER", "MISSING_AMOUNT", "AMBIGUOUS_REF"])
        requires_clarification = True
        clarification_reason = amb_kind
        
        if amb_kind == "MISSING_CUSTOMER":
            amt_display = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else str(amt_rs)
            if lang == "hi":
                text = random.choice([f"{amt_display} रुपये जमा करो", f"{amt_display} रुपये उधार लिखो", f"खाते में {amt_display} डाल दो"])
            elif lang == "kn":
                text = random.choice([f"{amt_display} ರೂಪಾಯಿ ಜಮಾ ಮಾಡಿ", f"{amt_display} ರೂಪಾಯಿ ಸಾಲ ಬರೆ", f"{amt_display} ರೂಪಾಯಿ ಕೊಡು"])
            elif lang == "hinglish":
                text = random.choice([f"{amt_display} rupaye jama karo", f"{amt_display} rupaye udhar likho", f"account me {amt_display} daalo"])
            elif lang == "kanglish":
                text = random.choice([f"{amt_display} rupaayi jama maadi", f"{amt_display} rupaayi saala bare"])
            else:
                text = random.choice([f"Add {amt_display} rupees credit", f"Record payment of {amt_display} rupees"])
            
            return {
                "id": f"khata_{sample_id:06d}",
                "text": text,
                "language": lang,
                "customer_ref": None,
                "base_name": None,
                "relationship": None,
                "amount_paise": amt_paise,
                "amount_str": f"{amt_rs}",
                "direction": "CREDIT" if "उधार" in text or "credit" in text or "ಸಾಲ" in text else "DEBIT",
                "category": "CREDIT" if "उधार" in text or "credit" in text or "ಸಾಲ" in text else "DEBIT",
                "intent": "CLARIFICATION_NEEDED",
                "requires_clarification": True,
                "clarification_reason": "MISSING_CUSTOMER"
            }
            
        elif amb_kind == "MISSING_AMOUNT":
            cust_name = f"{base_hi} {rel_suffix_hi}".strip() if lang == "hi" else \
                        f"{base_kn} {rel_suffix_kn}".strip() if lang == "kn" else \
                        f"{base_en} {rel_suffix_hinglish}".strip() if lang == "hinglish" else \
                        f"{base_en} {rel_suffix_kanglish}".strip() if lang == "kanglish" else \
                        f"{base_en}{rel_suffix_en}".strip()
            
            if lang == "hi":
                text = random.choice([f"{cust_name} के खाते में लिखो", f"{cust_name} ने पैसे दिए", f"{cust_name} को उधार दिया"])
            elif lang == "kn":
                text = random.choice([f"{cust_name} ಖಾತೆಗೆ ಬರೆ", f"{cust_name} ದುಡ್ಡು ಕೊಟ್ಟ", f"{cust_name}ಗೆ ಸಾಲ ಕೊಡು"])
            elif lang == "hinglish":
                text = random.choice([f"{cust_name} ke khate me likho", f"{cust_name} ne paise diye", f"{cust_name} ko udhar diya"])
            elif lang == "kanglish":
                text = random.choice([f"{cust_name} accountge bare", f"{cust_name} duddu kotta"])
            else:
                text = random.choice([f"Record credit for {cust_name}", f"{cust_name} paid money"])
                
            return {
                "id": f"khata_{sample_id:06d}",
                "text": text,
                "language": lang,
                "customer_ref": cust_name,
                "base_name": base_en,
                "relationship": rel_enum,
                "amount_paise": None,
                "amount_str": None,
                "direction": "CREDIT" if "उधार" in text or "credit" in text or "ಸಾಲ" in text else "DEBIT",
                "category": "CREDIT" if "उधार" in text or "credit" in text or "ಸಾಲ" in text else "DEBIT",
                "intent": "CLARIFICATION_NEEDED",
                "requires_clarification": True,
                "clarification_reason": "MISSING_AMOUNT"
            }
            
        else: # AMBIGUOUS_REF (pronouns without name)
            amt_display = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else str(amt_rs)
            if lang == "hi":
                text = random.choice([f"उसके पापा को {amt_display} रुपये दिए", f"उनके भाई से {amt_display} मिले", f"उसके खाते में {amt_display} डालो"])
            elif lang == "kn":
                text = random.choice([f"ಅವರ ತಂದೆಗೆ {amt_display} ರೂಪಾಯಿ ಕೊಟ್ಟೆ", f"ಅವನ ಅಣ್ಣನಿಂದ {amt_display} ಬಂತು"])
            elif lang == "hinglish":
                text = random.choice([f"uske papa ko {amt_display} rupaye diye", f"unke bhai se {amt_display} mila"])
            elif lang == "kanglish":
                text = random.choice([f"avara thandege {amt_display} rupaayi kotte", f"avana annaninge {amt_display} bantu"])
            else:
                text = random.choice([f"Gave his father {amt_display} rupees", f"Received {amt_display} from their brother"])
                
            return {
                "id": f"khata_{sample_id:06d}",
                "text": text,
                "language": lang,
                "customer_ref": None,
                "base_name": None,
                "relationship": "FATHER" if "पापा" in text or "father" in text or "ತಂದೆ" in text else "BROTHER",
                "amount_paise": amt_paise,
                "amount_str": f"{amt_rs}",
                "direction": "CREDIT" if "दिए" in text or "gave" in text or "ಕೊಟ್ಟೆ" in text else "DEBIT",
                "category": "CREDIT" if "दिए" in text or "gave" in text or "ಕೊಟ್ಟೆ" in text else "DEBIT",
                "intent": "CLARIFICATION_NEEDED",
                "requires_clarification": True,
                "clarification_reason": "AMBIGUOUS_REFERENCE"
            }

    # Standard Categories: CREDIT, DEBIT, STUCK, ILLEGAL
    if lang == "hi":
        cust_ref = f"{base_hi} {rel_suffix_hi}".strip() if has_rel else base_hi
        amt_str = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else (f"{amt_rs:.2f}" if use_digits else amt_hi_w)
        
        if cat == "CREDIT":
            templates = [
                f"{cust_ref} को {amt_str} रुपये उधार दिए",
                f"{cust_ref} के खाते में {amt_str} रुपये लिखो",
                f"{cust_ref} को {amt_str} का सामान दिया बाकी",
                f"{cust_ref} के नाम पे {amt_str} रुपये बाकी लिख दो",
                f"{cust_ref} ने {amt_str} रुपये का उधार लिया"
            ]
            intent = "RECORD_CREDIT"
            direction = "CREDIT"
        elif cat == "DEBIT":
            templates = [
                f"{cust_ref} ने {amt_str} रुपये जमा किए",
                f"{cust_ref} से {amt_str} रुपये नकद मिले",
                f"{cust_ref} का {amt_str} रुपये भुगतान आया",
                f"{cust_ref} से {amt_str} रुपये वसूल हुए",
                f"{cust_ref} ने {amt_str} रुपये वापस दिए"
            ]
            intent = "RECORD_PAYMENT"
            direction = "DEBIT"
        elif cat == "STUCK":
            templates = [
                f"{cust_ref} का {amt_str} रुपये का पेमेंट अटक गया",
                f"{cust_ref} के {amt_str} रुपये फंस गए हैं बैंक में",
                f"{cust_ref} का {amt_str} का यूपीआई ट्रांसफर अटका हुआ है"
            ]
            intent = "DISPUTE_STUCK"
            direction = "CREDIT"
        else: # ILLEGAL
            templates = [
                f"{cust_ref} का सट्टा {amt_str} रुपये लिखो",
                f"{cust_ref} को {amt_str} का गांजा दिया",
                f"{cust_ref} का मटका जुआ {amt_str} रुपये"
            ]
            intent = "ILLEGAL_FLAG"
            direction = "CREDIT"
            
    elif lang == "kn":
        cust_ref = f"{base_kn} {rel_suffix_kn}".strip() if has_rel else base_kn
        amt_str = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else (f"{amt_rs:.2f}" if use_digits else amt_kn_w)
        
        if cat == "CREDIT":
            templates = [
                f"{cust_ref}ಗೆ {amt_str} ರೂಪಾಯಿ ಸಾಲ ಕೊಟ್ಟೆ",
                f"{cust_ref} ಖಾತೆಗೆ {amt_str} ರೂಪಾಯಿ ಬರೆ",
                f"{cust_ref}ಗೆ {amt_str} ರೂಪಾಯಿ ಬಾಕಿ ಇದೆ",
                f"{cust_ref} {amt_str} ರೂಪಾಯಿ ಉದ್ರಿ ತೆಗೆದುಕೊಂಡ"
            ]
            intent = "RECORD_CREDIT"
            direction = "CREDIT"
        elif cat == "DEBIT":
            templates = [
                f"{cust_ref}ಯಿಂದ {amt_str} ರೂಪಾಯಿ ಬಂತು",
                f"{cust_ref} {amt_str} ರೂಪಾಯಿ ಜಮಾ ಮಾಡಿದ",
                f"{cust_ref} {amt_str} ರೂಪಾಯಿ ಕೊಟ್ಟಿದ್ದಾನೆ",
                f"{cust_ref}ಯಿಂದ {amt_str} ರೂಪಾಯಿ ವಸೂಲಿ ಆಯಿತು"
            ]
            intent = "RECORD_PAYMENT"
            direction = "DEBIT"
        elif cat == "STUCK":
            templates = [
                f"{cust_ref} ಅವರ {amt_str} ರೂಪಾಯಿ ಸಿಕ್ಕಿಹಾಕಿಕೊಂಡಿದೆ",
                f"{cust_ref} ಯುಪಿಐ ಪಾವತಿ {amt_str} ಸಿಲುಕಿದೆ"
            ]
            intent = "DISPUTE_STUCK"
            direction = "CREDIT"
        else: # ILLEGAL
            templates = [
                f"{cust_ref} ಮಟ್ಕಾ ಜೂಜು {amt_str} ರೂಪಾಯಿ",
                f"{cust_ref} ಬೆಟ್ಟಿಂಗ್ {amt_str} ರೂಪಾಯಿ ಬರೆ"
            ]
            intent = "ILLEGAL_FLAG"
            direction = "CREDIT"

    elif lang == "hinglish":
        cust_ref = f"{base_en} {rel_suffix_hinglish}".strip() if has_rel else base_en
        amt_str = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else (f"{amt_rs:.2f}" if use_digits else amt_hinglish_w)
        
        if cat == "CREDIT":
            templates = [
                f"{cust_ref} ko {amt_str} rupaye udhar diya",
                f"{cust_ref} ke account me {amt_str} likho",
                f"{cust_ref} ke naam pe {amt_str} baki daalo",
                f"{cust_ref} ne {amt_str} ka samaan udhar liya"
            ]
            intent = "RECORD_CREDIT"
            direction = "CREDIT"
        elif cat == "DEBIT":
            templates = [
                f"{cust_ref} ne {amt_str} rupaye diye",
                f"{cust_ref} se {amt_str} mila cash",
                f"{cust_ref} ka {amt_str} payment jama hua",
                f"{cust_ref} se {amt_str} vasool hua"
            ]
            intent = "RECORD_PAYMENT"
            direction = "DEBIT"
        elif cat == "STUCK":
            templates = [
                f"{cust_ref} ka {amt_str} fas gaya hai",
                f"{cust_ref} ka payment {amt_str} atak gaya"
            ]
            intent = "DISPUTE_STUCK"
            direction = "CREDIT"
        else: # ILLEGAL
            templates = [
                f"{cust_ref} ka satta {amt_str} likho",
                f"{cust_ref} betting debt {amt_str} rupaye"
            ]
            intent = "ILLEGAL_FLAG"
            direction = "CREDIT"

    elif lang == "kanglish":
        cust_ref = f"{base_en} {rel_suffix_kanglish}".strip() if has_rel else base_en
        amt_str = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else (f"{amt_rs:.2f}" if use_digits else amt_kanglish_w)
        
        if cat == "CREDIT":
            templates = [
                f"{cust_ref}ge {amt_str} rupaayi saala kotte",
                f"{cust_ref} accountge {amt_str} bare",
                f"{cust_ref}ge {amt_str} baaki ide"
            ]
            intent = "RECORD_CREDIT"
            direction = "CREDIT"
        elif cat == "DEBIT":
            templates = [
                f"{cust_ref}yinda {amt_str} rupaayi bantu",
                f"{cust_ref} {amt_str} rupaayi jama maadida",
                f"{cust_ref} {amt_str} cash kottiddaane"
            ]
            intent = "RECORD_PAYMENT"
            direction = "DEBIT"
        elif cat == "STUCK":
            templates = [
                f"{cust_ref} payment {amt_str} stuck aagide",
                f"{cust_ref} {amt_str} sikkihakikondide"
            ]
            intent = "DISPUTE_STUCK"
            direction = "CREDIT"
        else: # ILLEGAL
            templates = [
                f"{cust_ref} matka {amt_str} rupaayi",
                f"{cust_ref} gambling {amt_str} bare"
            ]
            intent = "ILLEGAL_FLAG"
            direction = "CREDIT"

    else: # en
        cust_ref = f"{base_en}{rel_suffix_en}" if has_rel else base_en
        amt_str = f"{int(amt_rs)}" if use_digits and amt_rs == int(amt_rs) else (f"{amt_rs:.2f}" if use_digits else amt_en_w)
        
        if cat == "CREDIT":
            templates = [
                f"Give {cust_ref} {amt_str} rupees credit",
                f"Added {amt_str} rupees to {cust_ref}'s debt",
                f"Recorded {amt_str} credit for {cust_ref}",
                f"{cust_ref} took {amt_str} rupees on credit"
            ]
            intent = "RECORD_CREDIT"
            direction = "CREDIT"
        elif cat == "DEBIT":
            templates = [
                f"{cust_ref} paid {amt_str} rupees",
                f"Received {amt_str} rupees from {cust_ref}",
                f"Recorded payment of {amt_str} from {cust_ref}",
                f"{cust_ref} settled {amt_str} rupees"
            ]
            intent = "RECORD_PAYMENT"
            direction = "DEBIT"
        elif cat == "STUCK":
            templates = [
                f"{cust_ref}'s payment of {amt_str} rupees is stuck",
                f"Transfer from {cust_ref} of {amt_str} failed and is pending"
            ]
            intent = "DISPUTE_STUCK"
            direction = "CREDIT"
        else: # ILLEGAL
            templates = [
                f"Record {amt_str} illegal gambling debt for {cust_ref}",
                f"{cust_ref} betting {amt_str} rupees"
            ]
            intent = "ILLEGAL_FLAG"
            direction = "CREDIT"

    text = random.choice(templates)
    
    return {
        "id": f"khata_{sample_id:06d}",
        "text": text,
        "language": lang,
        "customer_ref": cust_ref,
        "base_name": base_en,
        "relationship": rel_enum,
        "amount_paise": amt_paise,
        "amount_str": f"{amt_rs}",
        "direction": direction,
        "category": cat,
        "intent": intent,
        "requires_clarification": False,
        "clarification_reason": None
    }


def main():
    print("Generating KHATAWAS multilingual financial dataset...")
    output_dir = os.path.join(os.path.dirname(__file__), "..", "data")
    os.makedirs(output_dir, exist_ok=True)
    
    num_train = 5000
    num_val = 1000
    num_test = 1000
    num_ood = 500
    
    splits = [
        ("train.jsonl", num_train, "train"),
        ("val.jsonl", num_val, "val"),
        ("test.jsonl", num_test, "test"),
        ("ood_test.jsonl", num_ood, "ood")
    ]
    
    sample_id = 1
    for filename, count, split_name in splits:
        filepath = os.path.join(output_dir, filename)
        with open(filepath, "w", encoding="utf-8") as f:
            for _ in range(count):
                sample = generate_sample(sample_id, split_name)
                f.write(json.dumps(sample, ensure_ascii=False) + "\n")
                sample_id += 1
        print(f"Generated {count} records into {filepath}")

if __name__ == "__main__":
    main()
