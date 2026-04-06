package com.omnix.agent.skills

import android.content.Context
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.floatArrayToBytes
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pre-built skill library — Task 39.
 * Seeds the database with 15+ complete skill definitions on first launch.
 */
object SkillLibrary {

    private val json = Json { ignoreUnknownKeys = true }

    // ── WhatsApp ────────────────────────────────────────────────────────────────
    private const val WA_SEND_MESSAGE = """
    {
      "id":"whatsapp_send_message","app_id":"com.whatsapp","name":"WhatsApp Send Message",
      "type":"ui_automation","category":"messaging","version":"1.0",
      "intent_patterns":["send whatsapp to","whatsapp message to","message {contact} on whatsapp"],
      "parameters":{"contact":"","message":""},
      "steps":[
        {"action":"launch","package":"com.whatsapp"},
        {"action":"tap_content_desc","desc":"Search"},
        {"action":"type_text","resource_id":"com.whatsapp:id/search_input","value":"{contact}"},
        {"action":"wait_for","resource_id":"com.whatsapp:id/contact_row_container","timeout_ms":4000},
        {"action":"tap","resource_id":"com.whatsapp:id/contact_row_container"},
        {"action":"type_text","resource_id":"com.whatsapp:id/entry","value":"{message}"},
        {"action":"tap_content_desc","desc":"Send"}
      ],
      "confirmation_required":true
    }"""

    private const val WA_MAKE_CALL = """
    {
      "id":"whatsapp_call","app_id":"com.whatsapp","name":"WhatsApp Call",
      "type":"ui_automation","category":"messaging","version":"1.0",
      "intent_patterns":["call {contact} on whatsapp","whatsapp call","video call {contact}"],
      "parameters":{"contact":"","video":"false"},
      "steps":[
        {"action":"launch","package":"com.whatsapp"},
        {"action":"tap_text","text":"Calls"},
        {"action":"tap_content_desc","desc":"New call"},
        {"action":"type_text","resource_id":"com.whatsapp:id/search_contact","value":"{contact}"},
        {"action":"wait_for","resource_id":"com.whatsapp:id/contact_row_container","timeout_ms":4000},
        {"action":"tap","resource_id":"com.whatsapp:id/contact_row_container"},
        {"action":"tap_content_desc","desc":"Voice call"}
      ],
      "confirmation_required":true
    }"""

    // ── Google Pay (deep-link UPI) ──────────────────────────────────────────────
    private const val GPAY_SEND_MONEY = """
    {
      "id":"gpay_send_money","app_id":"com.google.android.apps.nbu.paisa.user","name":"GPay Send Money",
      "type":"deep_link","category":"payments","version":"1.0",
      "intent_patterns":["pay {contact} {amount}","send {amount} to {contact} gpay","gpay transfer"],
      "parameters":{"contact":"","amount":"","upi_id":""},
      "steps":[
        {"action":"deep_link","uri":"tez://upi/pay?pa={upi_id}&am={amount}&cu=INR"},
        {"action":"wait_for_text","text":"Pay","timeout_ms":5000},
        {"action":"tap_text","text":"Pay"}
      ],
      "confirmation_required":true
    }"""

    // ── PhonePe ─────────────────────────────────────────────────────────────────
    private const val PHONEPE_SEND = """
    {
      "id":"phonepe_send","app_id":"com.phonepe.app","name":"PhonePe Send Money",
      "type":"ui_automation","category":"payments","version":"1.0",
      "intent_patterns":["phonepe pay","send via phonepe","phonepe transfer {amount}"],
      "parameters":{"contact":"","amount":""},
      "steps":[
        {"action":"launch","package":"com.phonepe.app"},
        {"action":"tap_text","text":"Send Money"},
        {"action":"tap_text","text":"To Mobile Number"},
        {"action":"type_text","resource_id":"com.phonepe.app:id/et_mobile","value":"{contact}"},
        {"action":"tap_text","text":"Proceed"},
        {"action":"type_text","resource_id":"com.phonepe.app:id/et_amount","value":"{amount}"},
        {"action":"tap_text","text":"Pay"}
      ],
      "confirmation_required":true
    }"""

    // ── Google Maps ─────────────────────────────────────────────────────────────
    private const val MAPS_NAVIGATE = """
    {
      "id":"maps_navigate","app_id":"com.google.android.apps.maps","name":"Google Maps Navigate",
      "type":"deep_link","category":"travel","version":"1.0",
      "intent_patterns":["navigate to {destination}","directions to {destination}","take me to {destination}","how to reach {destination}"],
      "parameters":{"destination":""},
      "steps":[
        {"action":"deep_link","uri":"google.navigation:q={destination}&mode=d"},
        {"action":"wait_for_text","text":"Start","timeout_ms":5000}
      ],
      "confirmation_required":false
    }"""

    // ── Phone ───────────────────────────────────────────────────────────────────
    private const val CALL_CONTACT = """
    {
      "id":"call_contact","app_id":"com.android.dialer","name":"Call Contact",
      "type":"intent","category":"communication","version":"1.0",
      "intent_patterns":["call {contact}","phone {contact}","ring {contact}","dial {contact}"],
      "parameters":{"contact":"","phone":""},
      "steps":[
        {"action":"dial","phone":"{phone}"}
      ],
      "confirmation_required":true
    }"""

    // ── Swiggy ──────────────────────────────────────────────────────────────────
    private const val SWIGGY_OPEN = """
    {
      "id":"swiggy_open","app_id":"in.swiggy.android","name":"Open Swiggy",
      "type":"ui_automation","category":"food","version":"1.0",
      "intent_patterns":["order food","open swiggy","swiggy"],
      "parameters":{},
      "steps":[
        {"action":"launch","package":"in.swiggy.android"}
      ],
      "confirmation_required":false
    }"""

    // ── Zerodha Kite ────────────────────────────────────────────────────────────
    private const val KITE_OPEN_WATCHLIST = """
    {
      "id":"kite_watchlist","app_id":"com.zerodha.kite3","name":"Kite Open Watchlist",
      "type":"ui_automation","category":"finance","version":"1.0",
      "intent_patterns":["open kite","zerodha watchlist","check market","kite watchlist"],
      "parameters":{},
      "steps":[
        {"action":"launch","package":"com.zerodha.kite3"},
        {"action":"wait_for_text","text":"Watchlist","timeout_ms":6000}
      ],
      "confirmation_required":false
    }"""

    // ── YouTube ─────────────────────────────────────────────────────────────────
    private const val YOUTUBE_SEARCH = """
    {
      "id":"youtube_search","app_id":"com.google.android.youtube","name":"YouTube Search",
      "type":"deep_link","category":"entertainment","version":"1.0",
      "intent_patterns":["search youtube for {query}","play {query} on youtube","youtube {query}"],
      "parameters":{"query":""},
      "steps":[
        {"action":"deep_link","uri":"vnd.youtube:///results?search_query={query}"}
      ],
      "confirmation_required":false
    }"""

    // ── Gmail ───────────────────────────────────────────────────────────────────
    private const val GMAIL_COMPOSE = """
    {
      "id":"gmail_compose","app_id":"com.google.android.gm","name":"Gmail Compose",
      "type":"deep_link","category":"productivity","version":"1.0",
      "intent_patterns":["send email to {contact}","email {contact}","compose gmail to {contact}"],
      "parameters":{"contact":"","subject":"","body":""},
      "steps":[
        {"action":"deep_link","uri":"googlegmail:///co?to={contact}&subject={subject}&body={body}"}
      ],
      "confirmation_required":true
    }"""

    private val ALL_SKILLS = listOf(
        WA_SEND_MESSAGE, WA_MAKE_CALL,
        GPAY_SEND_MONEY, PHONEPE_SEND,
        MAPS_NAVIGATE,
        CALL_CONTACT,
        SWIGGY_OPEN,
        KITE_OPEN_WATCHLIST,
        YOUTUBE_SEARCH,
        GMAIL_COMPOSE
    ) + BankingSkillLibrary.all()

    /**
     * Seed all pre-built skills into the database.
     * Skips skills that already exist (idempotent).
     */
    suspend fun seedAll(context: Context, db: OmnixDatabase) = withContext(Dispatchers.IO) {
        ALL_SKILLS.forEach { skillJson ->
            try {
                val obj = json.parseToJsonElement(skillJson.trim()).let { it as? JsonObject } ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.content ?: return@forEach
                if (db.skillDao().getById(id) != null) return@forEach // already seeded

                val name = obj["name"]?.jsonPrimitive?.content ?: id
                val emb = GemmaInferenceEngine.generateEmbedding(name)

                db.skillDao().upsert(
                    SkillEntity(
                        id = id,
                        appId = obj["app_id"]?.jsonPrimitive?.content ?: "",
                        name = name,
                        type = obj["type"]?.jsonPrimitive?.content ?: "ui_automation",
                        category = obj["category"]?.jsonPrimitive?.content ?: "other",
                        version = obj["version"]?.jsonPrimitive?.content ?: "1.0",
                        intentPatternsJson = obj["intent_patterns"]?.toString() ?: "[]",
                        parametersJson = obj["parameters"]?.toString() ?: "{}",
                        stepsJson = obj["steps"]?.toString() ?: "[]",
                        confirmationRequired = obj["confirmation_required"]?.jsonPrimitive?.content == "true",
                        embedding = floatArrayToBytes(emb),
                        intentHash = id.take(16),
                        status = "active"
                    )
                )
            } catch (_: Exception) {}
        }
    }
}
