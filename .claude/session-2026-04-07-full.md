# OMNIX Full Dev Session — 2026-04-07 (Complete)

**Project:** OMNIX — On-device Autonomous AI Agent for Android (Samsung Galaxy S25 Ultra)
**Branch:** `master`
**Session duration:** ~00:15 – 02:00 IST

---

## Phase 1: Project Inception

### User Input
User uploaded three spec documents:
- `OMNIX_AI_Dev_Prompts_v3_COMPLETE_MERGED.docx`
- `OMNIX_Android_Impl_Spec_v3_COMPLETE_MERGED.docx`
- `OMNIX_Architecture_v3_COMPLETE_MERGED.docx`

Request: "read all three and understand and create my project — complete task one by one and let me know what tasks are left"

### Outcome
- Read and understood all three specification documents
- Identified OMNIX as an on-device AI agent that:
  - Uses wake word detection (Hey OMNIX)
  - Offline ASR (speech-to-text)
  - Local LLM inference (Gemma 4 E2B via MediaPipe)
  - Android Accessibility Service for UI automation
  - Skill execution engine (tap/type/scroll/navigate)
  - Multi-device mesh networking
  - App discovery & APK analysis
  - Proactive assistant + self-healing

---

## Phase 2: Planning & Design

### Skills Used
- `brainstorming` — mapped OMNIX idea into full product design
- `writing-plans` — created comprehensive implementation plan
- `subagent-driven-development` — dispatched subagents per task with spec compliance review

### Documents Created
- `docs/superpowers/specs/2026-04-06-omnix-complete-build-design.md` — full spec with:
  - Architecture overview
  - Sprint breakdown (all tasks, no time estimates)
  - File-by-file implementation plan
  - API/SDK decisions
  - Risk analysis

### Key Architecture Decisions Made During Planning

| Decision | Choice | Reason |
|----------|--------|--------|
| Wake word | Porcupine → replaced with Vosk | Porcupine is paid; Vosk is free Apache 2.0 |
| ASR | Whisper → replaced with Vosk | Vosk runs offline, no Python runtime needed |
| LLM | Gemma 4 E2B via MediaPipe LiteRT | On-device, ~2.6 GB, no cloud dependency |
| DB | Room 2.6.1 | Standard Android persistence |
| Background work | WorkManager 2.9.0 | Handles large file downloads, survives process death |
| Secure storage | EncryptedSharedPreferences (AES-256-GCM) | API keys, HF token |

---

## Phase 3: GitHub Repository Setup

### User Request
"Create a private GitHub repo, push it"

### Actions Taken
- Created private repo: `gh repo create omnix-agent --private`
- Added remote: `git remote add origin <url>`
- Pushed all code: `git push -u origin master`
- Added `.github/workflows/build.yml` for CI (signed APK builds via GitHub Actions)

### GitHub Actions CI
```yaml
# Triggers on push to master
# Builds arm64-v8a release APK
# Signs with keystore (KEYSTORE_* secrets)
# Uploads APK as artifact for download
```

---

## Phase 4: Core Project Build

### Gradle Setup
Files created/fixed:
- `settings.gradle` — plugin management, repo declarations
- `build.gradle` — root project plugins
- `gradle.properties` — JVM args, Kotlin options, AndroidX
- `app/build.gradle` — full dependency list, ABI filter (arm64-v8a), signing config

### Key Dependencies
```groovy
// AI
implementation("com.google.mediapipe:tasks-genai:0.10.22")

// ASR (offline, free)
implementation("com.alphacephei:vosk-android:0.3.47@aar") { transitive = true }

// Database
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// Background work
implementation("androidx.work:work-runtime-ktx:2.9.0")

// Secure storage
implementation("androidx.security:security-crypto:1.1.0-alpha06")

// Serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

### ProGuard Rules (`app/proguard-rules.pro`)
```
-keep class * extends androidx.work.ListenableWorker { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keep class com.omnix.agent.database.** { *; }
-keep class com.omnix.agent.executor.SkillStep { *; }
```

---

## Phase 5: All Source Files Created

### Package Structure
```
com.omnix.agent/
├── core/
│   ├── OmnixAccessibilityService.kt   — A11y service (tap/type/scroll/swipe/find)
│   ├── OmnixBootReceiver.kt           — Auto-start on boot
│   └── OmnixNotificationService.kt    — Notification listener
├── ai/
│   ├── GemmaInferenceEngine.kt        — MediaPipe LlmInference wrapper
│   ├── ModelDownloadManager.kt        — GemmaDownloadWorker + download logic
│   └── EncryptedPrefsManager.kt       — Secure key/token storage
├── voice/
│   ├── VoicePipeline.kt               — Wake word + ASR orchestration
│   ├── ASREngine.kt                   — Vosk offline speech recognition
│   ├── OmnixVoiceService.kt           — Foreground mic service
│   └── TTS.kt                         — Text-to-speech (en-IN locale)
├── executor/
│   ├── OmnixOrchestrator.kt           — Intent → skill routing
│   └── SkillExecutor.kt               — Step-by-step skill execution
├── skills/
│   ├── SkillLibrary.kt                — Pre-built skills (WhatsApp, maps, etc.)
│   ├── BankingSkillLibrary.kt         — Banking/UPI skills
│   ├── SkillLibraryManager.kt         — Skill seeding
│   └── CorrectionLearner.kt           — Learn from user corrections
├── discovery/
│   ├── APKAnalyzer.kt                 — Parse APK metadata
│   ├── DiscoveryEngine.kt             — Discover installed apps
│   ├── OmnixDiscoveryService.kt       — Foreground discovery service
│   ├── NewAppReceiver.kt              — Trigger discovery on new install
│   └── DiscoveryTestActivity.kt       — On-device integration test activity
├── improvements/
│   ├── ProactiveAssistant.kt          — Suggest actions proactively
│   └── SelfHealingSystem.kt           — Retry failed steps with alternatives
├── database/
│   ├── Entities.kt                    — SkillEntity, ActionHistoryEntity, APKKnowledgeEntity, ScreenCrawlEntity
│   ├── Daos.kt                        — Room DAOs
│   └── OmnixDatabase.kt              — Room DB v2
├── mesh/
│   └── OmnixMeshService.kt            — Multi-device mesh (connectedDevice)
└── ui/
    ├── OnboardingActivity.kt          — Setup wizard (permissions + downloads)
    ├── SettingsActivity.kt            — App settings
    ├── OverlayUI.kt                   — Floating overlay during skill execution
    ├── ConfirmationGate.kt            — User approval dialogs
    └── SystemTestActivity.kt          — 20-scenario integration tests
```

---

## Phase 6: Build Failures & Fixes

### Error 1 — Gradle Configuration
**Symptoms:** Build failed, missing dependencies
**Fix:** Added all missing `implementation(...)` declarations, fixed `ksp()` vs `kapt()` usage for Room

### Error 2 — Room @Blob Annotation
**Symptom:** `@ColumnInfo(typeAffinity = ColumnInfo.BLOB)` compile error
**Fix:** Correct annotation is `@ColumnInfo(typeAffinity = ColumnInfo.BLOB)` — verified field types

### Error 3 — Database Version
**Symptom:** Schema mismatch crash
**Fix:** Bumped database version to v2, added `fallbackToDestructiveMigration()` for dev builds

### Error 4 — WorkManager SystemForegroundService
**Symptom:** Release lint error `SpecifyForegroundServiceType`
**Fix:** Added to AndroidManifest.xml:
```xml
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

### Error 5 — Manifest Package Attribute Deprecation
**Fix:** Removed `package="com.omnix.agent"` from `<manifest>` root (now in `build.gradle` `namespace`)

---

## Phase 7: Phone Testing Setup

### User Question
"How to test this in my phone — connect phone to PC or use GitHub Actions?"

### Answer Given
- **Debug APK via ADB** = fastest for development (direct install, can see logcat)
- **Signed APK via GitHub Actions** = better for accessibility services (some Android versions restrict unsigned app accessibility)
- Recommended: use debug APK first to verify it works, then switch to signed

### User chose: signed APK, then connected phone via USB

### ADB Setup
```bash
ADB="c:/Users/kalya/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices  # verify device detected
"$ADB" install -r app-arm64-v8a-debug.apk
```

---

## Phase 8: App Crash on Launch

### Symptom
"omnix is crashing its not even opening what the hell have you done tested?"

### Root Cause
`OmnixOrchestrator.initialize(this)` called in `OnboardingActivity.onCreate()` before the database was ready, plus `GemmaInferenceEngine.initialize()` called too early.

### Fix
- Moved `GemmaInferenceEngine.initialize(this)` to `startOmnix()` (called only when user taps "Start OMNIX")
- Deferred skill seeding until after permissions granted
- Fixed null context issues in `ASREngine`
- Fixed `VoicePipeline` loading `.ppn` from `filesDir` instead of assets
- Fixed TTS locale to `en-IN`
- Fixed `warmUp()` call on wake word detection

### Samsung S25 Ultra Specific Fixes
- Added `takeScreenshotCompat()` for API 31+
- Fixed 50ms Samsung Galaxy AI event timing issue

---

## Phase 9: SkillExecutor Complete Rewrite

### Root Cause Found
100% of pre-built skills were silently failing. Investigation revealed:

The `SkillStep` Kotlin data class used camelCase field names:
```kotlin
// BROKEN — Kotlinx Serialization couldn't map JSON snake_case → Kotlin camelCase
data class SkillStep(
    val resourceId: String? = null,    // JSON has "resource_id"
    val packageName: String? = null,   // JSON has "package"
    val outputKey: String? = null,     // JSON has "output_key"
    ...
)
```

But ALL pre-built skills in `SkillLibrary.kt` and `BankingSkillLibrary.kt` use snake_case JSON:
```json
{"action": "tap", "resource_id": "com.whatsapp:id/send_btn"}
{"action": "launch", "package": "com.whatsapp"}
{"action": "capture", "output_key": "balance"}
```

### Fix — Added @SerialName Everywhere
```kotlin
@Serializable
data class SkillStep(
    val action: String,
    val element: ElementSelector? = null,
    @SerialName("resource_id") val resourceId: String? = null,
    val text: String? = null,
    @SerialName("content_desc") val contentDesc: String? = null,
    val desc: String? = null,
    val value: String? = null,
    @SerialName("package") val packageName: String? = null,
    val uri: String? = null,
    val template: String? = null,
    val phone: String? = null,
    @SerialName("output_key") val outputKey: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long = 8000,
    @SerialName("clear_first") val clearFirst: Boolean = false,
    @SerialName("delay_after_ms") val delayAfterMs: Long = 200,
    val narration: String = ""
)
```

### Missing Action Handlers Added
Previously `executeStep()` only handled `tap` and `type`. Added:

| Action | What it does |
|--------|-------------|
| `tap_text` | Find node by text label, tap it |
| `tap_content_desc` | Find node by content description, tap it |
| `launch` / `launch_app` | Start app by package name |
| `deep_link` | Open URI via `ACTION_VIEW` intent |
| `wait_for` / `wait_element` | Wait for element by resource ID (up to timeoutMs) |
| `wait_for_text` | Wait for element by text |
| `capture` / `read_text` | Read node text → store in output key |
| `read_screen_text` | Read all visible text → store in output key |
| `speak` | TTS speak with `{param}` substitution |
| `dial` | Call phone number via `ACTION_CALL` |
| `scroll_down` / `scroll_up` | Scroll a specific node |
| `swipe_down` / `swipe_up` | Full-screen swipe gestures |
| `press_back` / `press_home` | Navigation |
| `wait` | Pause for delayAfterMs |

### resolvedElement() Helper Added
Synthesizes `ElementSelector` from either nested format (new skills) or flat format (pre-built skills):
```kotlin
fun resolvedElement(): ElementSelector? {
    if (element != null) return element
    val rid = resourceId ?: ""
    val t   = text
    val cd  = contentDesc ?: desc
    if (rid.isEmpty() && t == null && cd == null) return null
    return ElementSelector(resourceId = rid, text = t, contentDesc = cd)
}
```

---

## Phase 10: Gemma Model Download — Complete Rework

### Original Problem
`ModelDownloadWorker.kt` used `DownloadManager.setDestinationUri(Uri.fromFile(filesDir/...))` which throws `SecurityException` on Android 10+ — internal storage (`filesDir`) is not allowed as a download destination.

### Fix — WorkManager + URL.openStream()
Replaced with `GemmaDownloadWorker` (`CoroutineWorker`):
- Uses `URL.openConnection()` → `InputStream` → writes directly to `filesDir`
- Handles HuggingFace CDN redirects manually (up to 10 hops)
- Shows download progress via system notification (MB downloaded / total MB)

### Additional Issue — HuggingFace 401 Auth Error
The Gemma 4 E2B model is a "gated" model on HuggingFace. Every download attempt returned HTTP 401 immediately because:
- No `Authorization` header was sent
- The model requires: HF account + Google Gemma terms acceptance

**Fix — HuggingFace Token Flow:**
1. Added `PREF_KEY_HF_TOKEN`, `saveHfToken()`, `getHfToken()` to `EncryptedPrefsManager`
2. Token stored encrypted (AES-256-GCM via EncryptedSharedPreferences)
3. `GemmaDownloadWorker` reads token → adds `Authorization: Bearer <token>` header
4. `OnboardingActivity.showModelDownloadDialog()` prompts user for token with instructions

### Additional Issue — "Waiting for Wi-Fi" Even on Connected Network
WorkManager constraint was `NetworkType.UNMETERED` (Wi-Fi only). If device was on mobile data, hotspot, or WorkManager misdetected the connection → job stayed queued forever.

**Fix:** Changed to `NetworkType.CONNECTED` (any network) + `ExistingWorkPolicy.REPLACE` (cancels stuck jobs):
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)  // was: UNMETERED
    .build()
WorkManager.getInstance(context)
    .enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.REPLACE, request)  // was: KEEP
```

---

## Phase 11: Onboarding UI — Download Progress

### Problem
User: "I couldn't see how much it is downloaded and how much needs to be"

The original `OnboardingActivity` had no progress bar and never observed WorkManager state.

### Fix — Layout Changes (`activity_onboarding.xml`)
Added:
```xml
<ProgressBar
    android:id="@+id/progress_download"
    style="?android:attr/progressBarStyleHorizontal"
    android:max="100"
    android:visibility="gone" />

<TextView
    android:id="@+id/tv_download_status"
    android:visibility="gone" />

<!-- Step 4: Vosk model status -->
<TextView android:id="@+id/tv_vosk_status" />
```

### Fix — WorkManager Live Observation (`OnboardingActivity.kt`)
```kotlin
private fun observeGemmaDownload() {
    WorkManager.getInstance(this)
        .getWorkInfosByTagLiveData("gemma_download")
        .observe(this) { infos ->
            val info = infos?.firstOrNull() ?: return@observe
            when (info.state) {
                ENQUEUED, BLOCKED -> {
                    progressBar.isIndeterminate = true
                    tvDownloadStatus.text = "Queued — waiting for network…"
                    btnDownload.isEnabled = false
                }
                RUNNING -> {
                    // Read real byte progress pushed by worker via setProgress()
                    val pct   = info.progress.getInt("pct", -1)
                    val dlMb  = info.progress.getLong("downloaded_mb", 0)
                    val totMb = info.progress.getLong("total_mb", 0)
                    if (pct >= 0 && totMb > 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        tvDownloadStatus.text = "$pct% — ${dlMb} MB / ${totMb} MB downloaded"
                    } else {
                        progressBar.isIndeterminate = true
                        tvDownloadStatus.text = "Connecting to HuggingFace…"
                    }
                    btnDownload.isEnabled = false
                }
                SUCCEEDED -> {
                    progressBar.visibility = View.GONE
                    btnDownload.text = "✓ Gemma Model Ready"
                    checkAndProgress()
                }
                FAILED -> {
                    tvDownloadStatus.text = "Download failed — check network and retry"
                    btnDownload.isEnabled = true
                }
            }
        }
}
```

### Worker pushes real progress via setProgress()
```kotlin
// Inside GemmaDownloadWorker.doWork() download loop
setProgress(
    workDataOf(
        "pct" to pct,
        "downloaded_mb" to mb(downloaded),
        "total_mb" to mb(total)
    )
)
```

---

## Summary of All Files Modified

| File | Changes |
|------|---------|
| `settings.gradle` | Repo declarations |
| `build.gradle` | Plugin versions |
| `gradle.properties` | JVM args, Kotlin options |
| `app/build.gradle` | All deps, ABI filter, signing |
| `app/proguard-rules.pro` | Keep rules: WorkManager, Room, Serialization |
| `.github/workflows/build.yml` | CI: signed APK build + upload artifact |
| `AndroidManifest.xml` | SystemForegroundService, DiscoveryTestActivity, removed package= |
| `res/xml/accessibility_service_config.xml` | A11y config |
| `res/values/strings.xml` | App strings |
| `res/values/themes.xml` | OMNIX theme |
| `res/values/colors.xml` | Color palette |
| `res/layout/activity_onboarding.xml` | Progress bar, status TVs, Vosk step |
| `database/Entities.kt` | SkillEntity, ActionHistoryEntity, APKKnowledgeEntity, ScreenCrawlEntity |
| `database/Daos.kt` | Room DAOs for all entities |
| `database/OmnixDatabase.kt` | Room DB v2 with migration |
| `core/OmnixAccessibilityService.kt` | tap/type/scroll/swipe/find/waitFor |
| `core/OmnixBootReceiver.kt` | Boot auto-start |
| `ai/GemmaInferenceEngine.kt` | MediaPipe LlmInference wrapper |
| `ai/ModelDownloadManager.kt` | GemmaDownloadWorker, HF Bearer auth, real progress, CONNECTED network |
| `ai/EncryptedPrefsManager.kt` | Added HF token: saveHfToken/getHfToken/hasHfToken |
| `voice/VoicePipeline.kt` | Wake word (.ppn from filesDir) + ASR pipeline |
| `voice/ASREngine.kt` | Vosk offline ASR |
| `voice/OmnixVoiceService.kt` | Foreground mic service |
| `voice/TTS.kt` | TTS with en-IN locale |
| `ui/OverlayUI.kt` | Floating overlay during skill execution |
| `ui/ConfirmationGate.kt` | User approval dialog |
| `ui/OnboardingActivity.kt` | HF token dialog, real progress bar, Vosk status, WorkManager observation |
| `core/OmnixBootReceiver.kt` | Boot receiver |
| `discovery/NewAppReceiver.kt` | PACKAGE_ADDED / PACKAGE_REPLACED receiver |
| `discovery/APKAnalyzer.kt` | APK metadata extraction |
| `discovery/DiscoveryEngine.kt` | App discovery + DB storage |
| `discovery/OmnixDiscoveryService.kt` | Foreground discovery service (dataSync) |
| `discovery/DiscoveryTestActivity.kt` | On-device integration test activity |
| `executor/SkillExecutor.kt` | COMPLETE REWRITE — @SerialName, all 20+ action types, resolvedElement() |
| `skills/SkillLibraryManager.kt` | Skill seeding |

---

## Key Constants Reference

| Item | Value |
|------|-------|
| Gemma model path | `filesDir/models/gemma-4-e2b.litertlm` |
| Gemma model URL | `https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b.litertlm` |
| Gemma model size | ~2.6 GB |
| Vosk model path | `filesDir/whisper/` |
| Vosk model URL | `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` |
| Vosk model size | ~40 MB |
| WorkManager tag | `gemma_download` |
| Notification channel | `omnix_download` |
| Notification ID | `201` |
| DB version | 2 |
| Package name | `com.omnix.agent` |
| minSdk | 31 |
| targetSdk | 35 |
| ABI | arm64-v8a only |

---

## How to Build & Install

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.8.7-hotspot"

# Compile check only (fast)
./gradlew app:compileDebugKotlin

# Full debug APK
./gradlew app:assembleDebug
# Output: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# Install to connected phone
ADB="c:/Users/kalya/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# Watch logs
"$ADB" logcat -s OMNIX WorkManager WM-WorkerWrapper
```

---

## Pending / Not Yet Done

- [ ] **HuggingFace one-time setup** — user must accept Gemma terms and get read token before download works
- [ ] **GemmaInferenceEngine** — verify `LlmInference.createFromFilePath()` works on S25 Ultra after model download completes
- [ ] **Vosk model extraction** — zip is downloaded but needs to be unzipped at runtime before ASR works
- [ ] **Pre-built skill end-to-end test** — now that `@SerialName` fix is in, verify WhatsApp/maps/banking skills execute correctly
- [ ] **Wake word .ppn file** — needs to be placed in `filesDir` (or bundled as asset); Porcupine replaced by Vosk but wake word detection still needs a solution
- [ ] **DiscoveryTestActivity** — run on-device integration tests
- [ ] **GitHub Actions CI secrets** — set `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` in repo Settings → Secrets for signed APK CI builds
- [ ] **Mesh networking** — `OmnixMeshService` stub exists, not fully implemented
- [ ] **Proactive assistant** — `ProactiveAssistant.start()` called but business logic needs fleshing out

---

## Architecture Diagram

```
User speaks "Hey OMNIX, open WhatsApp and message Ravi"
           ↓
[OmnixVoiceService — foreground, mic permission]
           ↓
[VoicePipeline]
  ├── WakeWordDetector (Vosk keyword)
  └── ASREngine (Vosk offline) → "open WhatsApp and message Ravi"
           ↓
[OmnixOrchestrator]
  ├── GemmaInferenceEngine (Gemma 4 E2B, MediaPipe)
  │     → intent: "send_whatsapp_message", params: {contact: "Ravi"}
  └── SkillLibrary.find("send_whatsapp_message")
           ↓
[SkillExecutor]
  ├── ConfirmationGate → "Send message to Ravi?" [YES/NO]
  ├── Step 1: launch {package: "com.whatsapp"}
  ├── Step 2: tap_text {text: "Search"}
  ├── Step 3: type {resource_id: "...", value: "Ravi"}
  ├── Step 4: tap {resource_id: "com.whatsapp:id/contact_row_container"}
  ├── Step 5: tap {resource_id: "com.whatsapp:id/entry"}
  ├── Step 6: type {resource_id: "com.whatsapp:id/entry", value: "{message}"}
  └── Step 7: tap {resource_id: "com.whatsapp:id/send"}
           ↓
[OmnixAccessibilityService]
  └── executes each tap/type on the real Android UI
           ↓
[TTS] "Done! Message sent to Ravi."
```

---

*Session saved: 2026-04-07 ~02:00 IST*
