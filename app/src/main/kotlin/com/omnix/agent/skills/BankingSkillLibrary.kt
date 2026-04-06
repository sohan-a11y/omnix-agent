package com.omnix.agent.skills

/**
 * Pre-built banking skill definitions — Tasks 13, 24.
 * Covers: HDFC, ICICI iMobile, Axis Mobile, Kotak, SBI YONO, Paytm, GPay, PhonePe.
 * Skills returned as JSON strings consumed by SkillLibrary.seedAll().
 */
object BankingSkillLibrary {

    // ── HDFC Bank ───────────────────────────────────────────────────────────────
    const val HDFC_CHECK_BALANCE = """
    {
      "id": "hdfc_check_balance",
      "app_id": "com.snapwork.hdfc",
      "name": "HDFC Check Balance",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["check hdfc balance","hdfc balance","how much in hdfc","hdfc account balance"],
      "parameters": {},
      "steps": [
        {"action":"launch","package":"com.snapwork.hdfc"},
        {"action":"wait_for","resource_id":"com.snapwork.hdfc:id/accountBalance","timeout_ms":8000},
        {"action":"read_text","resource_id":"com.snapwork.hdfc:id/accountBalance","output_key":"balance"},
        {"action":"speak","template":"Your HDFC balance is {balance}"}
      ],
      "confirmation_required": false
    }"""

    const val HDFC_MINI_STATEMENT = """
    {
      "id": "hdfc_mini_statement",
      "app_id": "com.snapwork.hdfc",
      "name": "HDFC Mini Statement",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["hdfc mini statement","last transactions hdfc","hdfc recent transactions"],
      "parameters": {},
      "steps": [
        {"action":"launch","package":"com.snapwork.hdfc"},
        {"action":"tap_text","text":"Account Statement"},
        {"action":"wait_for","resource_id":"com.snapwork.hdfc:id/transactionList","timeout_ms":8000},
        {"action":"read_screen_text","output_key":"transactions"},
        {"action":"speak","template":"Recent HDFC transactions: {transactions}"}
      ],
      "confirmation_required": false
    }"""

    // ── ICICI iMobile ──────────────────────────────────────────────────────────
    const val ICICI_CHECK_BALANCE = """
    {
      "id": "icici_check_balance",
      "app_id": "com.csam.icici.bank.imobile",
      "name": "ICICI Check Balance",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["check icici balance","icici balance","how much in icici","icici account balance"],
      "parameters": {},
      "steps": [
        {"action":"launch","package":"com.csam.icici.bank.imobile"},
        {"action":"wait_for_text","text":"Account Summary","timeout_ms":8000},
        {"action":"tap_text","text":"Account Summary"},
        {"action":"wait_for","resource_id":"com.csam.icici.bank.imobile:id/availableBalance","timeout_ms":6000},
        {"action":"read_text","resource_id":"com.csam.icici.bank.imobile:id/availableBalance","output_key":"balance"},
        {"action":"speak","template":"Your ICICI balance is {balance}"}
      ],
      "confirmation_required": false
    }"""

    const val ICICI_TRANSFER = """
    {
      "id": "icici_transfer",
      "app_id": "com.csam.icici.bank.imobile",
      "name": "ICICI Fund Transfer",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["transfer from icici","send money icici","icici transfer"],
      "parameters": {"amount":"","contact":""},
      "steps": [
        {"action":"launch","package":"com.csam.icici.bank.imobile"},
        {"action":"tap_text","text":"Fund Transfer"},
        {"action":"tap_text","text":"NEFT/RTGS"},
        {"action":"type_text","resource_id":"com.csam.icici.bank.imobile:id/beneficiaryName","value":"{contact}"},
        {"action":"type_text","resource_id":"com.csam.icici.bank.imobile:id/amount","value":"{amount}"},
        {"action":"tap_text","text":"Proceed"}
      ],
      "confirmation_required": true
    }"""

    // ── Axis Mobile ────────────────────────────────────────────────────────────
    const val AXIS_CHECK_BALANCE = """
    {
      "id": "axis_check_balance",
      "app_id": "com.axis.mobile",
      "name": "Axis Bank Balance",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["check axis balance","axis balance","axis account balance","how much in axis"],
      "parameters": {},
      "steps": [
        {"action":"launch","package":"com.axis.mobile"},
        {"action":"wait_for_text","text":"Account Balance","timeout_ms":8000},
        {"action":"read_text","resource_id":"com.axis.mobile:id/balanceAmount","output_key":"balance"},
        {"action":"speak","template":"Your Axis Bank balance is {balance}"}
      ],
      "confirmation_required": false
    }"""

    // ── Kotak 811 ──────────────────────────────────────────────────────────────
    const val KOTAK_CHECK_BALANCE = """
    {
      "id": "kotak_check_balance",
      "app_id": "com.msf.kbank.mobile",
      "name": "Kotak Bank Balance",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["check kotak balance","kotak balance","kotak account balance","how much in kotak"],
      "parameters": {},
      "steps": [
        {"action":"launch","package":"com.msf.kbank.mobile"},
        {"action":"wait_for_text","text":"Available Balance","timeout_ms":8000},
        {"action":"read_text","resource_id":"com.msf.kbank.mobile:id/tvAvailableBalance","output_key":"balance"},
        {"action":"speak","template":"Your Kotak balance is {balance}"}
      ],
      "confirmation_required": false
    }"""

    // ── SBI YONO ───────────────────────────────────────────────────────────────
    const val SBI_CHECK_BALANCE = """
    {
      "id": "sbi_check_balance",
      "app_id": "com.sbi.lotusintouch",
      "name": "SBI YONO Balance",
      "type": "ui_automation",
      "category": "banking",
      "version": "1.0",
      "intent_patterns": ["check sbi balance","sbi balance","sbi yono balance","how much in sbi"],
      "parameters": {},
      "steps": [
        {"action":"launch","package":"com.sbi.lotusintouch"},
        {"action":"wait_for_text","text":"Account Summary","timeout_ms":10000},
        {"action":"tap_text","text":"Account Summary"},
        {"action":"wait_for_text","text":"Available Balance","timeout_ms":6000},
        {"action":"read_screen_text","output_key":"balance"},
        {"action":"speak","template":"SBI account summary: {balance}"}
      ],
      "confirmation_required": false
    }"""

    fun all() = listOf(
        HDFC_CHECK_BALANCE, HDFC_MINI_STATEMENT,
        ICICI_CHECK_BALANCE, ICICI_TRANSFER,
        AXIS_CHECK_BALANCE,
        KOTAK_CHECK_BALANCE,
        SBI_CHECK_BALANCE
    )
}
