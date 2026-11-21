#!/usr/bin/env python3
"""
KHATAWAS Chatbot Question Intent & Entity Dataset Generator.

Generates multilingual natural language questions covering:
- Hindi (Devanagari)
- Kannada (native script)
- English
- Hinglish
- Kanglish

Question Intents:
1. GET_CUSTOMER_BALANCE
2. GET_CUSTOMER_HISTORY
3. GET_CUSTOMER_LAST_PAYMENT
4. LIST_OUTSTANDING_CUSTOMERS
5. GET_TOTAL_OUTSTANDING
6. GET_PERIOD_SUMMARY
7. LIST_CATEGORY_TRANSACTIONS
8. GET_TRANSACTIONS_BY_DATE
9. GET_TOP_DEBTOR
10. FOLLOW_UP_QUERY
11. UNKNOWN (OOD questions for graceful abstention)
"""

import json
import random
import os
import re
from typing import Dict, List, Any, Optional

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
    ("दीपक", "ದೀಪಕ್", "Deepak"),
    ("राजेश", "ರಾಜೇಶ್", "Rajesh"),
    ("शिवन्ना", "ಶಿವಣ್ಣ", "Shivanna"),
    ("ಆನಂದ್", "ಆನಂದ್", "Anand"),
    ("ದರ್ಶನ್", "ದರ್ಶನ್", "Darshan"),
    ("ಹರೀಶ್", "ಹರೀಶ್", "Harish")
]

RELATIONS = [
    ("पापा", "पिता", "ke papa", "thande", "father", "FATHER"),
    ("भाई", "भैया", "ke bhai", "sahodara", "brother", "BROTHER"),
    ("बेटा", "पुत्र", "ka beta", "maga", "son", "SON")
]

INTENTS = [
    "GET_CUSTOMER_BALANCE",
    "GET_CUSTOMER_HISTORY",
    "GET_CUSTOMER_LAST_PAYMENT",
    "LIST_OUTSTANDING_CUSTOMERS",
    "GET_TOTAL_OUTSTANDING",
    "GET_PERIOD_SUMMARY",
    "LIST_CATEGORY_TRANSACTIONS",
    "GET_TRANSACTIONS_BY_DATE",
    "GET_TOP_DEBTOR",
    "FOLLOW_UP_QUERY",
    "UNKNOWN"
]

DATE_FILTERS = ["NONE", "TODAY", "YESTERDAY", "THIS_WEEK", "THIS_MONTH", "LAST_MONTH"]
CATEGORY_FILTERS = ["NONE", "CREDIT", "DEBIT", "STUCK", "ILLEGAL"]
RELATION_FILTERS = ["NONE", "FATHER", "BROTHER", "SON", "FAMILY"]

def generate_customer_balance_questions():
    samples = []
    for hi_name, kn_name, en_name in BASE_NAMES:
        # Hindi base
        samples.append({
            "text": f"{hi_name} का बकाया कितना है?",
            "language": "hi",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": hi_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{hi_name} पर कितना पैसा बाकी है?",
            "language": "hi",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": hi_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        # Hindi with father relation
        samples.append({
            "text": f"{hi_name} के पापा का कितना बाकी है?",
            "language": "hi",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": f"{hi_name} के पापा",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        # English
        samples.append({
            "text": f"What is {en_name}'s current balance?",
            "language": "en",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"How much does {en_name} owe?",
            "language": "en",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"How much does {en_name}'s father owe?",
            "language": "en",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": f"{en_name}'s father",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        # Kannada
        samples.append({
            "text": f"{kn_name} ಅವರ ಬಾಕಿ ಎಷ್ಟು?",
            "language": "kn",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": kn_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{kn_name} ಎಷ್ಟು ಬಾಕಿ ಉಳಿಸಿಕೊಂಡಿದ್ದಾರೆ?",
            "language": "kn",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": kn_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{kn_name} ತಂದೆ ಎಷ್ಟು ಬಾಕಿ ಕೊಡಬೇಕು?",
            "language": "kn",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": f"{kn_name} ತಂದೆ",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        # Hinglish
        samples.append({
            "text": f"{en_name} ka balance kitna hai?",
            "language": "hinglish",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{en_name} ke papa ka kitna udhar baki hai?",
            "language": "hinglish",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": f"{en_name} ke papa",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        # Kanglish
        samples.append({
            "text": f"{en_name} baki eshtu ide?",
            "language": "kanglish",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{en_name} thande eshtu kodabeku?",
            "language": "kanglish",
            "intent": "GET_CUSTOMER_BALANCE",
            "customer_name": f"{en_name} thande",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
    return samples

def generate_customer_history_questions():
    samples = []
    for hi_name, kn_name, en_name in BASE_NAMES:
        samples.append({
            "text": f"{hi_name} का लेन-देन इतिहास दिखाओ",
            "language": "hi",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": hi_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{hi_name} के पापा का हिसाब दिखाओ",
            "language": "hi",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": f"{hi_name} के पापा",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"Show {en_name}'s transaction history",
            "language": "en",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"Show {en_name}'s father's transaction history",
            "language": "en",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": f"{en_name}'s father",
            "relation": "FATHER",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{kn_name} ಅವರ ವಹಿವಾಟು ಇತಿಹಾಸ ತೋರಿಸಿ",
            "language": "kn",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": kn_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{en_name} ki transaction history dikhao",
            "language": "hinglish",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{en_name} transactions thorisi",
            "language": "kanglish",
            "intent": "GET_CUSTOMER_HISTORY",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })
        # Last payment questions
        samples.append({
            "text": f"{hi_name} ने आखिरी भुगतान कब किया था?",
            "language": "hi",
            "intent": "GET_CUSTOMER_LAST_PAYMENT",
            "customer_name": hi_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "DEBIT",
            "requires_clarification": False
        })
        samples.append({
            "text": f"When was {en_name}'s last payment?",
            "language": "en",
            "intent": "GET_CUSTOMER_LAST_PAYMENT",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "DEBIT",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{kn_name} ಕೊನೆಯ ಪಾವತಿ ಯಾವಾಗ ಮಾಡಿದರು?",
            "language": "kn",
            "intent": "GET_CUSTOMER_LAST_PAYMENT",
            "customer_name": kn_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "DEBIT",
            "requires_clarification": False
        })
        samples.append({
            "text": f"{en_name} ne last payment kab kiya?",
            "language": "hinglish",
            "intent": "GET_CUSTOMER_LAST_PAYMENT",
            "customer_name": en_name,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "DEBIT",
            "requires_clarification": False
        })
    return samples

def generate_summary_and_aggregate_questions():
    samples = []
    # Total outstanding
    total_queries = [
        ("मेरा कुल बकाया कितना है?", "hi"),
        ("दुकान का कुल उधारी कितना है?", "hi"),
        ("What is my total outstanding balance?", "en"),
        ("Total outstanding amount across all customers?", "en"),
        ("ನನ್ನ ಒಟ್ಟು ಬಾಕಿ ಎಷ್ಟು?", "kn"),
        ("ಅಂಗಡಿಯ ಒಟ್ಟು ಸಾಲ ಎಷ್ಟು?", "kn"),
        ("Total outstanding balance kitna hai?", "hinglish"),
        ("Mera total udhar kitna baki hai?", "hinglish"),
        ("Total baki eshtu ide?", "kanglish"),
    ]
    for txt, lang in total_queries:
        samples.append({
            "text": txt,
            "language": lang,
            "intent": "GET_TOTAL_OUTSTANDING",
            "customer_name": None,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })

    # List outstanding customers / debtors
    debtor_queries = [
        ("किन ग्राहकों का पैसा बाकी है?", "hi"),
        ("उधारी वाले ग्राहकों की सूची दिखाओ", "hi"),
        ("हाल ही में किसने भुगतान नहीं किया?", "hi"),
        ("Which customers have outstanding balances?", "en"),
        ("Who has not paid recently?", "en"),
        ("Show all customers with unpaid balances", "en"),
        ("ಯಾವ ಗ್ರಾಹಕರ ಬಾಕಿ ಇದೆ?", "kn"),
        ("ಯಾರು ಇತ್ತೀಚೆಗೆ ಹಣ ಪಾವತಿಸಿಲ್ಲ?", "kn"),
        ("Kaun se customers ka udhar baki hai?", "hinglish"),
        ("Kis kis customer ka payment pending hai?", "hinglish"),
        ("Yava customers baki kottilla?", "kanglish"),
    ]
    for txt, lang in debtor_queries:
        samples.append({
            "text": txt,
            "language": lang,
            "intent": "LIST_OUTSTANDING_CUSTOMERS",
            "customer_name": None,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })

    # Top debtor
    top_queries = [
        ("सबसे ज्यादा बकाया किस ग्राहक का है?", "hi"),
        ("सबसे अधिक उधारी किसकी है?", "hi"),
        ("Which customer has the highest outstanding balance?", "en"),
        ("Who owes the most money?", "en"),
        ("ಯಾವ ಗ್ರಾಹಕರ ಬಾಕಿ ಅತಿ ಹೆಚ್ಚು?", "kn"),
        ("Sabse jyada udhar kiska hai?", "hinglish"),
        ("Highest baki yara hesarallide?", "kanglish"),
    ]
    for txt, lang in top_queries:
        samples.append({
            "text": txt,
            "language": lang,
            "intent": "GET_TOP_DEBTOR",
            "customer_name": None,
            "relation": "NONE",
            "date_filter": "NONE",
            "category_filter": "NONE",
            "requires_clarification": False
        })

    return samples

def generate_period_and_category_questions():
    samples = []
    # Today payments (DEBIT)
    samples.extend([
        {"text": "आज कितना पैसा मिला?", "language": "hi", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "DEBIT", "requires_clarification": False},
        {"text": "How much money did I receive today?", "language": "en", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "DEBIT", "requires_clarification": False},
        {"text": "ಇವತ್ತು ಎಷ್ಟು ಹಣ ಬಂತು?", "language": "kn", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "DEBIT", "requires_clarification": False},
        {"text": "Aaj kitna collection hua?", "language": "hinglish", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "DEBIT", "requires_clarification": False},
        {"text": "Ivattu eshtu collection aytu?", "language": "kanglish", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "DEBIT", "requires_clarification": False},
    ])
    # Month credit (CREDIT)
    samples.extend([
        {"text": "इस महीने कितना उधार दिया?", "language": "hi", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "THIS_MONTH", "category_filter": "CREDIT", "requires_clarification": False},
        {"text": "How much credit did I give this month?", "language": "en", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "THIS_MONTH", "category_filter": "CREDIT", "requires_clarification": False},
        {"text": "ಈ ತಿಂಗಳು ಎಷ್ಟು ಸಾಲ ಕೊಟ್ಟೆ?", "language": "kn", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "THIS_MONTH", "category_filter": "CREDIT", "requires_clarification": False},
        {"text": "Is mahine kitna udhar diya?", "language": "hinglish", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "THIS_MONTH", "category_filter": "CREDIT", "requires_clarification": False},
    ])
    # Today summary general
    samples.extend([
        {"text": "आज का सारांश दो", "language": "hi", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Give me a summary of today's transactions", "language": "en", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "NONE", "requires_clarification": False},
        {"text": "ಇಂದಿನ ವಹಿವಾಟುಗಳ ಸಾರಾಂಶ ನೀಡಿ", "language": "kn", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Aaj ke transactions ka summary do", "language": "hinglish", "intent": "GET_PERIOD_SUMMARY", "customer_name": None, "relation": "NONE", "date_filter": "TODAY", "category_filter": "NONE", "requires_clarification": False},
    ])
    # Stuck Category
    samples.extend([
        {"text": "सारे अटके हुए लेनदेन दिखाओ", "language": "hi", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "STUCK", "requires_clarification": False},
        {"text": "Show all stuck transactions", "language": "en", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "STUCK", "requires_clarification": False},
        {"text": "ಸಿಲುಕಿಕೊಂಡ ವಹಿವಾಟುಗಳನ್ನು ತೋರಿಸಿ", "language": "kn", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "STUCK", "requires_clarification": False},
        {"text": "Saare stuck transactions dikhao", "language": "hinglish", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "STUCK", "requires_clarification": False},
    ])
    # Illegal Category
    samples.extend([
        {"text": "सारे अवैध बैंक भुगतान दिखाओ", "language": "hi", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "ILLEGAL", "requires_clarification": False},
        {"text": "Show all illegal bank payments", "language": "en", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "ILLEGAL", "requires_clarification": False},
        {"text": "ಅಕ್ರಮ ಬ್ಯಾಂಕ್ ಪಾವತಿಗಳನ್ನು ತೋರಿಸಿ", "language": "kn", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "ILLEGAL", "requires_clarification": False},
        {"text": "Illegal bank payments dikhao", "language": "hinglish", "intent": "LIST_CATEGORY_TRANSACTIONS", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "ILLEGAL", "requires_clarification": False},
    ])
    # By Date
    samples.extend([
        {"text": "कल कौन से लेनदेन दर्ज किए गए थे?", "language": "hi", "intent": "GET_TRANSACTIONS_BY_DATE", "customer_name": None, "relation": "NONE", "date_filter": "YESTERDAY", "category_filter": "NONE", "requires_clarification": False},
        {"text": "What transactions were recorded yesterday?", "language": "en", "intent": "GET_TRANSACTIONS_BY_DATE", "customer_name": None, "relation": "NONE", "date_filter": "YESTERDAY", "category_filter": "NONE", "requires_clarification": False},
        {"text": "ನಿನ್ನೆ ಯಾವ ವಹಿವಾಟುಗಳು ದಾಖಲಾಗಿವೆ?", "language": "kn", "intent": "GET_TRANSACTIONS_BY_DATE", "customer_name": None, "relation": "NONE", "date_filter": "YESTERDAY", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Kal ke entries dikhao", "language": "hinglish", "intent": "GET_TRANSACTIONS_BY_DATE", "customer_name": None, "relation": "NONE", "date_filter": "YESTERDAY", "category_filter": "NONE", "requires_clarification": False},
    ])
    return samples

def generate_followup_and_ambiguous_questions():
    samples = []
    # Follow-ups
    samples.extend([
        {"text": "पिछले महीने का क्या?", "language": "hi", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "LAST_MONTH", "category_filter": "NONE", "requires_clarification": False},
        {"text": "What about last month?", "language": "en", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "LAST_MONTH", "category_filter": "NONE", "requires_clarification": False},
        {"text": "ಕಳೆದ ತಿಂಗಳದ್ದು ಏನು?", "language": "kn", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "LAST_MONTH", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Pichle mahine ka batao", "language": "hinglish", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "LAST_MONTH", "category_filter": "NONE", "requires_clarification": False},
        {"text": "उसके पिछले लेनदेन दिखाओ", "language": "hi", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Show his previous transactions", "language": "en", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "ಅವರ ಹಿಂದಿನ ವಹಿವಾಟುಗಳನ್ನು ತೋರಿಸಿ", "language": "kn", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "What was the last payment?", "language": "en", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "DEBIT", "requires_clarification": False},
        {"text": "Compare it with this month", "language": "en", "intent": "FOLLOW_UP_QUERY", "customer_name": None, "relation": "NONE", "date_filter": "THIS_MONTH", "category_filter": "NONE", "requires_clarification": False},
    ])
    # Ambiguous queries requiring clarification
    samples.extend([
        {"text": "उसका बैलेंस कितना है?", "language": "hi", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "उसके पैसे बताओ", "language": "hi", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "What is his balance?", "language": "en", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "How much does he owe?", "language": "en", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "ಅವರ ಬಾಕಿ ಎಷ್ಟು?", "language": "kn", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "Uska balance kitna hai?", "language": "hinglish", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "Avara baki eshtu?", "language": "kanglish", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "पापा का हिसाब बताओ", "language": "hi", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "FATHER", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
        {"text": "Show father's balance", "language": "en", "intent": "GET_CUSTOMER_BALANCE", "customer_name": None, "relation": "FATHER", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": True},
    ])
    # Out-of-Domain queries (graceful abstention)
    samples.extend([
        {"text": "आज का मौसम कैसा है?", "language": "hi", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "एक चुटकुला सुनाओ", "language": "hi", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "What is the weather today?", "language": "en", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Tell me a joke", "language": "en", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Who is the Prime Minister of India?", "language": "en", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "ಇಂದಿನ ಹವಾಮಾನ ಹೇಗಿದೆ?", "language": "kn", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "ಒಂದು ತಮಾಷೆ ಹೇಳಿ", "language": "kn", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
        {"text": "Cricket score kya hai?", "language": "hinglish", "intent": "UNKNOWN", "customer_name": None, "relation": "NONE", "date_filter": "NONE", "category_filter": "NONE", "requires_clarification": False},
    ])
    return samples

def generate_dataset():
    all_samples = []
    
    # Generate base sets
    c_bal = generate_customer_balance_questions()
    c_hist = generate_customer_history_questions()
    c_sum = generate_summary_and_aggregate_questions()
    c_per = generate_period_and_category_questions()
    c_fol = generate_followup_and_ambiguous_questions()
    
    base_pool = c_bal + c_hist + c_sum + c_per + c_fol
    print(f"Base unique template pool: {len(base_pool)}")
    
    # Multiply and augment to 3,000 samples with minor stylistic variations
    augmented = []
    prefixes_hi = ["कृपया ", "अरे ", "जरा ", "बताओ ", ""]
    prefixes_en = ["Please ", "Can you tell me ", "Hey ", "Assistant, ", ""]
    prefixes_kn = ["ದಯವಿಟ್ಟು ", "ಹೇಳಿ ", ""]
    
    for item in base_pool:
        augmented.append(item)
        lang = item["language"]
        pref = prefixes_hi if lang in ["hi", "hinglish"] else prefixes_kn if lang in ["kn", "kanglish"] else prefixes_en
        for p in pref:
            if not p:
                continue
            new_item = dict(item)
            new_item["text"] = p + item["text"]
            augmented.append(new_item)
            
    # Duplicate with slight shuffle to reach target size
    while len(all_samples) < 3500:
        for it in augmented:
            if len(all_samples) >= 3500:
                break
            all_samples.append(dict(it))
            
    random.shuffle(all_samples)
    
    train_split = all_samples[:2500]
    val_split = all_samples[2500:3000]
    test_split = all_samples[3000:3500]
    
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "data"))
    os.makedirs(base_dir, exist_ok=True)
    
    for split_name, data in [("chatbot_train.jsonl", train_split), ("chatbot_val.jsonl", val_split), ("chatbot_test.jsonl", test_split)]:
        path = os.path.join(base_dir, split_name)
        with open(path, "w", encoding="utf-8") as f:
            for item in data:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")
        print(f"Wrote {len(data)} items to {path}")

if __name__ == "__main__":
    generate_dataset()
