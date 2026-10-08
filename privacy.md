---
layout: default
title: Privacy Policy – Glasses AI Companion
permalink: /privacy/
---

# Privacy Policy – Glasses AI Companion

[English](#english) · [繁體中文](#繁體中文)

---

## English

**Last updated:** 8 October 2026

**App:** Glasses AI Companion (an unofficial AI companion app for Rokid glasses; not affiliated with Rokid)
**Developer:** zero2005x
**Contact:** 8jn6u1vhy@mozmail.com

This policy covers the **Google Play version** of the app. The separate build that is sideloaded from GitHub offers extra options (more AI services, an optional online text-to-speech engine and an optional Rokid SDK connection). It follows the same principles, and its details are described in the project README.

### In short

- You bring your own AI service and your own API key. Your voice, photos and text go **only** to the AI service you choose.
- The developer does **not** receive your conversations, photos, recordings or API keys, and runs no accounts, ads or analytics.
- The one exception is when **you** tap "report" on an AI response: that report is sent to the developer (details below).
- Your conversations, photos and recordings stay on your phone. The Google Play version is excluded from Android cloud backup.

### What the app processes, and where it goes

| Data | What for | Where it goes |
| --- | --- | --- |
| Text you type, and earlier messages of the same conversation | Getting an answer from the AI service | To the AI service you selected |
| Photos (taken by your glasses, or ones you choose) | Describing or analysing the picture | To the AI service you selected, only when you ask for an analysis |
| Voice and audio recordings | Turning speech into text, and summarising recordings | To the speech or AI service you selected |
| The text of a question, for "automatic model selection" (off by default) | Deciding how strong a model your question needs | Only if you turn it on: to the Gemini or OpenAI service you picked as the decision model, using your own key. It contains that question only, not earlier messages, photos or audio |
| Transcripts and AI answers | Showing them to you | Stored on your phone |
| Your API keys | Authenticating with the AI service you chose | Stored on your phone in Android Keystore-protected storage. Sent only to the service they belong to, never to the developer |
| Text to read aloud | Speech output | Handled by your phone's text-to-speech engine. Depending on the engine and voice you use, the engine's provider may process the text over the network |
| App logs | Troubleshooting | Stay on your phone. They leave it only if you choose to share them |
| A report about an AI response | Improving content filtering, fixing problems | To the developer, only when you send it (see "Reports") |

The app does not use your location, contacts, calendar or SMS, and the Google Play version does not request those permissions.

### The AI services you can choose

The Google Play version works with Google Gemini, OpenAI, Anthropic, Groq (speech recognition), and a custom OpenAI-compatible endpoint that you enter yourself (HTTPS only, or a server running on the phone itself). These are independent services. They process your data under **their** terms and privacy policies, not this one:

- Google Gemini: <https://ai.google.dev/gemini-api/terms>
- OpenAI: <https://openai.com/policies/privacy-policy>
- Anthropic: <https://www.anthropic.com/legal/privacy>
- Groq: <https://groq.com/privacy-policy>

Please read the terms of the service you use before sending anything sensitive. For example, Google's terms for the **free tier** of the Gemini API allow Google to use submitted content to improve its products, and human reviewers may read it (in the European Economic Area, Switzerland and the United Kingdom, the paid-service terms apply to free usage as well). Do not submit sensitive, confidential or personal information to a free-tier service.

If you enter a custom endpoint, the operator of that server receives your data. Only enter servers you trust.

**Automatic model selection** is off by default. If you turn it on, every text question is first sent to the Gemini or OpenAI model you chose as the "decision model", which only labels it as easy, medium or hard. The answer then comes from the model you assigned to that level. This is an extra request to a service you already use, so it can count against your quota or be billed by that service. It contains the text of that one question and nothing else, and the service handles it under its own terms like any other request. If the decision service fails or is unsure, the app simply uses your main model. The Google Play version does not offer the other decision services that exist in the GitHub build.

### Reports (the only data the developer receives)

Every AI response in the app has a flag button. If you use it and tap **Send report**, the app sends the developer:

- the AI response you reported;
- the reason you selected and the optional note you typed;
- the message that response answered, **only if you tick the box** to include it;
- the AI provider and model name, the app version, your language and your Android version.

A report never includes API keys, device identifiers, your name or account, or any other conversation. Before you send, the dialog shows what will be sent.

Reports are used only to improve content filtering and fix problems. They are kept for up to **90 days** and are not sold or shared with anyone, except where the law requires. Because reports carry no identifier, to have one deleted please email the contact address above and include the text of the response you reported.

### Data on your phone

Conversations, photos, recordings, settings and logs are stored in the app's private storage on your phone. Each answer also keeps a short note of which model produced it and why. You can delete conversations, recordings and photos inside the app, and uninstalling the app removes everything. The Google Play version turns off Android cloud backup and device-to-device transfer, so this data is not copied to Google's servers by Android backup.

### Permissions

- **Bluetooth** – to connect to your glasses (asked for only when you start the glasses connection).
- **Microphone** – to record audio or use voice input (asked for only when you start a recording). Recording from the phone microphone only works while the app is open.
- **Notifications** – to show the status of the glasses connection.
- **Internet** – to reach the AI service you chose.

### Other people

Glasses can record audio and take photos of people around you. Ask for permission before you record or photograph other people, and follow the laws where you are.

### Children

The app is not directed to children, and it is intended for people aged 18 and over.

### Changes

If this policy changes, the new version will be published here with a new date.

### Contact

Questions or deletion requests: 8jn6u1vhy@mozmail.com

---

## 繁體中文

**最後更新：** 2026 年 10 月 8 日

**App：** Glasses AI Companion（Rokid 眼鏡的非官方 AI 伴侶 App，與 Rokid 無隸屬關係）
**開發者：** zero2005x
**聯絡方式：** 8jn6u1vhy@mozmail.com

本政策適用於 **Google Play 版本**。另一個從 GitHub 側載的版本提供額外選項（更多 AI 服務、選用的線上文字轉語音引擎，以及選用的 Rokid SDK 連線），遵循相同原則，其細節請見專案 README。

### 重點摘要

- 您自備 AI 服務與 API 金鑰。您的語音、照片與文字**只會**傳送到您選擇的 AI 服務。
- 開發者**不會**收到您的對話、照片、錄音或 API 金鑰，也沒有帳號系統、廣告或分析工具。
- 唯一的例外：當**您自己**對某則 AI 回應按下「檢舉」時，該檢舉會傳送給開發者（詳見下方）。
- 對話、照片與錄音都保存在您的手機上。Google Play 版本已排除在 Android 雲端備份之外。

### App 處理哪些資料、資料送往何處

| 資料 | 用途 | 送往何處 |
| --- | --- | --- |
| 您輸入的文字，以及同一段對話中先前的訊息 | 向 AI 服務取得回答 | 送到您選擇的 AI 服務 |
| 照片（眼鏡拍攝或您自行選取） | 描述或分析圖片 | 僅在您要求分析時，送到您選擇的 AI 服務 |
| 語音與錄音 | 將語音轉成文字、整理錄音摘要 | 送到您選擇的語音或 AI 服務 |
| 問題文字，供「自動選模」使用（預設關閉） | 判斷您的問題需要多強的模型 | 僅在您開啟時：使用您自己的金鑰，送到您選為決策模型的 Gemini 或 OpenAI 服務。內容只有該則問題，不含先前的訊息、照片或音訊 |
| 逐字稿與 AI 回答 | 顯示給您看 | 儲存在您的手機上 |
| 您的 API 金鑰 | 向您選擇的 AI 服務驗證身分 | 儲存在手機上，受 Android Keystore 保護。只會送給該金鑰所屬的服務，絕不會送給開發者 |
| 要朗讀的文字 | 語音輸出 | 由手機的文字轉語音引擎處理。視您使用的引擎與語音而定，該引擎的提供者可能透過網路處理這些文字 |
| App 日誌 | 疑難排解 | 留在您的手機上。只有在您選擇分享時才會離開手機 |
| 對某則 AI 回應的檢舉 | 改善內容過濾、修正問題 | 僅在您送出時傳送給開發者（見「檢舉」） |

本 App 不使用您的位置、聯絡人、行事曆或簡訊，Google Play 版本也不會要求這些權限。

### 您可以選擇的 AI 服務

Google Play 版本可搭配 Google Gemini、OpenAI、Anthropic、Groq（語音辨識），以及您自行輸入的自訂 OpenAI 相容端點（僅限 HTTPS，或執行在手機本身的伺服器）。這些都是獨立的服務，會依**它們自己**的條款與隱私權政策處理您的資料，而不是依本政策：

- Google Gemini：<https://ai.google.dev/gemini-api/terms>
- OpenAI：<https://openai.com/policies/privacy-policy>
- Anthropic：<https://www.anthropic.com/legal/privacy>
- Groq：<https://groq.com/privacy-policy>

在傳送敏感內容之前，請先閱讀您所用服務的條款。例如，Google 針對 Gemini API **免費層**的條款允許 Google 將提交的內容用於改善其產品，且可能由人工審閱（在歐洲經濟區、瑞士與英國，免費使用同樣適用付費服務的條款）。請勿向免費層服務提交敏感、機密或個人資訊。

如果您輸入自訂端點，該伺服器的營運者會收到您的資料。請只輸入您信任的伺服器。

**自動選模**預設關閉。若您開啟，每則文字問題會先送到您選為「決策模型」的 Gemini 或 OpenAI 模型，由它只把問題標成簡單、中等或困難；接著由您指派給該等級的模型作答。這是對您已在使用的服務多送一次請求，可能計入該服務的額度或被計費。內容只有那一則問題的文字，不含其他東西，該服務會和其他請求一樣依自己的條款處理。決策服務失敗或無法判斷時，App 會直接使用您的主要模型。Google Play 版本不提供 GitHub 版本中的其他決策服務。

### 檢舉（開發者唯一會收到的資料）

App 中的每則 AI 回應都有一個旗標按鈕。如果您使用它並按下**送出檢舉**，App 會把以下內容傳送給開發者：

- 您檢舉的那則 AI 回應；
- 您選擇的原因，以及您選填的補充說明；
- 該回應所回答的訊息，**僅在您勾選**「一併傳送」時才會包含；
- AI 服務商與模型名稱、App 版本、您的語言與 Android 版本。

檢舉內容絕不包含 API 金鑰、裝置識別碼、您的姓名或帳號，或其他任何對話。送出前，對話框會顯示將傳送的內容。

檢舉僅用於改善內容過濾與修正問題，最多保存 **90 天**，不會出售，也不會與任何人分享，法律另有要求者除外。由於檢舉內容不含任何識別資訊，若要刪除某筆檢舉，請寄信到上方聯絡信箱，並附上您檢舉的回應文字。

### 保存在手機上的資料

對話、照片、錄音、設定與日誌都存放在 App 於您手機上的私有儲存空間。每則回答也會保存一段簡短紀錄，說明是哪個模型產生、為什麼。您可以在 App 內刪除對話、錄音與照片，解除安裝 App 則會移除所有資料。Google Play 版本已關閉 Android 雲端備份與裝置間轉移，因此這些資料不會經由 Android 備份複製到 Google 的伺服器。

### 權限

- **藍牙**：用來連接您的眼鏡（只在您啟動眼鏡連線時才會詢問）。
- **麥克風**：用來錄音或語音輸入（只在您開始錄音時才會詢問）。使用手機麥克風錄音僅能在 App 開啟時進行。
- **通知**：用來顯示眼鏡連線狀態。
- **網際網路**：用來連到您選擇的 AI 服務。

### 其他人

眼鏡可能錄下周圍人的聲音或拍下他們的照片。錄音或拍攝他人之前，請先徵得對方同意，並遵守您所在地的法律。

### 兒童

本 App 不是為兒童設計，適用對象為 18 歲以上。

### 政策變更

若本政策有變更，新版本會連同新的日期發布在此頁面。

### 聯絡我們

問題或刪除請求：8jn6u1vhy@mozmail.com
