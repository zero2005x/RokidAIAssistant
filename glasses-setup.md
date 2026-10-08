---
layout: default
title: Glasses setup guide – Glasses AI Companion
permalink: /glasses-setup/
---

# Glasses setup guide

[English](#english) · [繁體中文](#繁體中文)

---

## English

**Glasses AI Companion** is an unofficial app. It is not made by, endorsed by, or affiliated with Rokid.

You do **not** need glasses to use the app. Without them you can still chat with an AI service, analyse photos and record audio on your phone. This page is only for people who also want to use it with Rokid glasses.

### What you need

- The **Glasses AI Companion** app on your phone, with an AI service and your own API key set up in Settings.
- Rokid glasses that run Android and can install an app, and a way to install an app on them. This is typically done with a computer and `adb`, so it is aimed at people comfortable with developer tools.
- The **glasses-side companion app** from this project. It is a separate app and is **not** installed from Google Play.

### Steps

1. Download the glasses app (`rokid-glasses-v<version>.apk`) **only** from this project's official releases page: <https://github.com/zero2005x/RokidAIAssistant/releases>. Each release includes a `SHA256SUMS.txt` file; compare the checksum with the file you downloaded.
2. Install it on your glasses, for example with `adb install` while the glasses are connected to your computer. The exact way to enable developer mode and `adb` depends on your glasses model and firmware, so check Rokid's documentation for your device.
3. Pair the glasses with your phone in the phone's Bluetooth settings, if they are not paired already.
4. Open the glasses app on the glasses.
5. On your phone, open Glasses AI Companion and start the glasses connection from the Home screen. Grant the Bluetooth permission when asked.

If the connection does not work, make sure the glasses are not currently connected to another phone or app, and try again. Step details can vary between glasses models and firmware versions.

### Safety and privacy

- Install apps on your glasses only from sources you trust.
- Ask for permission before recording or photographing other people.
- See the [privacy policy](/RokidAIAssistant/privacy/) for what the app does with your data.

---

## 繁體中文

**Glasses AI Companion** 是非官方 App，並非由 Rokid 製作、背書，也與 Rokid 沒有隸屬關係。

使用這個 App **不需要**眼鏡。沒有眼鏡時，您仍可在手機上與 AI 服務聊天、分析照片和錄音。本頁僅供也想搭配 Rokid 眼鏡使用的人參考。

### 您需要準備

- 手機上的 **Glasses AI Companion**，並已在「設定」中選好 AI 服務、填入您自己的 API 金鑰。
- 能執行 Android 並可安裝 App 的 Rokid 眼鏡，以及在眼鏡上安裝 App 的方法。通常需透過電腦與 `adb` 完成，所以適合熟悉開發者工具的使用者。
- 本專案的**眼鏡端配套 App**。它是獨立的 App，**不是**從 Google Play 安裝。

### 步驟

1. **只**從本專案的官方發行頁下載眼鏡端 App（`rokid-glasses-v<版本>.apk`）：<https://github.com/zero2005x/RokidAIAssistant/releases>。每個版本都附有 `SHA256SUMS.txt` 校驗檔，請將其中的校驗碼與您下載的檔案比對。
2. 將它安裝到眼鏡上，例如在眼鏡連接電腦時使用 `adb install`。如何開啟開發者模式與 `adb` 取決於您的眼鏡型號與韌體，請參考 Rokid 對您裝置的說明文件。
3. 如果眼鏡尚未與手機配對，請先在手機的藍牙設定中完成配對。
4. 在眼鏡上開啟眼鏡端 App。
5. 在手機上開啟 Glasses AI Companion，從首頁啟動眼鏡連線，並在系統詢問時授予藍牙權限。

如果無法連線，請確認眼鏡目前沒有連著其他手機或 App，然後再試一次。實際步驟可能因眼鏡型號與韌體版本而異。

### 安全與隱私

- 只從您信任的來源在眼鏡上安裝 App。
- 錄音或拍攝他人之前，請先徵得對方同意。
- 想了解 App 如何處理您的資料，請參閱[隱私權政策](/RokidAIAssistant/privacy/)。
