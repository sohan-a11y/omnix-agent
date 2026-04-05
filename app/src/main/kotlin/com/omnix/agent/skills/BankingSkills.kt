package com.omnix.agent.skills

import com.omnix.agent.database.SkillEntity
import com.omnix.agent.executor.ElementSelector
import com.omnix.agent.executor.SkillStep
import com.omnix.agent.ai.floatArrayToBytes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Predefined banking skills.
 * These are base templates - OMNIX adapts them per bank app via AccessibilityService.
 * Works on FLAG_SECURE banking apps via AccessibilityService.
 */
object BankingSkills {

    private val json = Json { ignoreUnknownKeys = true }

    fun getHDFCBalanceSkill(): SkillEntity {
        val steps = listOf(
            SkillStep(
                action = "launch_app",
                value = "com.snapwork.hdfc",
                narration = "Opening HDFC Mobile Banking",
                delayAfterMs = 2000
            ),
            SkillStep(
                action = "wait_element",
                element = ElementSelector(resourceId = "com.snapwork.hdfc:id/balance_amount"),
                timeoutMs = 10000,
                narration = "Waiting for account to load"
            ),
            SkillStep(
                action = "capture",
                element = ElementSelector(resourceId = "com.snapwork.hdfc:id/balance_amount"),
                outputKey = "balance",
                narration = "Reading balance"
            )
        )

        return buildSkill(
            id = "hdfc_check_balance",
            appId = "com.snapwork.hdfc",
            name = "Check HDFC Balance",
            category = "banking",
            intentPatterns = listOf("check balance", "what is my balance", "hdfc balance", "account balance"),
            steps = steps,
            confirmationRequired = false
        )
    }

    fun getSBIBalanceSkill(): SkillEntity {
        val steps = listOf(
            SkillStep(action = "launch_app", value = "com.sbi.lotusintouch", delayAfterMs = 2000,
                narration = "Opening SBI YONO"),
            SkillStep(action = "wait_element",
                element = ElementSelector(resourceId = "com.sbi.lotusintouch:id/account_balance"),
                timeoutMs = 12000, narration = "Loading account"),
            SkillStep(action = "capture",
                element = ElementSelector(resourceId = "com.sbi.lotusintouch:id/account_balance"),
                outputKey = "balance", narration = "Reading balance")
        )
        return buildSkill("sbi_check_balance", "com.sbi.lotusintouch", "Check SBI Balance",
            "banking", listOf("sbi balance", "yono balance"), steps, false)
    }

    fun getGPayTransferSkill(): SkillEntity {
        val steps = listOf(
            SkillStep(action = "launch_app", value = "com.google.android.apps.nbu.paisa.user",
                delayAfterMs = 1500, narration = "Opening Google Pay"),
            SkillStep(action = "tap",
                element = ElementSelector(resourceId = "com.google.android.apps.nbu.paisa.user:id/new_payment_fab"),
                narration = "Tapping New Payment"),
            SkillStep(action = "type",
                element = ElementSelector(resourceId = "com.google.android.apps.nbu.paisa.user:id/search_box"),
                value = "{contact}", narration = "Searching for contact"),
            SkillStep(action = "wait",
                delayAfterMs = 1000, narration = "Waiting for results"),
            SkillStep(action = "tap",
                element = ElementSelector(text = "{contact}"),
                narration = "Selecting contact"),
            SkillStep(action = "type",
                element = ElementSelector(resourceId = "com.google.android.apps.nbu.paisa.user:id/amount"),
                value = "{amount}", narration = "Entering amount ₹{amount}"),
            SkillStep(action = "tap",
                element = ElementSelector(resourceId = "com.google.android.apps.nbu.paisa.user:id/pay_button"),
                narration = "Tapping Pay")
        )
        return buildSkill("gpay_transfer", "com.google.android.apps.nbu.paisa.user",
            "Send Money via GPay", "payments",
            listOf("send money", "pay {contact}", "transfer {amount} to {contact}", "gpay {contact}"),
            steps, confirmationRequired = true)
    }

    fun getPhonePeTransferSkill(): SkillEntity {
        val steps = listOf(
            SkillStep(action = "launch_app", value = "com.phonepe.app", delayAfterMs = 1500,
                narration = "Opening PhonePe"),
            SkillStep(action = "tap",
                element = ElementSelector(resourceId = "com.phonepe.app:id/send_money",
                    text = "Send Money"),
                narration = "Tapping Send Money"),
            SkillStep(action = "type",
                element = ElementSelector(resourceId = "com.phonepe.app:id/search_input"),
                value = "{contact}", narration = "Searching contact"),
            SkillStep(action = "wait", delayAfterMs = 800),
            SkillStep(action = "tap",
                element = ElementSelector(text = "{contact}"),
                narration = "Selecting {contact}"),
            SkillStep(action = "type",
                element = ElementSelector(resourceId = "com.phonepe.app:id/amount_input"),
                value = "{amount}", narration = "Entering ₹{amount}"),
            SkillStep(action = "tap",
                element = ElementSelector(text = "Pay"),
                narration = "Confirming payment")
        )
        return buildSkill("phonepe_transfer", "com.phonepe.app",
            "Send Money via PhonePe", "payments",
            listOf("phonepe {contact}", "pay via phonepe", "phonepe transfer"),
            steps, confirmationRequired = true)
    }

    private fun buildSkill(
        id: String,
        appId: String,
        name: String,
        category: String,
        intentPatterns: List<String>,
        steps: List<SkillStep>,
        confirmationRequired: Boolean
    ): SkillEntity {
        return SkillEntity(
            id = id,
            appId = appId,
            name = name,
            type = "ui_automation",
            category = category,
            version = "1.0",
            intentPatternsJson = json.encodeToString(intentPatterns),
            parametersJson = "{}",
            stepsJson = json.encodeToString(steps),
            confirmationRequired = confirmationRequired,
            embedding = floatArrayToBytes(FloatArray(768)),
            intentHash = ""
        )
    }
}
