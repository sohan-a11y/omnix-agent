# OMNIX Complete Build Design
**Date:** 2026-04-06
**Status:** Approved

---

## What We Are Building

OMNIX — an autonomous on-device AI agent for Android 12+ (Samsung S25 Ultra primary target).

- Package: `com.omnix.agent` | minSdk 31 | arm64-v8a only
- On-device Gemma 4 E2B via LiteRT-LM (no cloud, full privacy)
- AccessibilityService reads and controls ANY app including FLAG_SECURE banking apps
- "Hey OMNIX" wake word via Porcupine 3.0.1 (Snapdragon-optimized .ppn)
- Room 2.6.1 database with 7 entities
- Distribution: APK sideload only — NOT Play Store
- GitHub Actions CI produces signed release APK on every `v*` tag push

---

## Current State (what exists)

42 Kotlin files across 9 packages. Zero tests. No GitHub remote. No signing keystore.
All files built from `OMNIX_AI_Dev_Prompts_v3_COMPLETE_MERGED.docx` (40 tasks, 5 sprints).

---

## Execution Strategy: Module-by-Module CI Loop (Option C)

Process each package in dependency order:

```
For each module:
  kotlin-reviewer → code-reviewer → [security-reviewer] → [database-reviewer] → [performance-optimizer]
  → fix all issues → tdd (write tests) → verification-before-completion → commit
```

Then: GitHub setup (keystore → repo → secrets → push)

---

## Module Map (complete, nothing omitted)

### Module 0 — Build System (Tasks 1, 31)
**Goal:** Project must compile and CI must be green before anything else.

Files to create:
- `gradlew` (Unix shell script)
- `gradlew.bat` (Windows batch)
- `gradle/wrapper/gradle-wrapper.jar` (binary)
- `app/src/main/res/mipmap-*/ic_launcher.png` (all 5 densities)
- `local.properties` (template with SDK path placeholder)

Files to fix:
- Delete `app/src/main/kotlin/com/omnix/agent/BuildConfig.kt` — conflicts with Gradle auto-generated

Skills: code-reviewer

---

### Module 1 — `database` (Task 3)
**Files:** `Entities.kt`, `Daos.kt`, `OmnixDatabase.kt`

Missing:
- `ExecutionHistoryEntity` (needed by CompositeSkillEngine Task 27)
- `APKKnowledgeEntity` (mentioned in Task 9 spec, not created)
- `ScreenCrawlEntity` (for UI crawl results)

Fixes needed:
- `ScreenEntity.id` should be `SHA-256(appId + screenName)` per spec
- `SkillEntity.embedding` — add `@ColumnInfo(typeAffinity = ColumnInfo.BLOB)`
- All DAOs need `@Transaction` on multi-step operations

Skills: kotlin-reviewer, database-reviewer, code-reviewer, tdd

---

### Module 2 — `ai` (Tasks 5)
**Files:** `GemmaInferenceEngine.kt`, `ModelDownloadWorker.kt`

Missing:
- `ModelDownloadManager.kt` using Android `DownloadManager` (not WorkManager+HTTP — spec is explicit)
- `EncryptedPrefsManager.kt` for Zerodha API key storage

Fixes needed:
- `ModelDownloadWorker.kt` must be replaced with Android `DownloadManager`-based download
- `GemmaInferenceEngine.generateEmbedding()` returns placeholder `FloatArray(768){0f}` — needs real LiteRT embedding call

Skills: kotlin-reviewer, code-reviewer, tdd

---

### Module 3 — `core` (Tasks 4, 8, 32)
**Files:** `OmnixAccessibilityService.kt`, `OmnixBootReceiver.kt`, `OmnixNotificationService.kt`, `SamsungCompatibilityLayer.kt`

Fixes needed:
- `SamsungCompatibilityLayer`: add 50ms delay + re-query on Samsung Galaxy AI event priority fix
- `OmnixBootReceiver`: add `BootDiscoveryWorker` inline (already done — verify)
- `OmnixAccessibilityService.takeScreenshotCompat()`: implement for API 31+ using `takeScreenshot()`

Security review: AccessibilityService handles sensitive data from all apps — review data retention, logging, and what gets stored.

Skills: kotlin-reviewer, code-reviewer, security-reviewer, tdd

---

### Module 4 — `voice` (Tasks 6, 7)
**Files:** `VoicePipeline.kt`, `ASREngine.kt`, `TTS.kt`, `OmnixVoiceService.kt`

Fixes needed:
- `TTS.kt`: change `Locale.US` → `Locale("en", "IN")` (Indian English per spec)
- `VoicePipeline.kt`: `.ppn` path should be `"models/omnix_android_arm64.ppn"` loaded from `filesDir`
- `ASREngine.kt`: `context` parameter should not be nullable — make non-null
- `VoicePipeline.onWakeWordDetected()`: call `AppPreLauncher.warmUp()` immediately on wake word (Task 33)

Skills: kotlin-reviewer, code-reviewer, tdd

---

### Module 5 — `discovery` (Tasks 9, 10, 11, 12, 15, 34)
**Files:** `APKAnalyzer.kt`, `DiscoveryEngine.kt`, `DifferentialDiscovery.kt`, `NewAppReceiver.kt`, `OmnixDiscoveryService.kt`

Missing (not implemented at all):
- `DiscoveryEngine.crawlAppWithAPKGuide()` — Task 11: launch app, navigate each screen, validate APK structure at runtime
- `DiscoveryEngine.labelUnknownElements()` — Task 12: batch Gemma vision calls for elements with no text/desc
- `DiscoveryEngine.generateSkillsFromNavPaths()` — Task 12: synthesize skills from navigation paths
- `DiscoveryTestActivity.kt` — Task 15: integration test UI for WhatsApp + bank + Maps

Fixes needed:
- `APKAnalyzer.parseBinaryXml()`: currently returns stub — implement proper Android binary XML parsing via `XmlResourceParser`
- `DiscoveryEngine.isSystemApp()`: add more Samsung system package prefixes

Skills: kotlin-reviewer, code-reviewer, tdd

---

### Module 6 — `skills` (Tasks 13, 14, 16, 23, 24, 25, 26, 27, 37, 39)
**Files:** `SkillLibraryManager.kt`, `BankingSkills.kt`, `StockSkills.kt`, `HumanBehaviorSimulator.kt`, `ParameterResolver.kt`, `SkillMarketplace.kt`, `EmergencySOSSkill.kt`, `MorningBriefingSkill.kt`

Missing:
- `SkillMatcher.kt` — separate class per spec (SkillLibraryManager has the logic but wrong class name)
- `CorrectionLearner.kt` — persists overrides, `applyOverrides(intent)` called in Orchestrator
- `ContactsReader.kt` — Levenshtein fuzzy contact search (spec: distance ≤ 2)
- `BankingSkillLibrary.kt` — ICICI iMobile (`com.csam.icici.bank.imobile`), Axis Mobile (`com.axis.mobile`), Kotak (`com.msf.kbank.mobile`) skills
- `SkillLibrary.kt` — Task 39: 10+ complete pre-built skill JSONs (WhatsApp send, GPay deep-link UPI, HDFC balance, Google Maps navigate, etc.)
- `ScheduledTaskManager.kt` — Task 25: full WorkManager scheduler with one_time/recurring_daily/recurring_interval/conditional types
- `EmergencyWorkflow.kt` — Task 26: proper parallel coroutines, 5-second max SOS
- `SkillRegistry.kt` — Task 37: HTTP skill search + import

Fixes needed:
- `HumanBehaviorSimulator`: not integrated into SkillExecutor — `wrappedTap()` must replace all direct `a11y.tap()` calls
- `ParameterResolver`: needs `ContactsReader` integration + Levenshtein matching
- `BankingSkills.kt`: rename to `BankingSkillLibrary.kt`, add 3 missing banks

Security review: Banking + payment + SOS skills — critical.

Skills: kotlin-reviewer, code-reviewer, security-reviewer, tdd

---

### Module 7 — `executor` (Tasks 17, 18, 19, 22, 33)
**Files:** `SkillExecutor.kt`, `OmnixOrchestrator.kt`, `AppPreLauncher.kt`

Missing:
- `SkillExecutor` step types: `long_press`, `double_tap`, `pinch_zoom`, `take_screenshot`, `read_screen_text`, `open_notification_shade`, `set_clipboard`, `read_clipboard` — at least 8 more step types from spec
- `AppPreLauncher.HourlyUsageModel` — usage pattern prediction from SharedPreferences JSON

Fixes needed:
- `OmnixOrchestrator.handleVoiceIntent()`: add `CorrectionLearner.applyOverrides(intent)` before `findSkill()`
- `OmnixOrchestrator`: add "I don't know this skill yet — learning it now" flow that triggers discovery
- `AppPreLauncher.prewarm()`: call `a11y.waitForElement()` after launch to confirm app loaded

Security review: Orchestrator routes all commands — verify no intent injection possible.

Skills: kotlin-reviewer, code-reviewer, security-reviewer, tdd

---

### Module 8 — `improvements` (Tasks 20, 21, 35, 38)
**Files:** `SelfHealingSystem.kt`, `EventTriggerEngine.kt`, `ContextMemoryManager.kt`, `CompositeSkillEngine.kt`, `ProactiveIntelligence.kt`, `PerformanceProfiler.kt`

Missing:
- `ContextManager.kt` — Task 21: `estimateTokenCount()`, `compactContextIfNeeded()` with 80%/90% thresholds
- `ProactiveAssistant.kt` — Task 35: portfolio monitor (P&L > ₹2000 change), bill due in 3 days, daily step goal
- `EventTriggerEngine` — all 7 triggers need real implementation:
  1. `LocationLeave` — FusedLocationProviderClient
  2. `ScreenAppear` — already partial
  3. `TextChange` — already partial
  4. `NotificationReceived` — hook into NotificationService
  5. `TimeOfDay` — WorkManager periodic
  6. `BatteryLevel` — BroadcastReceiver
  7. `AppLaunch` — window state changed
- `OmnixProfiler.kt` — Task 38: timing instrumentation wrapper

Fixes needed:
- `SelfHealingSystem`: add `db.skillDao().recordHeal(step.skillId, updatedStepsJson)` after successful heal (permanent fix)
- `CompositeSkillEngine`: needs `ExecutionHistoryEntity` in DB (added in Module 1)
- `PerformanceProfiler`: add CPU sampling via `/proc/stat`

Skills: kotlin-reviewer, code-reviewer, performance-optimizer, tdd

---

### Module 9 — `mesh` (Task 36)
**Files:** `OmnixMeshService.kt`

Missing:
- `OmnixMesh.kt` — Task 36: real mDNS via `NsdManager.registerService("_omnix._tcp")`, peer discovery, command routing

Fixes needed:
- `OmnixMeshService.kt` is currently a stub — wire `OmnixMesh.kt` in

Skills: kotlin-reviewer, code-reviewer, tdd

---

### Module 10 — `ui` (Tasks 7, 28, 29, 30)
**Files:** `OnboardingActivity.kt`, `SettingsActivity.kt`, `OverlayUI.kt`, `ConfirmationGate.kt`, `PlanPreview.kt`

Missing:
- `SystemTestActivity.kt` — Task 30: 20-scenario integration test UI
- Layout XML for RecyclerView in SettingsActivity (skill library browser)
- `activity_onboarding.xml` needs animated 3-step permission flow

Fixes needed:
- `OnboardingActivity`: add `PermissionCheckWorker` that polls every 2s while waiting for accessibility grant
- `PlanPreview.confirm()`: add `buildPlanSentence()` for multi-step skills
- `OverlayUI`: verify `TYPE_APPLICATION_OVERLAY` works correctly with `SYSTEM_ALERT_WINDOW` permission (this is correct for Android 12+, not accessibility overlay)

Skills: kotlin-reviewer, code-reviewer, tdd

---

### Module 11 — Final Wiring (Task 40)
**Goal:** Connect all modules into one working system.

Wiring checklist:
1. `SamsungCompatibilityLayer.apply(ctx)` called in `OmnixAccessibilityService.onServiceConnected()`
2. `AppPreLauncher.warmUp()` called in `VoicePipeline.onWakeWordDetected()`
3. `AnomalyDetector.score()` (from ProactiveAssistant) called before every financial step
4. `OmnixProfiler.start/end()` wrapping every major operation
5. `ContextManager.compactContextIfNeeded()` called in Orchestrator before each Gemma call
6. `OmnixMesh.advertise()` started in OmnixMeshService
7. All skill libraries seeded via `SkillLibrary.seedAll(db)` in OnboardingActivity

Skills: code-reviewer, security-reviewer, verification-before-completion

---

### Module 12 — GitHub Setup
**Steps:**
1. Generate keystore: `keytool -genkey -v -keystore omnix-release.jks -alias omnix -keyalg RSA -keysize 2048 -validity 10000`
2. `gh repo create omnix-agent --private --source=. --remote=origin`
3. `git push -u origin master`
4. Set secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
5. Leave `PORCUPINE_KEY` placeholder with instructions

**Porcupine key instructions for user (Task 6 dependency):**
1. Go to console.picovoice.ai
2. Sign up / log in
3. Copy the AccessKey from dashboard (free tier, unlimited for personal use)
4. Run: `gh secret set PORCUPINE_KEY`

---

## Skills Used Per Module (master reference)

| Module | kotlin-reviewer | code-reviewer | security-reviewer | database-reviewer | performance-optimizer | tdd | verify |
|--------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| 0 Build | | ✓ | | | | | |
| 1 database | ✓ | ✓ | | ✓ | | ✓ | ✓ |
| 2 ai | ✓ | ✓ | | | | ✓ | ✓ |
| 3 core | ✓ | ✓ | ✓ | | | ✓ | ✓ |
| 4 voice | ✓ | ✓ | | | | ✓ | ✓ |
| 5 discovery | ✓ | ✓ | | | | ✓ | ✓ |
| 6 skills | ✓ | ✓ | ✓ | | | ✓ | ✓ |
| 7 executor | ✓ | ✓ | ✓ | | | ✓ | ✓ |
| 8 improvements | ✓ | ✓ | | | ✓ | ✓ | ✓ |
| 9 mesh | ✓ | ✓ | | | | ✓ | ✓ |
| 10 ui | ✓ | ✓ | | | | ✓ | ✓ |
| 11 wiring | | ✓ | ✓ | | | | ✓ |
| 12 GitHub | | | | | | | |

---

## Files to Create (net new, alphabetical)

```
app/src/main/kotlin/com/omnix/agent/
  ai/EncryptedPrefsManager.kt
  ai/ModelDownloadManager.kt              (replaces ModelDownloadWorker)
  database/APKKnowledgeEntity.kt
  database/ExecutionHistoryEntity.kt
  discovery/DiscoveryTestActivity.kt
  improvements/ContextManager.kt
  improvements/OmnixProfiler.kt
  improvements/ProactiveAssistant.kt
  mesh/OmnixMesh.kt
  skills/BankingSkillLibrary.kt           (replaces BankingSkills.kt)
  skills/ContactsReader.kt
  skills/CorrectionLearner.kt
  skills/EmergencyWorkflow.kt             (replaces EmergencySOSSkill.kt)
  skills/ScheduledTaskManager.kt
  skills/SkillLibrary.kt
  skills/SkillMatcher.kt
  skills/SkillRegistry.kt
  skills/StockClient.kt
  ui/SystemTestActivity.kt
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
app/src/main/res/mipmap-*/ic_launcher.png (all densities)
local.properties
docs/superpowers/specs/2026-04-06-omnix-complete-build-design.md (this file)
```

## Files to Delete

```
app/src/main/kotlin/com/omnix/agent/BuildConfig.kt   (conflicts with Gradle)
```

## Files to Significantly Fix

```
TTS.kt                    — Locale.US → Locale("en","IN")
ModelDownloadWorker.kt    — replace with Android DownloadManager
VoicePipeline.kt          — .ppn path fix + warmUp() call
SelfHealingSystem.kt      — add permanent skill update
EventTriggerEngine.kt     — implement all 7 triggers
OmnixOrchestrator.kt      — add CorrectionLearner.applyOverrides()
HumanBehaviorSimulator.kt — integrate into SkillExecutor
AppPreLauncher.kt         — add HourlyUsageModel
SamsungCompatibilityLayer — add Galaxy AI 50ms delay fix
APKAnalyzer.kt            — fix parseBinaryXml()
BankingSkills.kt          → rename to BankingSkillLibrary.kt + 3 more banks
```

---

## Constraints (non-negotiables from spec)

- minSdk 31 (Android 12) — HARD
- arm64-v8a ABI only — HARD
- Model NOT bundled in APK — HARD
- No Play Store — sideload only
- Financial actions ALWAYS need ConfirmationGate — HARD
- SOS must complete in ≤ 5 seconds — HARD
- All coroutines on `Dispatchers.IO` for DB/network, `Dispatchers.Main` for UI — HARD
- No root required anywhere — HARD
