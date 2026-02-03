# API 設定指南

> 📖 [English Version](../API_SETTINGS.md)

**Rokid AI Assistant 的 AI 和 STT 服務商完整設定指南。**

---

## 🚀 快速開始

```bash
# 1. 複製範本
cp local.properties.template local.properties

# 2. 新增 Gemini API 金鑰（最低需求）
echo "GEMINI_API_KEY=your_key_here" >> local.properties

# 3. 重新建置
./gradlew assembleDebug
```

> 前往 [Google AI Studio](https://ai.google.dev/) 在 2 分鐘內取得免費的 Gemini API 金鑰。

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

| 金鑰                  | 必要性      | 取得位置                                            |
| --------------------- | ----------- | --------------------------------------------------- |
| `GEMINI_API_KEY`      | ✅ 必要     | [Google AI Studio](https://ai.google.dev/)          |
| `ROKID_CLIENT_SECRET` | ⚠️ 眼鏡需要 | Rokid 開發者入口                                    |
| `OPENAI_API_KEY`      | 選用        | [OpenAI Platform](https://platform.openai.com/)     |
| `ANTHROPIC_API_KEY`   | 選用        | [Anthropic Console](https://console.anthropic.com/) |

### 服務商比較

| 服務商        | 視覺 | 串流 | 免費方案 | 延遲 |
| ------------- | ---- | ---- | -------- | ---- |
| **Gemini**    | ✅   | ✅   | ✅ 優惠  | 中等 |
| **OpenAI**    | ✅   | ✅   | ❌       | 低   |
| **Anthropic** | ✅   | ✅   | ❌       | 中等 |
| **Groq**      | ✅   | ✅   | ✅ 有限  | 極低 |
| **DeepSeek**  | ❌   | ✅   | ✅       | 中等 |

---

## AI 聊天服務商

### 支援的服務商

| 服務商         | 模型                            | 視覺   | 基礎 URL                                             |
| -------------- | ------------------------------- | ------ | ---------------------------------------------------- |
| **Gemini**     | Gemini 2.5 Pro/Flash/Flash-Lite | ✅     | `https://generativelanguage.googleapis.com/v1beta/`  |
| **OpenAI**     | GPT-5, GPT-5.2, o3, o4-mini     | ✅     | `https://api.openai.com/v1/`                         |
| **Anthropic**  | Claude 4, Claude 4 Sonnet       | ✅     | `https://api.anthropic.com/v1/`                      |
| **DeepSeek**   | DeepSeek V3, R2                 | ❌     | `https://api.deepseek.com/`                          |
| **Groq**       | Llama 4 Scout/Maverick          | ✅     | `https://api.groq.com/openai/v1/`                    |
| **xAI**        | Grok 3, Grok 3 Mini             | ✅     | `https://api.x.ai/v1/`                               |
| **阿里雲**     | Qwen-Max, Qwen-Plus             | ✅     | `https://dashscope.aliyuncs.com/compatible-mode/v1/` |
| **智譜**       | GLM-4 Plus                      | ✅     | `https://open.bigmodel.cn/api/paas/v4/`              |
| **百度**       | ERNIE 4.5 Pro                   | ❌     | `https://aip.baidubce.com/rpc/2.0/...`               |
| **Perplexity** | Sonar Pro, Sonar Reasoning Pro  | ❌     | `https://api.perplexity.ai/`                         |
| **自訂**       | 使用者定義                      | 視情況 | 使用者定義（如 Ollama, LM Studio）                   |

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

| 服務商 | 方式              | 串流 |
| ------ | ----------------- | ---- |
| Gemini | 原生多模態        | ❌   |
| OpenAI | Whisper API       | ❌   |
| Groq   | Whisper（加速版） | ❌   |

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
implementation("com.rokid.cxr:client-m:1.0.4")
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

| 設定         | 應用內位置           | 用途                           |
| ------------ | -------------------- | ------------------------------ |
| AI 服務商    | 設定 → AI Provider   | 選擇 Gemini/OpenAI 等          |
| 模型         | 設定 → Model         | 選擇特定模型                   |
| 自訂端點     | 設定 → Custom        | 用於 Ollama/LM Studio          |
| STT 服務商   | 設定 → Speech        | 設定 STT                       |
| 系統提示詞   | 設定 → System Prompt | 自訂 AI 行為                   |
| 自動分析錄音 | 設定 → 錄音設定      | 錄音結束後自動傳送 AI 進行辨識 |

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
A: 免費方案有限制。選項:
   1. 等待配額重置（每日）
   2. 升級到付費方案
   3. 暫時切換到其他服務商
```

**Q: OpenAI 回傳 401 Unauthorized**

```
A: 1. 檢查 API 金鑰是否有效
   2. 確認已設定帳單（無免費方案）
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
