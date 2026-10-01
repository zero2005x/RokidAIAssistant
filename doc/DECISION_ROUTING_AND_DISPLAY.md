# 自動選模與眼鏡顯示設定

## 自動選模

手機「設定 → 自動選模」可以啟用決策層，選擇既有 Gemini／OpenAI 金鑰、TypeSafe Jev 或自架 Laya，並指定快速、均衡、高品質三個模型槽位。每個槽位都可跨 AI 供應商選擇模型；若選中的供應商缺少憑證，就使用主要模型。模型路由只用於一般文字對話與錄音轉寫後的提問；照片分析及 Gemini Live 維持原有專用流程。目前使用者採 Gemini 決策，Laya 尚未準備服務網址。

決策層會傳送**當前問題的文字**到所選服務。Jev／Laya 使用 `POST /v1/systemone` 的 `choice`；Gemini／OpenAI 使用結構化 JSON 分類。選中的生成模型會收到目前對話最近十則訊息。決策只回傳難度分類，**不會生成答案**，也不會觸發拍照或錄音。回答旁顯示實際生成模型及 App 的選模規則理由；LLM 分類不顯示自述信心百分比。

若決策服務連線失敗、結果信心不足、槽位未設定或憑證缺失，App 使用原本主要模型。若已選槽位生成失敗，App 再試主要模型。連線失敗不會默默改用另一個決策後端。使用者可以關閉自動選模，恢復原有流程。

### Jev

在設定中輸入 TypeSafe API key。手機連到 `https://api.typesafe.ai/v1/systemone`，以 Bearer token 驗證，指定 `jev-latest`。參考 [TypeSafe API](https://docs.typesafe.ai/api)。

### Laya

在自己的伺服器安裝 Hugging Face 模型頁列出的 Python 套件 `laya[serve]`，以 `laya-serve` 提供 Jev 相容的 `/v1/systemone`。使用者提供的 [receptron/laya](https://github.com/receptron/laya) 是 Node 實作，本身沒有此 HTTP 伺服器。設定填入**可由手機連線的基底網址**，不要附加 `/v1/systemone`。需要驗證時設定 `LAYA_API_KEY`，並在手機輸入相同 key。建議使用 HTTPS；HTTP 僅接受私有 IPv4／本機位址，且 Android 網路政策必須明確允許實際主機。尚未取得使用者伺服器位址，因此目前 APK 未開放 HTTP 主機；不可將全域明文流量開啟。HTTP 會未加密傳送問題和選填金鑰，設定頁有說明。`localhost` 指向手機本身。繁中問題應使用 multilingual checkpoint。參考 [Laya 模型與自架說明](https://huggingface.co/convaiinnovations/laya)。

Laya 基礎 checkpoint 在作者的 typed-decisions 零樣本測試表現有限；部署前應以真實問題驗證快速／均衡／高品質分類，必要時微調。沒有使用者範例時，專案測試僅能檢查 API 契約、信心門檻與備援流程，無法證實實際分類品質。

## 眼鏡文字顯示

手機「設定 → 眼鏡文字顯示」可調整全部眼鏡文字的字級、系統／等寬字形，以及可視區的寬度、高度、水平和垂直位置。黑底預覽反映未套用的草稿。按「套用到眼鏡」才儲存並傳送；重設只改變草稿，也需要按套用。App 把可視區限制在螢幕內並留至少 3% 邊距。

設定透過既有藍牙訊息 `SYSTEM_CONFIG` 傳送，眼鏡端會保存；眼鏡重連時手機再次同步已套用設定。主文字字級直接對應設定中的 sp，其餘文字按比例縮放。回答使用 Compose `TextMeasurer`，依主文字實際字形、密度、可用寬度與扣除狀態／提示後的高度分頁，保留每個字元與換行；單行比可用高度更高時仍保留文字並提供捲動。裝置選擇畫面也在設定範圍內。手機預覽使用眼鏡回報的 APP 畫面比例，未收到尺寸時明示採示意值。目前 RG_glasses 回報 480×640；實際光學可視範圍仍需配戴確認。

## 審查後修正

- 狀態列與提示分別最多使用可視區高度的 25% 與 20%，過長內容可捲動，避免小範圍／大字級把回答區擠掉。
- 判斷 HTTP 呼叫總時間最多 3.5 秒，逾時回到主要模型，不接受重新導向。
- 快速與均衡要求信心至少 0.5，高品質至少 0.3；三類機率需有效、總和接近 1，且所選類別機率至少 0.55 並為最大值。這是品質優先的初始規則，尚未證實真實分類準確率。參考 [TypeSafe confidence](https://docs.typesafe.ai/confidence)。
- 包含主要模型在內，每輪都從資料庫重新灌入最近歷史，避免跨模型切換失去脈絡。
- 所有服務失敗時保留主要模型的錯誤，手機顯示本地化錯誤並向眼鏡送 `AI_ERROR`，不將錯誤保存成 AI 回答或播放 TTS。
- 設定頁揭露問題文字送往 Jev／Laya，包括本機回答模型的情況；新增設定拆為獨立頁面。新增文案與理由支援原有 13 種語言，理由以穩定 JSON 代碼保存並在顯示時翻譯。
- GPT-Transcribe 的語言提示以 multipart `languages[]` 傳送 ISO-639-1 代碼，格式和官方 Python SDK 的陣列序列化一致；Whisper 保留 singular `language`。參考 [遷移指南](https://developers.openai.com/cookbook/examples/migrating_from_whisper_to_gpt_transcribe) 與 [官方 SDK](https://github.com/openai/openai-python/blob/main/src/openai/_base_client.py)。尚未用真實 key 和錄音確認辨識品質；提示中文不保證繁體輸出。


## 2026-10-01：既有供應商決策與預覽修正

- 決策頁新增 Gemini／OpenAI，分別使用原生 generateContent JSON schema／Responses strict JSON schema。重用現有供應商金鑰，決策模型可獨立選擇；三個回答槽位保持原設定。
- LLM 只回傳難度與「明確／不確定」，不把自述確定程度當成 Jev 的校準機率。理由存 `selected_llm` 代碼，在顯示時翻譯。
- 只送本次文字問題，不送照片、音訊或對話歷史。會增加一次供應商 API 請求；啟用開關維持使用者控制。分類無動作輸出，不啟動相機或錄音。
- 決策失敗、拒答、不完整、格式錯誤、不確定、缺金鑰或超過 3.5 秒均使用主要模型。超過 4000 字元的 LLM 分類不截斷後判斷，直接回退。
- 新增 `DISPLAY_METRICS`（0x33），眼鏡 App 回報畫面像素、密度、字體倍率與 SPP 相機能力。手機保存最近尺寸；未收到時使用標示為示意的 480×640 畫布。
- 手機預覽按裝置像素縮放文字與內距，狀態／主文字／提示採互不重疊區域。這是 App 畫面預覽，仍不能取代眼鏡光學可視範圍的配戴確認。
- SPP 連線先詢問能力，原生眼鏡 App 支援 SPP 相機時略過 CXR BLE 初始化；未回報能力的舊裝置仍使用 SDK 路徑。晚到的能力回報會取消本 App 的 SDK 重試。
- `ProviderConnectivitySmokeTest` 需明確指定 `verifyProviders=true` 才會執行付費網路測試；不輸出金鑰或把它們移出手機。

官方格式參考：[Gemini GenerateContent](https://ai.google.dev/api/generate-content)、[Gemini 驗證](https://ai.google.dev/gemini-api/docs/api-key)、[OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)。
