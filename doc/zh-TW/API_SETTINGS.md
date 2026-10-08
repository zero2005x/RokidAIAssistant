# API 設定指南

> 📖 [English Version](../API_SETTINGS.md)

**Rokid AI Assistant 的 AI 和 STT 服務商完整設定指南。**

---

## 🚀 快速開始

```bash
# 1. 複製範本
cp local.properties.template local.properties

# 2.（選用）新增任一服務商金鑰 — 沒有任何金鑰也能安裝並開啟 App。
#    金鑰也可以之後在 App 內的設定畫面輸入。
echo "GEMINI_API_KEY=your_key_here" >> local.properties

# 3. 重新建置
./gradlew assembleDebug
```

> 安裝 App 或開啟設定頁不需要任何 AI 金鑰。只有你實際選用的那一家服務商
> 需要憑證（可在 App 內輸入，或寫在 `local.properties`）。

---

## 範圍

### 涵蓋範圍

- 所有支援的 AI 聊天服務商設定
- 所有支援的 STT（語音轉文字）服務商設定
- Rokid CXR SDK 認證設定
- `local.properties` 檔案管理

### 不涵蓋範圍

- AI 模型微調或訓練
- 自訂模型部署
- 服務商的帳單或配額管理

---

## 目錄

- [快速參考](#快速參考)
- [AI 聊天服務商](#ai-聊天服務商)
- [語音轉文字服務商](#語音轉文字服務商)
- [Rokid SDK 設定](#rokid-sdk-設定)
- [設定檔](#設定檔)
- [應用內設定](#應用內設定)
- [FAQ 與疑難排解](#faq-與疑難排解)

---

## 快速參考

### 必要金鑰

| 金鑰                  | 必要性        | 取得位置                                            |
| --------------------- | ------------- | --------------------------------------------------- |
| `ROKID_CLIENT_SECRET` | ⚠️ 僅眼鏡需要 | Rokid 開發者入口                                    |
| `GEMINI_API_KEY`      | 選用          | [Google AI Studio](https://ai.google.dev/)          |
| `OPENAI_API_KEY`      | 選用          | [OpenAI Platform](https://platform.openai.com/)     |
| `ANTHROPIC_API_KEY`   | 選用          | [Anthropic Console](https://console.anthropic.com/) |

> 只有你選用的服務商需要憑證。`local.properties` 中的建置期金鑰為選用；
> App 內輸入的金鑰儲存於 Android Keystore 加密偏好設定（不會以明文儲存、
> 不會寫入 Log）。

### 服務商能力對照表

最後驗證日期：**2026-08-02**。App 會從各家官方 Models API 動態載入模型清單；
「代表性 fallback 模型」僅在 API 無法連線且無快取時使用。

| 服務商      | 協定                     | 動態模型 | 視覺（依模型） | 串流   | STT endpoint | 即時語音 | 驗證方式            |
| ----------- | ------------------------ | -------- | -------------- | ------ | ------------ | -------- | ------------------- |
| Gemini      | generateContent（原生）  | ✅       | ✅             | ✅ SSE | ✅ 原生音訊  | ❌       | API key             |
| OpenAI      | Responses API + Chat Completions 備援 | ✅ | ✅  | ✅ SSE | ✅ Whisper   | ❌       | Bearer key          |
| Anthropic   | Messages（原生）         | ✅       | ✅             | ✅ SSE | ❌           | ❌       | x-api-key           |
| DeepSeek    | Chat Completions         | ✅       | ❌             | ✅ SSE | ❌           | ❌       | Bearer key          |
| Groq        | Chat Completions         | ✅       | ✅（特定模型） | ✅ SSE | ✅ Whisper   | ❌       | Bearer key          |
| xAI         | Chat Completions         | ✅       | ✅（如 Grok 4.5） | ✅ SSE | ❌        | ❌       | Bearer key          |
| 阿里雲      | Chat Completions         | ✅       | ✅（VL 模型）  | ✅ SSE | ❌           | ❌       | Bearer key + 區域   |
| Z.AI（GLM） | Chat Completions         | ✅       | ✅（GLM-V）    | ✅ SSE | ❌           | ❌       | Bearer key          |
| 百度        | Qianfan v2（+ legacy OAuth） | ✅   | ✅（VL 模型）  | ✅ SSE | ❌           | ❌       | Bearer key / 舊金鑰對 |
| Perplexity  | Sonar Chat Completions   | ❌（fallback 清單） | ✅（Sonar） | ✅ SSE | ❌    | ❌       | Bearer key          |
| Moonshot    | Chat Completions         | ✅       | ✅（Kimi 多模態） | ✅ SSE | ❌        | ❌       | Bearer key          |
| Mistral     | Chat Completions         | ✅（解析能力 metadata） | ✅（特定模型） | ✅ SSE | ❌ | ❌  | Bearer key          |
| Gemini Live | Live WebSocket           | ✅       | ✅             | ✅ WS  | ✅ 原生      | ✅       | API key             |
| AnythingLLM | Workspace API            | ❌（依 workspace） | ⚠️ 未知  | ❌     | ❌           | ❌       | API key + URL       |
| 自訂        | Chat Completions 或 Responses | ✅（視端點） | ⚠️ 手動覆寫 | ✅  | ❌           | ❌       | 選用 key            |

> 視覺是「模型層級」能力：標示 ✅ 的服務商，其純文字模型仍會在送出前被拒絕
> 圖片請求。部分服務商可能提供 trial/free quota — 請以官方 Console 為準，
> 不保證永久存在。

### 代表性 fallback 模型（2026-09-30 驗證）

| 服務商     | Fallback 範例（未標註者為 stable）                                       |
| ---------- | ------------------------------------------------------------------------ |
| Gemini     | gemini-3.8-flash、gemini-3.6-flash、gemini-3.5-flash(-lite)              |
| OpenAI     | gpt-6-astra/sol/luna、gpt-5.6/terra/luna（gpt-5.4、gpt-4o 為 legacy）    |
| Anthropic  | claude-sonnet-5-5、claude-opus-5-5、claude-sonnet-5、claude-opus-5        |
| DeepSeek   | deepseek-flash、deepseek-v4-pro（deepseek-v4-flash 為暫時相容別名）         |
| Groq       | gpt-oss-120b/20b、qwen3.8-27b（Llama 3.3 已停用；Llama 4 Scout/Maverick 為 preview） |
| xAI        | grok-4.7、grok-4.5（視覺）、grok-4.1-fast、grok-4                          |
| 阿里雲     | qwen3.8-max/flash、qwen3.7-max/plus/flash、qwen2.5-vl-72b/32b            |
| Z.AI       | glm-5.1、glm-5v-turbo（視覺）、glm-4.7-flash                             |
| 百度       | ernie-5.1、ernie-5.0、ernie-4.5-turbo-128k、ernie-4.5-turbo-vl（視覺）   |
| Perplexity | sonar、sonar-pro、sonar-reasoning-pro、sonar-deep-research               |
| Moonshot   | kimi-k2.6、kimi-k2.5、kimi-k2.5-thinking                                 |
| Mistral    | mistral-medium-3-5、mistral-large-2512、mistral-small-2603、ministral-3-8b |

> 帳號的 Models API 回傳結果永遠優先於此表。更新方式：查閱各家官方模型文件，
> 修改 `ai/catalog/FallbackModelCatalog.kt`，並更新 `LAST_VERIFIED_DATE`。

---

## AI 聊天服務商

### 支援的服務商

各家模型清單由官方 API 動態載入（Live → 快取 → 驗證過的 fallback → 手動輸入）。
基礎 URL：

| 服務商          | 基礎 URL                                                             |
| --------------- | -------------------------------------------------------------------- |
| **Gemini**      | `https://generativelanguage.googleapis.com/v1beta/`                  |
| **OpenAI**      | `https://api.openai.com/v1/`                                         |
| **Anthropic**   | `https://api.anthropic.com/v1/`                                      |
| **DeepSeek**    | `https://api.deepseek.com/`                                          |
| **Groq**        | `https://api.groq.com/openai/v1/`                                    |
| **xAI**         | `https://api.x.ai/v1/`                                               |
| **阿里雲**      | 區域端點（中國 / 新加坡 / 美國 / 德國 / 日本 / 自訂 workspace URL）  |
| **智譜**        | `https://api.z.ai/api/paas/v4/`                                      |
| **百度**        | `https://qianfan.baidubce.com/v2/`（舊 RPC 保留作 migration）        |
| **Perplexity**  | `https://api.perplexity.ai/`（Sonar）                                |
| **Moonshot**    | `https://api.moonshot.ai/v1/`                                        |
| **Mistral**     | `https://api.mistral.ai/v1/`                                         |
| **Gemini Live** | `wss://generativelanguage.googleapis.com/ws/...`                     |
| **AnythingLLM** | 使用者定義 server URL                                                |
| **自訂**        | 使用者定義（Ollama、LM Studio、vLLM...）                             |

### 模型目錄（四層）

1. **Live** — 使用你的金鑰從服務商 Models API 取得。
2. **Cached** — 上次成功結果，24 小時內重用（網路失敗時延長使用）。
3. **Fallback** — 經驗證的靜態清單（2026-08-02），不含 TTS／圖片生成／轉錄模型。
4. **Manual** — 隨時可手動輸入模型 ID；手動選擇永不會被刪除，即使不在遠端
   清單中（改為顯示警告）。

每家服務商各自記住上次選擇的模型：切換 OpenAI → Gemini → OpenAI 會恢復你
先前選擇的 OpenAI 模型。

### 取得 API 金鑰

#### Google Gemini（推薦）

```
網址: https://ai.google.dev/
步驟:
1. 使用 Google 帳號登入
2. 點擊「Get API key」
3. 在新專案中建立金鑰
4. 複製金鑰到 local.properties
```

#### OpenAI

```
網址: https://platform.openai.com/
步驟:
1. 建立帳號 / 登入
2. Settings → API Keys
3. 建立新的密鑰
4. 複製金鑰（只顯示一次！）
```

#### Anthropic

```
網址: https://console.anthropic.com/
步驟:
1. 建立帳號
2. API Keys 區塊
3. 產生新金鑰
```

---

## 語音轉文字服務商

### 內建 AI 服務商 STT

STT 與聊天服務商解耦。只有具備實際轉錄 endpoint 的服務商可執行語音轉文字：

| 服務商 | 方式                          | 備註                     |
| ------ | ----------------------------- | ------------------------ |
| Gemini | 原生多模態音訊                | 使用所選 Gemini 聊天模型 |
| OpenAI | Whisper `/audio/transcriptions` | 獨立轉錄模型           |
| Groq   | Whisper（加速版）             | `whisper-large-v3-turbo` |

> 「OpenAI 相容」不代表有可用的轉錄 endpoint — xAI、DeepSeek、阿里雲、智譜、
> Moonshot、Mistral、Perplexity、百度等聊天服務商不會收到 STT 請求。

### 專用 STT 服務商

| 服務商               | 認證類型                 | 串流 | 即時 |
| -------------------- | ------------------------ | ---- | ---- |
| **Google Cloud STT** | 服務帳戶 / API 金鑰      | ✅   | ✅   |
| **Azure Speech**     | 訂閱金鑰 + 區域          | ✅   | ✅   |
| **AWS Transcribe**   | IAM 憑證                 | ✅   | ✅   |
| **Deepgram**         | API 金鑰                 | ✅   | ✅   |
| **訊飛**             | App ID + API 金鑰 + 密鑰 | ❌   | ✅   |

---

## Rokid SDK 設定

### CXR-M SDK（手機端）

**位置**: `phone-app/build.gradle.kts`

```kotlin
implementation("com.rokid.cxr:client-m:1.2.2")
```

**用途**:

- 藍牙連接眼鏡
- AI 事件監聽（長按偵測）
- 拍照控制

**設定**:

```properties
# 在 local.properties 中（移除密鑰中的連字號）
# 原始格式: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
# 輸入為: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
ROKID_CLIENT_SECRET=your_rokid_client_secret_here
```

### CXR-S SDK（眼鏡端）

**位置**: `glasses-app/build.gradle.kts`

```kotlin
implementation("com.rokid.cxr:cxr-service-bridge")
```

**用途**:

- 接收手機訊息
- 傳送資料回手機

---

## 設定檔

### local.properties

**位置**: 專案根目錄 (`RokidAIAssistant/local.properties`)

> ⚠️ 此檔案在 `.gitignore` 中 — 請勿提交到版本控制！

```properties
# =============================================
# Rokid AI Assistant - API 設定
# =============================================

# [必要] Gemini API 金鑰
# 取得位置: https://ai.google.dev/
GEMINI_API_KEY=your_gemini_api_key_here

# [眼鏡必要] Rokid Client Secret
# 輸入前請移除連字號
ROKID_CLIENT_SECRET=your_rokid_client_secret_here

# [選用] OpenAI API 金鑰（用於 GPT/Whisper）
OPENAI_API_KEY=your_openai_api_key_here

# [選用] Anthropic API 金鑰（用於 Claude）
ANTHROPIC_API_KEY=your_anthropic_api_key_here
```

---

## 應用內設定

其他 API 金鑰可在應用的**設定**畫面中配置：

| 設定         | 應用內位置              | 用途                                                      |
| ------------ | ----------------------- | --------------------------------------------------------- |
| AI 服務商    | 設定 → AI Provider      | 選擇服務商（顯示協定與是否已設定憑證）                    |
| 模型         | 設定 → Model            | 搜尋／重新整理目錄、來源標籤（Live/Cached/Fallback）、能力標籤、手動模型 ID |
| API 金鑰     | 設定 → API Keys         | 僅要求目前所選服務商的憑證                                |
| 百度驗證     | 設定 → API Keys（百度） | Qianfan v2 金鑰，或舊 API Key + Secret Key 切換開關       |
| 阿里雲區域   | 設定 → API Keys（阿里雲）| 中國 / 新加坡 / 美國 / 德國 / 日本 / 自訂 workspace URL   |
| 自訂端點     | 設定 → Custom           | Base URL、模型 ID、選用金鑰、models 路徑、Chat Completions/Responses 協定（HTTP 明文警告） |
| STT 服務商   | 設定 → Speech           | 設定 STT                                                  |
| 系統提示詞   | 設定 → System Prompt    | 自訂 AI 行為                                              |
| 自動分析錄音 | 設定 → 錄音設定         | 錄音結束後自動傳送 AI 進行辨識                            |

### 百度 migration

新設定使用單一 **Qianfan v2 API 金鑰**（Bearer）。只持有舊 API Key +
Secret Key 的既有安裝會自動以 legacy 模式繼續運作 — 舊憑證不會被刪除，
也可以用「Legacy authentication」開關明確切換。

### DeepSeek migration

已儲存的舊 `deepseek-chat` / `deepseek-reasoner` 模型 ID 會自動遷移到
`deepseek-flash` / `deepseek-v4-pro`；已儲存的 `deepseek-v4-flash` 也會遷移。
仍可手動輸入舊 ID（會顯示
deprecated 警告）。

**檔案路徑**: `phone-app/src/.../ui/settings/SettingsScreen.kt`

---

## FAQ 與疑難排解

### API 金鑰問題

**Q: 建置時出現「API key not found」錯誤**

```
A: 檢查 local.properties 是否存在於專案根目錄（非模組資料夾）。
   執行: cat local.properties  # 驗證檔案內容
```

**Q: 執行時出現「Invalid API key」錯誤**

```
A: 1. 確認金鑰正確（無多餘空格）
   2. 檢查金鑰是否過期或被撤銷
   3. 變更金鑰後重新建置: ./gradlew clean assembleDebug
```

**Q: API 金鑰在測試中有效但在 release 建置中無效**

```
A: 金鑰在建置時嵌入。變更金鑰後重新建置 release:
   ./gradlew clean assembleRelease
```

### 服務商特定問題

**Q: Gemini 回傳「quota exceeded」**

```
A: 配額取決於你的 Google AI 方案（可能有 trial/free quota — 請以官方
   Console 為準，不保證永久存在）。選項:
   1. 等待配額重置
   2. 升級方案
   3. 暫時切換到其他服務商
```

**Q: OpenAI 回傳 401 Unauthorized**

```
A: 1. 檢查 API 金鑰是否有效
   2. 確認帳號已設定帳單
   3. 驗證金鑰有正確權限
```

### Rokid SDK 問題

**Q: 無法連接眼鏡**

```
A: 1. 確認 ROKID_CLIENT_SECRET 正確
   2. 從密鑰中移除連字號
   3. 兩個裝置都啟用藍牙
   4. 重新啟動兩個應用
```

**Q: 拍照失敗**

```
A: 1. 在眼鏡上授予相機權限
   2. 確認 CXR 連接已建立
   3. 檢查 Logcat 中的 CXR 錯誤
```
