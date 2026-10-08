# AI 供應商 API 呼叫對照與更新紀錄

核對日期：2026-09-30。範圍為 `AiProvider` / `ProviderRegistry` 原有的全部 16 個選項，以及共用的文字、圖片、串流、模型清單和內建語音轉文字呼叫。以下是 App 實際使用的介面，不代表各平台所有產品都已整合。模型能否使用仍取決於帳戶權限及服務端當下的模型清單。

## 2026-09-30 增量更新

本次逐一檢查下表既有供應商是否有與 App 的文字對話、影像理解或語音轉寫直接相關的 API 更新。沒有加入與本 App 無關的影片生成、訓練或代理工具介面。付費 API 未使用使用者憑證實網測試。

| 供應商 | 評估與 App 變更 |
| --- | --- |
| Gemini / Gemini Live | 增列 `gemini-3.8-flash`、`gemini-3.8-live` 與 Extended Thinking；新安裝的文字預設改為 3.8 Flash，已儲存的模型選擇保留。[變更紀錄](https://ai.google.dev/gemini-api/docs/changelog) |
| OpenAI | 增列 GPT-6 Astra/Sol/Luna；按模型限制處理 reasoning effort 與採樣欄位，改用 Responses 優先。內建轉寫模型改為 `gpt-transcribe`，保留既有 multipart 路徑。[模型](https://developers.openai.com/api/docs/models)、[轉寫](https://developers.openai.com/api/docs/guides/speech-to-text) |
| Anthropic | 增列 Claude Sonnet/Opus 5.5；既有 4.7+ 不送自訂採樣參數的規則適用 5.5。[模型](https://platform.claude.com/docs/en/models/overview) |
| DeepSeek | 增列 `deepseek-flash`（V4.1 Flash）與圖片能力；已儲存的 V4 Flash 別名遷移到新 ID，保留 V4 Pro。[變更紀錄](https://api-docs.deepseek.com/updates/) |
| xAI | 增列 Grok 4.7 的文字／圖片模型選項。新的語音轉寫 API 需要獨立 STT adapter，未把聊天端點誤作轉寫端點。[變更紀錄](https://docs.x.ai/developers/release-notes) |
| Alibaba / Qwen | 增列 `qwen3.8-max`、`qwen3.8-flash` 及圖片能力；區域與密鑰設定維持既有契約。[模型](https://www.alibabacloud.com/help/en/model-studio/models) |
| Groq | `llama-3.3-70b-versatile` 已於 2026-08-16 停用，改用 `openai/gpt-oss-120b` 作預設與已儲存設定的替代；`qwen/qwen3.6-27b` 遷移至 `qwen/qwen3.8-27b`。[停用公告](https://console.groq.com/docs/deprecations) |
| Moonshot / Kimi | 增列可沿用現有文字／圖片 Chat Completions 流程的 `kimi-k2.6`。K3 已評估，但官方要求多輪請求原樣帶回包含 `reasoning_content` 的完整 assistant 訊息；目前 App 只儲存可見文字，所以本次不把 K3 列為可用備援模型。[K2.6](https://platform.kimi.ai/docs/guide/kimi-k2-6-quickstart)、[K3 契約](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart) |
| Z.AI、Baidu、Perplexity、Mistral | 保留既有請求介面與模型清單的即時載入方式；本次沒有確認到必須變更現有呼叫契約的新功能。各供應商詳見下表官方文件連結。 |
| AnythingLLM、Local Gemma、Custom | 這些由使用者自架或本機環境決定可用功能，無通用雲端版本可自動遷移；保留現有端點／模型偵測。 |

Jev 與 Laya 是新增的決策後端，用於選擇上述生成模型，不計入既有 16 個生成供應商。使用方式見 [選模與眼鏡顯示設定](DECISION_ROUTING_AND_DISPLAY.md)。

## 供應商對照

除 Gemini、Anthropic、Live、Local Gemma 外，HTTP JSON 請求使用 `Authorization: Bearer <API_KEY>`。Chat Completions 請求以 `model`、`messages` 組成，讀取 `choices[0].message.content`；SSE 讀取 `choices[0].delta.content`。

| 原有選項 | App 預設位址與呼叫方式 | 特殊處理與官方參考 |
| --- | --- | --- |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`；串流為 `:streamGenerateContent?alt=sse`；`GET /v1beta/models` | `x-goog-api-key` 標頭；`contents[].parts`、`systemInstruction`、`generationConfig`；圖片／音訊使用 inline data。HTTP URL 不再攜帶 key。[API](https://ai.google.dev/api) |
| OpenAI | `https://api.openai.com/v1/`；`POST responses` 或 `chat/completions`；`GET models` | Responses 使用 `input`、`max_output_tokens`、`reasoning.effort`、`text.verbosity`；Chat 使用 `messages` 和模型對應 token 欄位。保留現有 factory 的 Responses 選擇與 404 相容路徑。未設定 effort 時交由服務端預設，過濾模型不接受的 effort。[參數規則](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-5.2) |
| Anthropic | `https://api.anthropic.com/v1/messages`；`GET /v1/models` | `x-api-key`、`anthropic-version: 2023-06-01`；頂層 `system`、`messages`、`max_tokens`。圖片使用 base64 source。Claude 4.7 起省略採樣參數；舊模型 temperature 限制於 0–1；合併全部文字區塊。[Messages](https://platform.claude.com/docs/en/build-with-claude/working-with-messages) |
| DeepSeek | `https://api.deepseek.com/chat/completions`；`GET /models` | `max_tokens`；依原有 reasoning capability 省略採樣與 penalty；保留獨立 reasoning 欄位與一般回答的區隔。串流可要求 usage。[Chat API](https://api-docs.deepseek.com/api/create-chat-completion/) |
| Groq | `https://api.groq.com/openai/v1/chat/completions`；`GET /openai/v1/models` | 使用 `max_completion_tokens`，省略目前不支援的 frequency/presence penalties；支援串流 usage。[API](https://console.groq.com/docs/api-reference) |
| xAI | `https://api.x.ai/v1/chat/completions`；`GET /v1/models` | 保留 Grok reasoning 系列的採樣、penalty、stop 限制；不注入 OpenAI 專用 effort/verbosity；圖片依模型能力處理。[文字生成](https://docs.x.ai/developers/model-capabilities/text/generate-text) |
| Alibaba / Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；同 base 的 `models` | 可自訂區域端點，必須搭配相同區域的 key。原始開源 Qwen3 hybrid 型號的非串流請求設定 `enable_thinking: false`；商業與其他版本不套用這項限制。串流可要求 usage。[OpenAI 相容](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope)、[Thinking](https://www.alibabacloud.com/help/en/model-studio/deep-thinking) |
| Z.AI / GLM | `https://api.z.ai/api/paas/v4/chat/completions`；同 base 的 `models` | 不把 Coding Plan 專用端點與一般 API 混用。temperature 限制於 0–1、top_p 於 0.01–1；省略未在使用的 API schema 中列出的 penalties、stream_options。[Chat API](https://docs.z.ai/api-reference/llm/chat-completion) |
| Baidu Qianfan | `https://qianfan.baidubce.com/v2/chat/completions`；`GET /v2/models` | v2 使用 Bearer API key；既有舊版 `BaiduService` 的 OAuth API key + secret 相容路徑仍由設定遷移邏輯決定。[v2 API](https://cloud.baidu.com/doc/qianfan-api/s/3m7of64lb)、[官方 SDK](https://github.com/baidubce/bce-qianfan-sdk/blob/main/docs/inference.md) |
| Perplexity Sonar | `https://api.perplexity.ai/chat/completions` | 保留 citations/search_results；模型選擇使用 fallback 與手動輸入，避免把其他產品的 models 清單當成 Sonar 清單。省略 penalties 和 stream_options。[Sonar API](https://docs.perplexity.ai/api-reference/sonar-post) |
| Moonshot / Kimi | `https://api.moonshot.ai/v1/chat/completions`；`GET /v1/models` | Kimi thinking 系列省略採樣參數並使用服務端預設，以符合 K2.5/K2.6 的固定 temperature/top_p；一般 Moonshot temperature 限制於 0–1。省略 penalties 與未確認的 stream_options。[固定參數](https://platform.kimi.ai/docs/guide/use-kimi-vision-model)、[遷移指南](https://platform.kimi.ai/docs/guide/migrating-from-openai-to-kimi) |
| Mistral | `https://api.mistral.ai/v1/chat/completions`；`GET /v1/models` | `max_tokens`；接受文字或 content blocks 回覆並合併文字。保留模型清單 capabilities 與圖片判斷。[Chat API](https://docs.mistral.ai/api/endpoint/chat) |
| Gemini Live | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=...` | WebSocket 首先送 setup，再送即時音訊／圖片及 client content；不使用 HTTP SSE。依官方 WebSocket 文件保留 query key；Live 沒有獨立 HTTP models 清單。[WebSocket](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket) |
| AnythingLLM | 自訂 server，預設 `http://localhost:3001`；`POST /api/v1/workspace/{slug}/chat` | `message`、`mode: chat`，回覆 `textResponse`、`sources`。workspace slug 以單一路徑區段編碼，空白、斜線、加號不混淆。API 依自架版本的 `/api/docs` 為準。[API 說明](https://docs.anythingllm.com/features/api) |
| Local Gemma | `local://gemma/`，無 HTTP API、無 API key | 呼叫 `LocalInferenceEngine.generate/generateStream`。現有 build 未注入引擎時會明確回報模型不可用；不宣稱已完成原生推理，也不暗中轉雲端。[MediaPipe Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) |
| Custom / OpenAI-compatible | 自訂 base，預設 `http://localhost:11434/v1/`；`POST chat/completions`、`GET models` | API key 可留空；使用保守相容欄位。即使模型名與 OpenAI 相同，也不自動套用 OpenAI 的 reasoning/verbosity。[Ollama 相容介面](https://docs.ollama.com/api/openai-compatibility) |

## 語音與原始 app 模組

Gemini 內建轉寫走 generateContent 音訊輸入；OpenAI/Groq 內建轉寫走 multipart `POST audio/transcriptions`，分別使用 `gpt-transcribe` / `whisper-large-v3-turbo`，不拿聊天 model ID 當轉寫模型。其餘聊天供應商仍透過獨立 STT 選擇處理語音。專用 STT 的 18 個選項另見 [STT_IMPLEMENTATION_STATUS.md](STT_IMPLEMENTATION_STATUS.md)，本次未宣稱對每家付費 STT 服務完成實網驗證。

原始 `app` 模組的 `services/GeminiService.kt` 使用 `GenerativeModel.generateContent/sendMessage` SDK 封裝；其 Whisper 路徑同為 `/v1/audio/transcriptions`。本次主要更新 `phone-app` 的多供應商 HTTP adapter，未新增 Firebase、替換 SDK 或新增供應商。

## 共用修正

- 統一非串流、串流、圖片與連線探測的參數策略；連線探測不再對 Responses 模型硬送 Chat Completions。
- 保留遠端既有的 Gemini/OpenAI 串流 error 處理，補上 Responses `response.incomplete` 和 `error`；失敗後不發送 Completed、不寫入成功對話歷史。
- 保留文字區塊合併與串流空白；JSON null 不轉成字面上的 `null`。
- `stream_options.include_usage` 僅送到本次文件確認支援的 OpenAI、Groq、DeepSeek、Alibaba；不送給任意自訂相容伺服器。
- 沿用既有 build、unit tests、coverage 與 Sonar workflow；驗收另外確認 Sonar 分析步驟的實際 conclusion，不只看 workflow 顯示成功。

## 驗證方式與限制

`ProviderApiContractTest` 使用 MockWebServer 驗證 HTTP 契約、供應商參數、workspace URL 編碼、串流失敗與 history 行為。原有 Gemini、OpenAI、Responses、provider policy 測試同步更新，保留其餘 regression suite。

完整 CI 指令沿用 `.github/workflows/sonar.yml`：

```sh
./gradlew --no-daemon clean build :phone-app:createGithubDebugUnitTestCoverageReport --stacktrace
./gradlew --no-daemon sonar --info
```

最終驗收須以推送提交對應的 GitHub Actions 與外部 check 結果為準。Mock 測試與 CI 不等於每家供應商的付費帳戶實網測試；本次沒有使用使用者的 API key 發送付費推理請求。
