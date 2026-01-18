# Rokid AI 助手

**繁體中文** | [简体中文](README_CN.md) | [English](README_EN.md)

一款運行在 Android 手機上，與 Rokid 智能眼鏡配合使用的 AI 語音助手應用程式。

## 功能特點

- 🔗 **藍牙連接**：手機作為 SPP 伺服器，眼鏡作為客戶端連接
- 🎤 **語音互動**：透過眼鏡麥克風錄製語音
- 🤖 **多供應商 AI**：支援 11 個 AI 供應商（Gemini、OpenAI、Claude、Perplexity 等）
- 📺 **AR 顯示**：在眼鏡螢幕上顯示對話內容
- 🌍 **多語言支援**：支援 13 種語言
- 🔐 **安全儲存**：API 金鑰使用 EncryptedSharedPreferences 加密儲存

## 系統架構

```
┌─────────────────┐      Bluetooth SPP      ┌─────────────────┐      WiFi      ┌─────────────────┐
│   Rokid 眼鏡    │ ◄──────────────────────► │     手機 App    │ ◄────────────► │    AI APIs      │
│  (glasses-app)  │     語音/指令/回應       │   (phone-app)   │   HTTP/REST   │    (雲端)       │
└─────────────────┘                          └─────────────────┘                └─────────────────┘
        │                                            │
        │                                            │
   ┌────┴────┐                              ┌───────┴───────┐
   │  錄音   │                              │  AI 處理      │
   │  顯示   │                              │  設定管理     │
   └─────────┘                              └───────────────┘
```

## 專案結構

```
RokidAIAssistant/
├── phone-app/                    # 手機應用程式
│   ├── data/
│   │   ├── ApiSettings.kt        # AI 供應商設定
│   │   ├── AppLanguage.kt        # 語言定義
│   │   └── SettingsRepository.kt # 設定儲存
│   ├── service/
│   │   ├── PhoneAIService.kt     # 主要前台服務
│   │   ├── BluetoothSppManager.kt# 藍牙 SPP 伺服器
│   │   └── ai/                   # AI 服務實作
│   │       ├── GeminiService.kt
│   │       ├── OpenAiService.kt
│   │       ├── AnthropicService.kt
│   │       └── ...
│   └── ui/
│       ├── SettingsScreen.kt     # 設定介面
│       └── ...
│
├── glasses-app/                  # 眼鏡應用程式
│   ├── service/
│   │   ├── BluetoothSppClient.kt # 藍牙 SPP 客戶端
│   │   └── WakeWordService.kt    # 喚醒詞偵測
│   └── viewmodel/
│       └── GlassesViewModel.kt   # UI 狀態管理
│
└── common/                       # 共用模組
    ├── Message.kt                # 藍牙訊息格式
    ├── MessageType.kt            # 訊息類型定義
    └── Constants.kt              # 共用常數
```

## 支援的 AI 供應商

| 供應商            | 對話 | 語音識別     | 視覺 |
| ----------------- | ---- | ------------ | ---- |
| Google Gemini     | ✅   | ✅           | ✅   |
| OpenAI            | ✅   | ✅ (Whisper) | ✅   |
| Anthropic Claude  | ✅   | ❌           | ✅   |
| DeepSeek          | ✅   | ❌           | ✅   |
| Groq              | ✅   | ✅ (Whisper) | ✅   |
| xAI (Grok)        | ✅   | ❌           | ✅   |
| 阿里雲通義千問    | ✅   | ❌           | ✅   |
| 智譜 AI (ChatGLM) | ✅   | ❌           | ✅   |
| 百度文心一言      | ✅   | ❌           | ✅   |
| Perplexity        | ✅   | ❌           | ❌   |
| 自訂 (Ollama 等)  | ✅   | ❌           | ❌   |

## 支援的語言

應用程式介面支援以下 13 種語言：

| 語言     | 代碼  | 原生名稱   |
| -------- | ----- | ---------- |
| 英文     | en    | English    |
| 簡體中文 | zh-CN | 简体中文   |
| 繁體中文 | zh-TW | 繁體中文   |
| 日文     | ja    | 日本語     |
| 韓文     | ko    | 한국어     |
| 越南文   | vi    | Tiếng Việt |
| 泰文     | th    | ไทย        |
| 法文     | fr    | Français   |
| 西班牙文 | es    | Español    |
| 俄文     | ru    | Русский    |
| 烏克蘭文 | uk    | Українська |
| 阿拉伯文 | ar    | العربية    |
| 義大利文 | it    | Italiano   |

## 快速開始

### 前置需求

- Android Studio Hedgehog (2023.1.1) 或更新版本
- Android SDK 34
- Kotlin 2.x
- Rokid 眼鏡設備（用於 glasses-app）
- 至少一個 AI 供應商的 API 金鑰

### 設定步驟

1. **克隆專案**

   ```bash
   git clone <repository-url>
   cd RokidAIAssistant
   ```

2. **配置 API 金鑰**

   複製 `local.properties.template` 為 `local.properties` 並填入您的金鑰：

   ```properties
   sdk.dir=<您的 Android SDK 路徑>
   GEMINI_API_KEY=<您的 Gemini API 金鑰>
   OPENAI_API_KEY=<您的 OpenAI API 金鑰>
   ```

3. **建置並執行**

   ```bash
   # 建置手機應用程式
   ./gradlew :phone-app:assembleDebug

   # 建置眼鏡應用程式
   ./gradlew :glasses-app:assembleDebug
   ```

### 使用方式

1. 在 Android 手機上安裝 `phone-app`
2. 在 Rokid 眼鏡上安裝 `glasses-app`
3. 開啟手機應用程式並點擊「啟動服務」
4. 透過藍牙將眼鏡與手機配對
5. 在眼鏡上，點擊觸控板或說出喚醒詞開始錄音
6. 說出您的問題，放開後即可獲得 AI 回應

## 設定選項

所有設定都可以在手機應用程式的設定頁面中配置：

- **AI 供應商**：從 11 個供應商中選擇
- **AI 模型**：選擇該供應商的模型
- **API 金鑰**：每個供應商的金鑰安全儲存
- **語音識別**：選擇語音識別服務
- **系統提示詞**：自訂 AI 行為
- **應用程式語言**：介面語言選擇

## 藍牙通訊協議

### SPP UUID

```
a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### 訊息格式

JSON 格式，使用換行符分隔，二進位資料使用 Base64 編碼。

### 訊息類型

| 類型             | 方向      | 說明               |
| ---------------- | --------- | ------------------ |
| VOICE_START      | 眼鏡→手機 | 開始錄音           |
| VOICE_END        | 眼鏡→手機 | 錄音結束，包含音訊 |
| AI_PROCESSING    | 手機→眼鏡 | 處理狀態           |
| USER_TRANSCRIPT  | 手機→眼鏡 | 語音轉文字結果     |
| AI_RESPONSE_TEXT | 手機→眼鏡 | AI 文字回應        |
| AI_ERROR         | 手機→眼鏡 | 錯誤訊息           |

## 音訊格式

- **取樣率**：16000 Hz
- **聲道**：單聲道
- **位元深度**：16-bit
- **格式**：PCM → WAV（呼叫 API 前轉換）

## 開發資訊

### 建置需求

| 元件                  | 版本                   |
| --------------------- | ---------------------- |
| Android Gradle Plugin | 9.0.0                  |
| Kotlin                | 2.2.10                 |
| Gradle                | 9.1.0                  |
| Min SDK               | 26（眼鏡）/ 28（手機） |
| Target SDK            | 34                     |

### 主要依賴

| 依賴              | 版本       |
| ----------------- | ---------- |
| Compose BOM       | 2024.02.00 |
| Generative AI SDK | 0.9.0      |
| Retrofit          | 2.11.0     |
| OkHttp            | 4.12.0     |

## 安全注意事項

⚠️ **重要**：

- `local.properties` 包含敏感的 API 金鑰 - **請勿提交到 Git**
- API 金鑰使用 EncryptedSharedPreferences 加密儲存
- 所有金鑰已在 `.gitignore` 中排除版本控制

## 授權

私有專案，僅供內部使用。

## 文件

- [架構指南](ARCHITECTURE.md)
- [API 設定指南](API_SETTINGS_TW.md)
- [疑難排解指南](TROUBLESHOOTING_TW.md)
