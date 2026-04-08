---
name: OMNIX Android Project
description: OMNIX autonomous AI agent for Android 12+ - project status and architecture
type: project
---

OMNIX is an on-device autonomous AI agent for Android 12+ targeting Samsung S25 Ultra.

**Why:** User wants to build a complete autonomous agent app that reads/controls any Android app via AccessibilityService, uses Gemma 4 on-device LLM, and executes tasks by voice.

**How to apply:** This is the primary active project. All code is in c:\Users\kalya\OneDrive\Documents\omnix\omnix-code\

**Key specs:**
- Package: com.omnix.agent, minSdk 31, arm64-v8a only
- LiteRT-LM 1.0.0 for Gemma 4 E2B inference
- Porcupine 3.0.1 for "Hey OMNIX" wake word
- Room 2.6.1 database, 7 entities
- GitHub Actions CI for signed APK releases

**Status (2026-04-06):** Git initialized with 2 commits, 42 Kotlin files built.
All 40 tasks from spec implemented at architecture level. Needs:
- Gradle wrapper binary (gradlew + jar)
- GitHub remote to be created and pushed
- WhatsApp/messaging skills (Task 23)
- Full EventTriggerEngine 7 trigger implementations (Task 20)
- Integration tests (Task 15, 30)
- PORCUPINE_KEY secret from picovoice.ai console

**Documents:**
- OMNIX_AI_Dev_Prompts_v3_COMPLETE_MERGED.docx - 40 tasks
- OMNIX_Android_Impl_Spec_v3_COMPLETE_MERGED.docx - code templates
- OMNIX_Architecture_v3_COMPLETE_MERGED.docx - architecture
