# 系統架構

> 📖 [English Version](../ARCHITECTURE.md)

**Rokid AI Assistant 系統設計與模組通訊指南。**

---

## 🚀 快速開始

```bash
# 建置並安裝全部模組
./gradlew installDebug

# 執行元件特定建置
./gradlew :phone-app:installGithubDebug
./gradlew :glasses-app:installDebug
```

---

## 範圍

### 涵蓋範圍

- 模組間通訊協定
- 資料流與狀態管理
- Rokid CXR SDK 整合模式
- 建置設定與相依性圖

### 不涵蓋範圍

- Rokid 眼鏡硬體規格
- 第三方 AI 服務商內部實作
- 網路基礎架構設定

---

## 目錄

- [系統概覽](#系統概覽)
- [模組指南](#模組指南)
- [通訊架構](#通訊架構)
- [資料流](#資料流)
- [測試](#測試)
- [FAQ 與疑難排解](#faq-與疑難排解)

---

## 系統概覽

```
┌─────────────────────────────────────────────────────────────────┐
│                        使用者互動                                │
│                    語音 / 視覺 / 手勢                            │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
┌───────────────────┐                   ┌───────────────────┐
│   Rokid 眼鏡      │◄─── 藍牙 ────────►│   手機應用        │
│   (glasses-app)   │     CXR-M          │   (phone-app)     │
├───────────────────┤                   ├───────────────────┤
│ • 相機輸入        │                   │ • AI 處理         │
│ • 麥克風輸入      │                   │ • 雲端 API 呼叫   │
│ • AR 顯示輸出     │                   │ • 本地儲存        │
│ • 喚醒詞偵測      │                   │ • 手勢辨識        │
└───────────────────┘                   └─────────┬─────────┘
                                                  │
                                    ┌─────────────┴─────────────┐
                                    ▼                           ▼
                            ┌───────────────┐           ┌───────────────┐
                            │   AI 服務     │           │   STT 服務    │
                            │   Gemini      │           │   Whisper     │
                            │   OpenAI      │           │   Google      │
                            │   Anthropic   │           │   Azure       │
                            └───────────────┘           └───────────────┘
```

---

## AI Provider 架構（2026-08-02 更新）

AI 服務採資料驅動架構（`phone-app/src/.../ai/catalog/`）：

- **ProviderRegistry / ProviderDescriptor**：每家供應商一筆描述（協定
  `ApiProtocol`、模型目錄格式、models endpoint、驗證方式、區域端點）。
  新增供應商不再需要修改大型 `when` 區塊。
- **四層模型目錄（ModelCatalogRepository）**：官方 Models API（Live）→
  本機快取（24 小時 TTL）→ 經官方文件驗證的靜態 fallback → 使用者手動輸入
  模型 ID。fallback 清單記錄 `LAST_VERIFIED_DATE` 與官方文件來源；preview
  或 deprecated 模型不會成為唯一預設模型。
- **ModelCapabilities（模型層級能力）**：text/image/audio input、streaming、
  tool calling、reasoning、realtime、transcription、context 大小。
  圖片能力由「模型」決定而非「供應商」：不支援圖片的模型不會收到圖片請求。
- **ProviderRequestPolicy**：能力驅動的請求參數。OpenAI 專用參數
  （`reasoning_effort`、`verbosity`、`max_completion_tokens`）只會送給 OpenAI；
  DeepSeek reasoning 模型移除 sampling 參數；Perplexity 不送 penalty 參數。
- **AiStreamEvent 統一串流**：`TextDelta` / `ToolCallDelta` / `Citation` /
  `Usage` / `Thinking` / `Completed` / `Error`。OpenAI Chat Completions、
  OpenAI Responses、Anthropic Messages、Gemini generateContent 皆有 SSE
  串流實作。DeepSeek `reasoning_content` 與 Claude thinking 內容不會顯示給
  使用者（僅顯示「思考中」狀態）。
- **ProviderApiException 錯誤分類**：Invalid API key、Permission denied、
  Model unavailable/deprecated、Rate limit、Quota exceeded、Region mismatch、
  Invalid request、Unsupported image、Context too long、Network/Timeout/
  Service unavailable，保留 HTTP status 與供應商錯誤碼，並移除金鑰等敏感內容。
- **STT 與聊天解耦**：只有具備實際 transcription endpoint 的供應商
  （OpenAI Whisper、Groq Whisper、Gemini 原生音訊）可執行語音轉文字。
- **Baidu**：預設使用 Qianfan v2（Bearer API Key）；舊 API Key + Secret Key
  OAuth 保留為 legacy 模式並自動 migration，舊憑證不會被刪除。

---

## 模組指南

### 專案結構

```
RokidAIAssistant/
├── phone-app/            # 手機主應用
│   └── src/main/
│       ├── java/.../
│       │   ├── MainActivity.kt
│       │   ├── data/           # Repository, API clients
│       │   ├── domain/         # Use cases, entities
│       │   └── ui/             # Compose screens
│       └── res/
├── glasses-app/          # 眼鏡 AR 顯示應用
│   └── src/main/
│       └── java/.../
│           ├── MainActivity.kt
│           ├── service/        # CXR 服務橋接
│           └── ui/             # AR overlay UI
├── common/               # 共用程式碼
│   └── src/main/
│       └── java/.../
│           ├── protocol/       # CXR 訊息協定
│           └── constants/      # 共用常數
└── doc/                  # 文件
```

### 模組相依性

| 模組          | 相依於               | 用途            |
| ------------- | -------------------- | --------------- |
| `phone-app`   | `common`             | 主 AI 處理應用  |
| `glasses-app` | `common`             | AR 顯示與輸入   |
| `common`      | -                    | 共用協定與工具  |

---

## 通訊架構

### CXR 協定

手機與眼鏡之間的通訊使用 Rokid CXR SDK：

```kotlin
// 定義於 common/src/.../protocol/CxrMessage.kt
sealed class CxrMessage {
    data class TextDisplay(val text: String) : CxrMessage()
    data class VoiceCommand(val audio: ByteArray) : CxrMessage()
    data class PhotoCapture(val imageData: ByteArray) : CxrMessage()
    data class StatusUpdate(val status: ConnectionStatus) : CxrMessage()
}
```

### 訊息流程

```
眼鏡                            手機
  │                               │
  │──── VoiceCommand ────────────►│
  │                               │ (STT 處理)
  │                               │ (AI 推理)
  │◄──── TextDisplay ─────────────│
  │                               │
  │──── PhotoCapture ────────────►│
  │                               │ (視覺分析)
  │◄──── TextDisplay ─────────────│
```

---

## 資料流

### AI 對話流程

```
1. 使用者輸入（語音/文字/圖片）
      │
      ▼
2. 輸入處理
   ├── 語音 → STT 服務 → 文字
   └── 圖片 → Base64 編碼
      │
      ▼
3. AI 服務呼叫
   ├── 建構訊息（含歷史）
   ├── 串流回應
   └── 錯誤處理/重試
      │
      ▼
4. 回應處理
   ├── 解析 Markdown
   ├── TTS（可選）
   └── 儲存到歷史
      │
      ▼
5. 輸出到使用者
   ├── 手機 → Compose UI
   └── 眼鏡 → CXR TextDisplay
```

### 錄音與自動分析流程（新功能）

```
1. 使用者開始錄音
   ├── 手機錄音按鈕
   └── 眼鏡錄音按鈕
      │
      ▼
2. 停止錄音
   ├── PCM 轉換 WAV 格式
   └── 儲存到 Room 資料庫
      │
      ▼
3. 檢查自動分析設定
   │  (autoAnalyzeRecordings)
   │
   ├── 已啟用 → 繼續自動處理
   └── 已停用 → 結束（僅儲存）
      │
      ▼
4. STT 語音辨識
   │  (使用已設定的 STT 服務商)
   │
      ▼
5. AI 分析
   │  (將辨識結果傳送給 AI)
   │
      ▼
6. 輸出結果
   ├── 更新資料庫
   ├── 更新 UI 對話記錄
   └── 眼鏡顯示（如已連接）
```

**關鍵實作點：**

- `ApiSettings.autoAnalyzeRecordings` 控制自動分析（預設：啟用）
- `ServiceBridge.transcribeRecordingFlow` 處理錄音事件
- `PhoneAIService.processPhoneRecording()` 編排完整工作流程

### 狀態管理

```kotlin
// phone-app/src/.../ui/chat/ChatViewModel.kt
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val connectionStatus: ConnectionStatus = Disconnected
)
```

---

## 測試

### 手動測試清單

| 測試案例 | 步驟            | 預期結果             |
| -------- | --------------- | -------------------- |
| CXR 連接 | 眼鏡 ↔ 手機配對 | 連接狀態 = Connected |
| 語音輸入 | 說出指令        | 文字顯示於兩端       |
| AI 回應  | 傳送訊息        | 串流回應在 < 3 秒    |
| 拍照     | 眼鏡觸發拍照    | 圖片傳到手機並分析   |
| 斷線重連 | 關閉藍牙再開啟  | 自動重新連接         |

### 執行單元測試

```bash
# 全部測試
./gradlew test

# 特定模組
./gradlew :phone-app:testGithubDebugUnitTest
./gradlew :common:test
```

---

## FAQ 與疑難排解

### 架構問題

**Q: 為什麼分成 phone-app 和 glasses-app？**

```
A: 硬體分離 - 眼鏡處理能力有限，主要處理在手機執行。
   CXR SDK 也需要分離的客戶端（M）和服務端（S）模組。
```

**Q: common 模組包含什麼？**

```
A: 共用的協定定義、常數、工具函式。
   確保兩端使用相同的訊息格式。
```

**Q: 如何新增 AI 服務商？**

```
A: 1. 實作 AiProvider 介面
   2. 在 ProviderFactory 註冊
   3. 更新 SettingsScreen UI
   4. 在 local.properties 新增金鑰
```

### 建置問題

**Q: 建置失敗顯示相依性衝突**

```bash
# 檢查相依性樹
./gradlew :phone-app:dependencies --configuration githubReleaseRuntimeClasspath

# 強制解析
configurations.all {
    resolutionStrategy {
        force("com.example:library:1.0.0")
    }
}
```

**Q: Gradle sync 失敗**

```
A: 1. File → Invalidate Caches / Restart
   2. 刪除 .gradle 和 build 資料夾
   3. 重新 sync
```

### 通訊問題

**Q: CXR 連接不穩定**

```
A: 1. 確認藍牙權限已授予
   2. 檢查 ROKID_CLIENT_SECRET 正確
   3. 重啟兩端應用
   4. 查看 Logcat 中的 CXR 標籤
```

**Q: 訊息延遲高**

```
A: 1. 檢查網路連接（AI API 需要）
   2. 嘗試延遲較低的服務商（如 Groq）
   3. 減少訊息歷史長度
```

---

## 建置與執行

### Debug vs Release

| 設定         | Debug            | Release          |
| ------------ | ---------------- | ---------------- |
| Minification | ❌               | ✅ R8            |
| 除錯符號     | ✅               | ❌               |
| API 金鑰來源 | local.properties | local.properties |
| 建置時間     | 快               | 慢               |
| APK 大小     | 較大             | 較小             |

### 常見開發任務

| 任務                   | 指令                                  |
| ---------------------- | ------------------------------------- |
| 建置並安裝 phone-app   | `./gradlew :phone-app:installGithubDebug`   |
| 建置並安裝 glasses-app | `./gradlew :glasses-app:installDebug` |
| 執行測試               | `./gradlew test`                      |
| 清理建置               | `./gradlew clean`                     |
| 檢查相依性更新         | `./gradlew dependencyUpdates`         |
| 產生 Release APK       | `./gradlew assembleRelease`           |
