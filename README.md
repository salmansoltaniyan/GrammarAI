# ✨ Grammar AI for Android

[![Android](https://img.shields.io/badge/Platform-Android_7.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![Gemini API](https://img.shields.io/badge/Google_Gemini-3.5_Flash_Lite-4285F4.svg)](https://ai.google.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An intelligent, system-wide language and grammar companion for Android specializing in **Dutch (Nederlands)** and **English**. Powered by Google's latest **Gemini API** (`gemini-3.5-flash-lite`), Grammar AI offers real-time grammar correction, intelligent vocabulary extraction, 1-tap translation for chat apps like WhatsApp, and simultaneous sync with **AnkiDroid** and **Google Sheets**.

---

## 🚀 Key Features

### 1. ✍️ Smart Writing Assistant & First-Word Target Language Inference
* **First-Word Language Inference:** Determine your desired output language from the very first word! If you type a Dutch starter word (e.g., *Ik*, *Het*, *Als*, *We*, *Waarom*, *Vandaag*...) and continue in Persian or English, Grammar AI automatically translates and writes the entire message in natural, fluent Dutch.
* **Granular Sentence Correction Scope:** Choose whether to correct:
  * **All sentences** (entire text)
  * **Last sentence only** (earlier sentences remain untouched as context)
  * **Last 2 sentences**
  * **Last 3 sentences**
* **System-Wide Floating Bubble:** Floats over any messaging app (WhatsApp, Telegram, Notes, etc.) whenever you tap an input field for instant 1-tap checks and direct text replacement.
* **Context Menu Integration (`PROCESS_TEXT`):** Select text anywhere and tap `✨ Grammar AI` from the system context menu.

### 2. 🌐 Quick Tap-to-Translate (Dedicated WhatsApp Bubble)
* **Floating Translate Bubble (🌐):** A lightweight, draggable floating bubble available at your fingertips.
* **1-Tap Clipboard Translation:** Tap the bubble right after copying a message in WhatsApp to instantly view:
  * Fluent Persian translation.
  * Direct 1-tap copy button.
  * Extracted key phrases and expressions.
* **Quick Paste (`📋 Paste New`):** Paste and translate any new message directly without leaving the dialog.

### 3. 📥 Simultaneous AnkiDroid & Google Sheets Vocabulary Sync
* **Automatic Key Vocabulary Extraction:** Extracts up to 3 useful words, idioms, or collocations from each message with lemma forms and Persian meanings.
* **Multi-Select Checkboxes:** Choose exactly which vocabulary items you want to keep.
* **Single-Click Dual Sync:**
  * **AnkiDroid:** Automatically creates 2-way flashcards (Dutch phrase & standalone example sentence on Front, Persian meanings on Back) in your preferred deck (default: `Dutch`).
  * **Google Sheets:** Logs 4 columns (`Dutch Phrase`, `Example Sentence`, `Persian Phrase Meaning`, `Persian Sentence Meaning`) via a simple Google Apps Script Web App.

### 4. ⚙️ In-App Configuration & Playground
* Built-in prompt editor and model selector.
* AnkiDroid connection status diagnostics and permission launcher.
* Google Sheets webhook URL configuration and test sync button.
* Correction scope selector and persistent keystore signing for smooth app updates.

---

## 📱 How to Use

### ✍️ Correcting Your Writing
1. **Floating Bubble:** Tap into any text field in WhatsApp or Telegram; the Grammar AI bubble appears. Tap it to review and replace your text.
2. **Text Selection:** Highlight text in any app, tap the `⋮` menu, and select **`✨ Grammar AI`**.

### 🌐 Translating Incoming Messages
1. Long-press and **Copy** any incoming message in WhatsApp.
2. Tap the **🌐 Translate Bubble** on your screen.
3. Review the Persian translation and select any vocabulary words you want to remember.
4. Tap **"Save Selected to Anki & Sheets"** to sync them immediately.

---

## 🛠️ Tech Stack & Architecture

* **Language & SDK:** Kotlin, Android SDK 34 (Minimum SDK 24 / Android 7.0+)
* **UI Framework:** Material Design 3, ViewBinding, Rounded Floating Windows
* **Networking & Coroutines:** OkHttp 4, Kotlin Coroutines & LifecycleScope
* **AI Provider:** Google Gemini API (`gemini-3.5-flash-lite`, `gemini-1.5-flash`, dynamic discovery fallback)
* **Integrations:**
  * **AnkiDroid API:** Direct ContentProvider integration using `com.ichi2.anki.api.AddContentApi`
  * **Google Sheets:** Webhook POST dispatch to Google Apps Script
* **CI/CD:** Automated GitHub Actions build pipeline with persistent debug keystore signing

---

## 📦 Getting Started

### Prerequisites
* Android device running Android 7.0+
* A free Gemini API key from [Google AI Studio](https://aistudio.google.com/)
* *(Optional)* [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) installed with **AnkiDroid API** enabled in *Settings → Advanced*.
* *(Optional)* A Google Apps Script web app URL deployed as *Anyone* access.

### Installation
1. Download the latest APK from the **Releases** section on GitHub.
2. Install the APK and launch **Grammar AI**.
3. Enter your Gemini API Key and tap **Save Key**.
4. In **Floating Assistant**, grant the required permissions (*Display over other apps* & *Accessibility Service*).

> [!NOTE]
> **Android 13+ Restricted Settings:**  
> When sideloading APK files outside Google Play, Android locks accessibility permissions by default. To unlock:  
> **Settings → Apps → Grammar AI → three dots (`⋮`) → "Allow restricted settings"**, then enable the Accessibility toggle.

---

## 🔒 Privacy & Security

* Your Gemini API Key is stored encrypted/locally on your phone via private SharedPreferences.
* Text is only transmitted to the Google Gemini API when you explicitly tap to check or translate.
* No background tracking, analytics, or telemetry data is collected.

---

## 📄 License
This project is open-source and distributed under the [MIT License](LICENSE).
