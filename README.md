# ✨ Grammar AI for Android

[![Android](https://img.shields.io/badge/Platform-Android_7.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![Gemini API](https://img.shields.io/badge/Google_Gemini-3.5_Flash_Lite-4285F4.svg)](https://ai.google.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An intelligent, system-wide grammar and language assistant for Android specializing in **Dutch (Nederlands)** and **English**. Powered by Google's latest **Gemini API** (`gemini-3.5-flash-lite`), Grammar AI enables real-time spelling correction, phrasing improvements, grammar explanations, and Persian translations inside **any app** on your phone.

---

## 🚀 Key Features

* **Dual Operation Modes:**
  1. **System Text Menu (`PROCESS_TEXT`):** Select text in any app (WhatsApp, Chrome, Notes, etc.) and tap `✨ Grammar AI` from the popup menu next to Copy/Cut.
  2. **Floating Assistant Bubble (Grammarly Style):** An interactive floating bubble powered by Android Accessibility Service that detects typing fields in apps like Telegram, WhatsApp, and Instagram, allowing 1-tap grammar fixes and direct text replacement.
* **Smart Grammar Engine:**
  * Auto-detects whether your text is Dutch, English, or a mix.
  * Corrects spelling, grammar, word order, and unnatural expressions.
  * Provides a clear summary of what was changed and why.
  * Includes a Persian translation of the corrected sentence for learners.
* **Powered by Gemini 3.5 Flash Lite:**
  * Ultra-fast sub-second response times.
  * High free-tier daily quota (500 requests/day, 15 RPM).
  * Dynamic model discovery with automatic fallback.
* **In-App Playground & Customization:**
  * Test prompts and models directly inside the settings screen.
  * Fully customizable prompt templates.
  * Secure local storage of your Google Gemini API key via Encrypted/Private SharedPreferences.

---

## 📱 How to Use

### Mode 1: Context Menu (`✨ Grammar AI`)
1. Select any text in an app (e.g. WhatsApp, Chrome, Gmail).
2. In the floating text selection menu (or inside the `⋮` overflow menu), tap **`✨ Grammar AI`**.
3. Review the corrected sentence and explanations.
4. Tap **Replace in Text** to update your text instantly, or **Copy** to clipboard.

### Mode 2: Floating Bubble Mode (Grammarly Mode)
1. Open **Grammar AI** and tap **Configure Permissions**.
2. Enable **Display over other apps** and **Accessibility Service** for Grammar AI.
3. A floating bubble appears when you focus any text input field across Android.
4. Tap the bubble anytime to inspect, correct, and replace your draft with a single tap.

> [!NOTE]
> **Android 13+ Restricted Settings Guide:**  
> When sideloading APK files outside Google Play, Android locks accessibility permissions by default. To unlock:
> 1. Go to **Settings → Apps → Grammar AI**.
> 2. Tap the **three dots (`⋮`)** in the top right corner.
> 3. Select **"Allow restricted settings"** and confirm your fingerprint/PIN.
> 4. Return to the app and toggle the permissions on!

---

## 🛠️ Tech Stack & Architecture

* **Language:** Kotlin
* **UI & Layout:** Material Design 3, ViewBinding, Dynamic Custom Dialogs
* **Networking & Async:** OkHttp 4, Kotlin Coroutines
* **AI Provider:** Google Gemini API (`v1beta` models endpoint with JSON structured output)
* **Android Services:**
  * `ProcessTextActivity`: Handles `android.intent.action.PROCESS_TEXT`
  * `GrammarAccessibilityService`: Handles `AccessibilityService` events and `WindowManager` floating overlays
* **CI/CD:** Automated GitHub Actions workflow compiling signed/debug APK artifacts on push

---

## 📦 Getting Started

### Prerequisites
* Android device running Android 7.0 (Nougat, API 24) or higher.
* A free Gemini API key from [Google AI Studio](https://aistudio.google.com/).

### Installation
1. Download the latest `GrammarAI-debug-apk` from [GitHub Releases](https://github.com/salmansoltaniyan/GrammarAI/releases) or GitHub Actions artifacts.
2. Install the APK on your device.
3. Open **Grammar AI**, paste your Gemini API Key, and tap **Save Key**.

---

## 🔒 Privacy & Security

* Your Gemini API Key is stored strictly on your local device.
* Grammar AI only sends the text you explicitly select or request to check to the Google Gemini API.
* No telemetry, analytics, or background tracking data is collected.

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
