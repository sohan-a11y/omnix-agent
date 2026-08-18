# 📱 OMNIX Android AI Agent

> **On-Device Voice-Controlled Android Automation Powered by Google Gemma & Accessibility UI Driver**

![GitHub License](https://img.shields.io/github/license/sohan-a11y/omnix-agent?style=flat-square)
![GitHub Last Commit](https://img.shields.io/github/last-commit/sohan-a11y/omnix-agent?style=flat-square)

[![Skills](https://skillicons.dev/icons?i=kotlin,android,java)](https://skillicons.dev)

OMNIX Android Agent is a native Android application that runs local LLM inference (Google Gemma via MediaPipe) and executes complex device tasks through accessibility view tree parsing and voice recognition.

---

## 🌟 Key Features

- 📱 **On-Device LLM Inference**: Runs Google Gemma 2B models locally with zero cloud API latency.
- 🎙️ **Voice Command Recognition**: Hands-free speech-to-intent pipeline for device control.
- ♿ **Accessibility UI Parser**: Inspects screen layouts, clicks buttons, and inputs text dynamically without root access.
- 🔍 **APK Skill Discovery**: Scans installed applications to synthesize available automation tools.

---

## 📁 Project Structure

```
omnix-agent/
├── app/
│   ├── src/main/java/     # Kotlin source code (LLM Engine, Accessibility Service, Voice UI)
│   ├── src/main/res/      # Layout XMLs & resources
│   └── build.gradle       # App module dependencies (MediaPipe, AndroidX)
├── config/                # Model quantization & prompt configurations
└── docs/                  # Architecture specs & integration guides
```

---

## 🚀 Build & Run

```bash
# Clone repository
git clone https://github.com/sohan-a11y/omnix-agent.git
cd omnix-agent

# Build APK via Gradle
./gradlew assembleDebug
```

---

## 📜 License

MIT License — see `LICENSE` for details.


---

<div align="center">

**Built by [M Sai Sohan (@sohan-a11y)](https://github.com/sohan-a11y)**

*If you find this project useful, please consider giving it a ⭐ on GitHub!*

</div>
