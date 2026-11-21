/*
 * Copyright (c) 2026 KHATAWAS Project
 * Rust-first ledger computations: parsing, entity recognition, pronoun guard, and integer paise math.
 */

fn escape_json(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(c),
        }
    }
    out
}

#[derive(Debug, Clone, PartialEq)]
pub enum TransactionDirection {
    Credit,
    Payment,
    Reversal,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ParsedTransaction {
    pub customer_name: String,
    pub amount_paise: i64,
    pub tx_type: String, // "CREDIT", "PAYMENT", "REVERSAL"
    pub date_offset: i32,
    pub event_id: String,
    pub note: String,
}

impl ParsedTransaction {
    pub fn to_json(&self) -> String {
        format!(
            r#"{{"customer_name":"{}","amount_paise":{},"tx_type":"{}","date_offset":{},"event_id":"{}","note":"{}"}}"#,
            escape_json(&self.customer_name),
            self.amount_paise,
            escape_json(&self.tx_type),
            self.date_offset,
            escape_json(&self.event_id),
            escape_json(&self.note),
        )
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum ParseResult {
    Success { transactions: Vec<ParsedTransaction> },
    Correction { new_amount_paise: i64 },
    Query { customer_name: String, query_type: String },
    DailySummaryQuery { date_offset: i32, query_type: String },
    TopDebtorsQuery { limit: usize },
    Reversal { customer_name: String, amount_paise: i64 },
    Ambiguous { reason: String, clarification_question: String },
    Failure { error_message: String },
}

impl ParseResult {
    pub fn to_json(&self) -> String {
        match self {
            ParseResult::Success { transactions } => {
                let tx_json: Vec<String> = transactions.iter().map(|t| t.to_json()).collect();
                format!(r#"{{"status":"Success","transactions":[{}]}}"#, tx_json.join(","))
            }
            ParseResult::Correction { new_amount_paise } => {
                format!(r#"{{"status":"Correction","new_amount_paise":{}}}"#, new_amount_paise)
            }
            ParseResult::Query { customer_name, query_type } => {
                format!(
                    r#"{{"status":"Query","customer_name":"{}","query_type":"{}"}}"#,
                    escape_json(customer_name),
                    escape_json(query_type)
                )
            }
            ParseResult::DailySummaryQuery { date_offset, query_type } => {
                format!(
                    r#"{{"status":"DailySummaryQuery","date_offset":{},"query_type":"{}"}}"#,
                    date_offset,
                    escape_json(query_type)
                )
            }
            ParseResult::TopDebtorsQuery { limit } => {
                format!(r#"{{"status":"TopDebtorsQuery","limit":{}}}"#, limit)
            }
            ParseResult::Reversal { customer_name, amount_paise } => {
                format!(
                    r#"{{"status":"Reversal","customer_name":"{}","amount_paise":{}}}"#,
                    escape_json(customer_name),
                    amount_paise
                )
            }
            ParseResult::Ambiguous { reason, clarification_question } => {
                format!(
                    r#"{{"status":"Ambiguous","reason":"{}","clarification_question":"{}"}}"#,
                    escape_json(reason),
                    escape_json(clarification_question)
                )
            }
            ParseResult::Failure { error_message } => {
                format!(r#"{{"status":"Failure","error_message":"{}"}}"#, escape_json(error_message))
            }
        }
    }
}

pub struct LedgerEngine;

impl LedgerEngine {
    // List of pronouns that MUST NEVER be accepted as customer names
    pub fn is_pronoun(token: &str) -> bool {
        let t = token.to_lowercase();
        matches!(
            t.as_str(),
            "iska" | "isko" | "isse" | "iski" | "iske" |
            "uska" | "usko" | "usse" | "uski" | "uske" |
            "inka" | "inko" | "inse" | "unka" | "unko" |
            "mera" | "meri" | "mere" | "tera" | "apna" | "kiska" |
            "is" | "us" | "in" | "un" | "ye" | "wo" | "yeh" | "voh" |
            "इसका" | "इसको" | "इससे" | "इसकी" | "इसके" |
            "उसका" | "उसको" | "उससे" | "उसकी" | "उसके" |
            "इनका" | "इनको" | "इनसे" | "उनका" | "उनको" |
            "मेरा" | "मेरी" | "मेरे" | "तेरा" | "अपना" | "किसका" |
            "इस" | "उस" | "इन" | "उन" | "यह" | "वह" | "ये" | "वो"
        )
    }

    pub fn is_reserved_word(token: &str) -> bool {
        let t = token.to_lowercase();
        matches!(
            t.as_str(),
            "aaj" | "kal" | "parso" | "today" | "yesterday" |
            "rupaye" | "rupee" | "rupees" | "rs" |
            "ka" | "ke" | "ki" | "ko" | "ne" | "se" | "me" | "par" | "tha" | "hai" |
            "udhaar" | "udhar" | "jama" | "diya" | "diye" | "baaki" | "payment" |
            "nahi" | "samaan" | "hisaab" | "kitna" | "batao" | "collection" | "total" |
            "gaya" | "hua" | "sabse" | "zyada" | "cancel" | "radd" | "wapas" |
            "आज" | "कल" | "परसों" | "रुपये" | "रुपए" |
            "का" | "के" | "की" | "को" | "ने" | "से" | "था" | "है" |
            "उधार" | "जमा" | "दिया" | "दिए" | "बाकी" | "पेमेंट" |
            "नहीं" | "सामान" | "हिसाब" | "कितना" | "बताओ" | "कलेक्शन" | "टोटल" |
            "गया" | "हुआ" | "सबसे" | "ज्यादा" | "रद्द" | "वापस" | "कैंसिल"
        )
    }

    /// Extract customer name using grammatical postposition matching and pronoun rejection.
    pub fn extract_customer_name(text: &str) -> Option<String> {
        let delimiters = [' ', ',', ';', '?', '!', '.'];
        let tokens: Vec<&str> = text
            .split(&delimiters[..])
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .collect();

        if tokens.is_empty() {
            return None;
        }

        let particles = ["ka", "ke", "ki", "ko", "ne", "se", "का", "के", "की", "को", "ने", "से"];

        // Strategy 1: Look for a token followed by a postposition particle
        for i in 0..tokens.len().saturating_sub(1) {
            let candidate = tokens[i];
            let next = tokens[i + 1].to_lowercase();
            if particles.contains(&next.as_str()) {
                if !Self::is_pronoun(candidate) && !Self::is_reserved_word(candidate) && candidate.len() >= 2 {
                    return Some(Self::format_name(candidate));
                }
            }
        }

        // Strategy 2: First valid non-reserved token
        for token in tokens {
            if !Self::is_pronoun(token) && !Self::is_reserved_word(token) && token.len() >= 2 && !token.chars().all(|c| c.is_ascii_digit()) {
                return Some(Self::format_name(token));
            }
        }

        None
    }

    fn format_name(s: &str) -> String {
        let mut chars = s.chars();
        match chars.next() {
            None => String::new(),
            Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        }
    }

    /// Extract amount in integer paise (e.g. "340" -> 34,000 paise).
    pub fn extract_amount_paise(clause: &str) -> Option<i64> {
        let normalized = Self::normalize_devanagari_digits(clause);
        for word in normalized.split(|c: char| !c.is_numeric() && c != '.') {
            if word.is_empty() {
                continue;
            }
            if let Ok(val) = word.parse::<f64>() {
                if val > 0.0 {
                    return Some((val * 100.0).round() as i64);
                }
            }
        }
        None
    }

    fn normalize_devanagari_digits(s: &str) -> String {
        let dev = "०१२३४५६७८९";
        let mut out = String::with_capacity(s.len());
        for c in s.chars() {
            if let Some(pos) = dev.find(c) {
                out.push((b'0' + (pos / 3) as u8) as char); // UTF-8 3-byte devanagari digit
            } else {
                out.push(c);
            }
        }
        out
    }

    /// Complete deterministic structured parse of Kirana speech.
    pub fn parse_structured(text: &str) -> ParseResult {
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return ParseResult::Failure {
                error_message: "Empty utterance".to_string(),
            };
        }

        let lower = trimmed.to_lowercase();

        // 1. Reversals: "Ramesh ka hisaab cancel karo", "रद्द करो"
        if lower.contains("cancel") || lower.contains("radd") || lower.contains("wapas") ||
           trimmed.contains("रद्द") || trimmed.contains("वापस") || trimmed.contains("कैंसिल") {
            if let Some(cust) = Self::extract_customer_name(trimmed) {
                let amt = Self::extract_amount_paise(trimmed).unwrap_or(0);
                return ParseResult::Reversal {
                    customer_name: cust,
                    amount_paise: amt,
                };
            }
            return ParseResult::Ambiguous {
                reason: "Reversal without customer name".to_string(),
                clarification_question: "Kiska hisaab cancel karna hai?".to_string(),
            };
        }

        // 2. Corrections: "Nahi, 430"
        if lower.starts_with("nahi") || lower.starts_with("no ") || lower.starts_with("galat") ||
           trimmed.starts_with("नहीं") || trimmed.starts_with("गलत") {
            if let Some(amt) = Self::extract_amount_paise(trimmed) {
                return ParseResult::Correction { new_amount_paise: amt };
            }
            return ParseResult::Ambiguous {
                reason: "Correction without valid amount".to_string(),
                clarification_question: "Kitna amount likhna hai?".to_string(),
            };
        }

        // 3. Top Debtors query
        if lower.contains("sabse zyada") || trimmed.contains("सबसे ज्यादा") {
            return ParseResult::TopDebtorsQuery { limit: 5 };
        }

        // 4. Daily summary queries: "Aaj kitna udhaar gaya?", "Kal ka collection kitna tha?"
        if (lower.contains("kitna") || trimmed.contains("कितना")) &&
           (lower.contains("aaj") || lower.contains("kal") || trimmed.contains("आज") || trimmed.contains("कल")) &&
           Self::extract_customer_name(trimmed).is_none() {
            let offset = if lower.contains("kal") || trimmed.contains("कल") { -1 } else { 0 };
            let q_type = if lower.contains("udhaar") || trimmed.contains("उधार") {
                "CREDIT".to_string()
            } else if lower.contains("collection") || lower.contains("jama") || trimmed.contains("जमा") {
                "PAYMENT".to_string()
            } else {
                "ALL".to_string()
            };
            return ParseResult::DailySummaryQuery {
                date_offset: offset,
                query_type: q_type,
            };
        }

        // 5. Customer balance query: "Ramesh ka total kitna hai"
        if (lower.contains("kitna") || lower.contains("hisaab") || lower.contains("total") ||
            trimmed.contains("कितना") || trimmed.contains("हिसाब") || trimmed.contains("टोटल")) &&
           Self::extract_customer_name(trimmed).is_some() {
            let cust = Self::extract_customer_name(trimmed).unwrap();
            return ParseResult::Query {
                customer_name: cust,
                query_type: "BALANCE".to_string(),
            };
        }

        // 6. Regular transactions
        let cust = match Self::extract_customer_name(trimmed) {
            Some(c) => c,
            None => {
                let has_pronoun = trimmed
                    .split(|c: char| !c.is_alphanumeric())
                    .any(|w| Self::is_pronoun(w));
                let q = if has_pronoun {
                    "Kiska hisaab hai? Kripya customer ka naam bataiye."
                } else {
                    "Kiska hisaab likhna hai?"
                };
                return ParseResult::Ambiguous {
                    reason: "Missing or ambiguous customer name".to_string(),
                    clarification_question: q.to_string(),
                };
            }
        };

        // Check for credit / payment keywords
        let is_credit = lower.contains("udhaar") || lower.contains("baaki") || trimmed.contains("उधार") || trimmed.contains("बाकी");
        let is_payment = lower.contains("diya") || lower.contains("jama") || trimmed.contains("दिया") || trimmed.contains("जमा");

        if !is_credit && !is_payment {
            return ParseResult::Ambiguous {
                reason: "Transaction direction missing".to_string(),
                clarification_question: "Kya ye udhaar hai ya jama mila?".to_string(),
            };
        }

        let amount = match Self::extract_amount_paise(trimmed) {
            Some(a) => a,
            None => {
                return ParseResult::Ambiguous {
                    reason: "Missing amount".to_string(),
                    clarification_question: "Kitne rupaye ka transaction hai?".to_string(),
                };
            }
        };

        let direction_str = if is_credit && !is_payment {
            "CREDIT"
        } else if is_payment && !is_credit {
            "PAYMENT"
        } else {
            "CREDIT"
        };

        let date_offset = if lower.contains("kal") || trimmed.contains("कल") { -1 } else { 0 };

        let tx = ParsedTransaction {
            customer_name: cust,
            amount_paise: amount,
            tx_type: direction_str.to_string(),
            date_offset,
            event_id: String::new(),
            note: trimmed.to_string(),
        };

        ParseResult::Success {
            transactions: vec![tx],
        }
    }

    /// Calculate net outstanding balance in integer paise:
    /// Net = Total Credit - Total Payment - Reversals
    pub fn calculate_balance_paise(credits: &[i64], payments: &[i64], reversals: &[i64]) -> i64 {
        let total_credit: i64 = credits.iter().sum();
        let total_payment: i64 = payments.iter().sum();
        let total_reversals: i64 = reversals.iter().sum();
        total_credit - total_payment - total_reversals
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_credit_parsing_latin() {
        let res = LedgerEngine::parse_structured("Ramesh ka 340 udhaar");
        match res {
            ParseResult::Success { transactions } => {
                assert_eq!(transactions.len(), 1);
                assert_eq!(transactions[0].customer_name, "Ramesh");
                assert_eq!(transactions[0].amount_paise, 34000);
                assert_eq!(transactions[0].tx_type, "CREDIT");
            }
            _ => panic!("Expected Success, got {:?}", res),
        }
    }

    #[test]
    fn test_pronoun_rejection_devanagari() {
        let res = LedgerEngine::parse_structured("इसका 340 उधार");
        match res {
            ParseResult::Ambiguous { clarification_question, .. } => {
                assert!(clarification_question.contains("customer ka naam"));
            }
            _ => panic!("Expected Ambiguous rejection, got {:?}", res),
        }
    }

    #[test]
    fn test_balance_calculation() {
        let credits = [34000, 16000]; // ₹500
        let payments = [20000];        // ₹200
        let reversals = [0];
        let bal = LedgerEngine::calculate_balance_paise(&credits, &payments, &reversals);
        assert_eq!(bal, 30000); // ₹300 outstanding
    }
}
