package com.omnix.agent.skills

import com.omnix.agent.database.SkillEntity
import com.omnix.agent.executor.ElementSelector
import com.omnix.agent.executor.SkillStep
import com.omnix.agent.ai.floatArrayToBytes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stock market skills - Zerodha Kite integration via AccessibilityService
 */
object StockSkills {

    private val json = Json { ignoreUnknownKeys = true }

    fun getZerodhaPortfolioSkill(): SkillEntity {
        val steps = listOf(
            SkillStep(action = "launch_app", value = "com.zerodha.kite3", delayAfterMs = 2000,
                narration = "Opening Zerodha Kite"),
            SkillStep(action = "wait_element",
                element = ElementSelector(resourceId = "com.zerodha.kite3:id/portfolio_value"),
                timeoutMs = 10000, narration = "Loading portfolio"),
            SkillStep(action = "capture",
                element = ElementSelector(resourceId = "com.zerodha.kite3:id/portfolio_value"),
                outputKey = "portfolio_value", narration = "Reading portfolio value"),
            SkillStep(action = "capture",
                element = ElementSelector(resourceId = "com.zerodha.kite3:id/day_pnl"),
                outputKey = "day_pnl", narration = "Reading day P&L")
        )
        return buildSkill("zerodha_portfolio", "com.zerodha.kite3",
            "Check Zerodha Portfolio", "stocks",
            listOf("portfolio value", "my stocks", "zerodha portfolio", "check investments"),
            steps, false)
    }

    fun getZerodhaBuySkill(): SkillEntity {
        val steps = listOf(
            SkillStep(action = "launch_app", value = "com.zerodha.kite3", delayAfterMs = 2000,
                narration = "Opening Zerodha"),
            SkillStep(action = "tap",
                element = ElementSelector(resourceId = "com.zerodha.kite3:id/search"),
                narration = "Opening search"),
            SkillStep(action = "type",
                element = ElementSelector(resourceId = "com.zerodha.kite3:id/search_input"),
                value = "{stock}", narration = "Searching for {stock}"),
            SkillStep(action = "wait", delayAfterMs = 800),
            SkillStep(action = "tap",
                element = ElementSelector(text = "{stock}"),
                narration = "Selecting {stock}"),
            SkillStep(action = "tap",
                element = ElementSelector(text = "BUY", resourceId = "com.zerodha.kite3:id/buy_btn"),
                narration = "Tapping Buy"),
            SkillStep(action = "type",
                element = ElementSelector(resourceId = "com.zerodha.kite3:id/quantity"),
                value = "{quantity}", narration = "Entering quantity {quantity}"),
            SkillStep(action = "tap",
                element = ElementSelector(text = "Place Order"),
                narration = "Placing order")
        )
        return buildSkill("zerodha_buy", "com.zerodha.kite3",
            "Buy Stock on Zerodha", "stocks",
            listOf("buy {quantity} shares of {stock}", "buy {stock}", "place buy order"),
            steps, confirmationRequired = true)
    }

    private fun buildSkill(
        id: String, appId: String, name: String, category: String,
        intentPatterns: List<String>, steps: List<SkillStep>, confirmationRequired: Boolean
    ): SkillEntity {
        return SkillEntity(
            id = id, appId = appId, name = name, type = "ui_automation",
            category = category, version = "1.0",
            intentPatternsJson = json.encodeToString(intentPatterns),
            parametersJson = "{}", stepsJson = json.encodeToString(steps),
            confirmationRequired = confirmationRequired,
            embedding = floatArrayToBytes(FloatArray(768)), intentHash = ""
        )
    }
}
