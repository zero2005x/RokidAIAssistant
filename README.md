# Rokid AI 助手

一款運行在 Android 手機上，與 Rokid 智能眼鏡配合使用的 AI 語音助手應用。

## 功能特點

- 🔗 **藍牙連接**：自動掃描並連接 Rokid 眼鏡
- 🎤 **語音交互**：透過眼鏡麥克風接收語音
- 🤖 **AI 對話**：整合 Google Gemini API 提供智能對話
- 📺 **AR 顯示**：將對話內容顯示在眼鏡螢幕上

## 技術架構

```
┌─────────────────────────────────────────┐
│            手機端 (本應用)               │
├─────────────────────────────────────────┤
│  Rokid CXR-M SDK                        │
│  ├── 藍牙連接管理                        │
│  ├── AI 事件監聽                         │
│  └── 音頻流接收                          │
├─────────────────────────────────────────┤
│  AI 服務層                               │
│  ├── Speech-to-Text (Whisper API) ✅    │
│  ├── Gemini API (已整合) ✅             │
│  └── Text-to-Speech (Edge TTS) ✅       │
└─────────────────────────────────────────┘
                   │
            Bluetooth
                   │
┌─────────────────────────────────────────┐
│          Rokid 智能眼鏡                  │
│  ├── 觸控板/語音喚醒                     │
│  ├── 麥克風錄音                          │
│  └── AR 字幕顯示                         │
└─────────────────────────────────────────┘
```

## 專案結構

```
app/src/main/
├── java/com/example/rokidaiassistant/
│   ├── MainActivity.kt              # 入口，權限檢查
│   ├── activities/
│   │   ├── bluetooth/               # 藍牙連接模組
│   │   │   ├── BluetoothInitActivity.kt
│   │   │   └── BluetoothInitViewModel.kt
│   │   └── aiassistant/             # AI 助手模組
│   │       ├── AIAssistantActivity.kt
│   │       └── AIAssistantViewModel.kt
│   ├── services/
│   │   ├── GeminiService.kt         # Gemini API 服務
│   │   ├── SpeechToTextService.kt   # 語音識別 (Whisper)
│   │   ├── TextToSpeechService.kt   # 語音合成 (Edge TTS)
│   │   ├── EdgeTtsClient.kt         # Edge TTS WebSocket 客戶端
│   │   └── AudioBufferManager.kt    # 音頻緩衝管理
│   ├── data/
│   │   └── Constants.kt             # 常數配置
│   └── ui/theme/
│       └── Theme.kt                 # Compose 主題
├── res/
│   ├── raw/
│   │   └── sn_auth_file.lc          # SN 鑑權文件
│   ├── values/
│   │   ├── strings.xml
│   │   └── themes.xml
│   └── xml/
│       └── network_security_config.xml
└── AndroidManifest.xml
```

## 快速開始

### 前置需求

- Android Studio Hedgehog (2023.1.1) 或更新版本
- Android SDK 34
- Kotlin 1.9.22
- Rokid 眼鏡設備 + SN 鑑權文件
- Gemini API Key

### 設定步驟

1. **克隆專案**

   ```bash
   cd RokidAIAssistant
   ```

2. **配置敏感資訊**

   編輯 `local.properties`：

   ```properties
   sdk.dir=<你的 Android SDK 路徑>
   ROKID_CLIENT_SECRET=<你的 Client Secret，去除連字號>
   GEMINI_API_KEY=<你的 Gemini API Key>
   OPENAI_API_KEY=<你的 OpenAI API Key，用於 Whisper STT>
   ```

3. **放置 SN 鑑權文件**

   將 `.lc` 鑑權文件複製到：

   ```
   app/src/main/res/raw/sn_auth_file.lc
   ```

4. **建置並運行**
   ```bash
   ./gradlew assembleDebug
   # 或在 Android Studio 中點擊 Run
   ```

### 使用方式

1. 開啟應用，點擊「掃描並連接眼鏡」
2. 選擇您的 Rokid 眼鏡進行配對
3. 連接成功後進入 AI 助手頁面
4. **在眼鏡上**：長按觸控板 或 說「樂奇」
5. 開始與 AI 對話！

## 功能狀態

### ✅ 已完成

- [x] Speech-to-Text 整合 (OpenAI Whisper API)
- [x] Text-to-Speech 整合 (Edge TTS + 系統 TTS 備選)
- [x] Gemini AI 對話
- [x] 藍牙連接與 CXR SDK 整合

### ⏳ 待完成

- [ ] 設定頁面 (API Key 管理、語音設定等)
- [ ] 對話歷史持久化
- [ ] 離線模式支援
- [ ] 錯誤處理優化

## 依賴版本

| 依賴              | 版本       |
| ----------------- | ---------- |
| Rokid CXR SDK     | 1.0.4      |
| Kotlin            | 1.9.22     |
| Compose BOM       | 2024.02.00 |
| Generative AI SDK | 0.2.2      |
| Retrofit          | 2.9.0      |
| OkHttp            | 4.12.0     |

## 注意事項

⚠️ **安全提醒**：

- `local.properties` 包含敏感資訊，**請勿提交到 Git**
- 已加入 `.gitignore` 排除

## 授權

私有專案，僅供內部使用。

---

_如有問題，請參考 [IMPLEMENTATION_GUIDE.md](../IMPLEMENTATION_GUIDE.md)_
