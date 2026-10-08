# Glasses AI Companion — Google Play 上架計畫

- **狀態**：v0.5，2026-10-03（D1～D4 已決定；P1 工程大致完成，通過單元測試、lint、release AAB 檢查與 API 36 模擬器煙霧測試；**真機（含眼鏡）尚未測**，見 §6.1、§6.2）
- **範圍**：只上架 `phone-app`（Play 版）；`glasses-app` 與舊 `app` 模組不上架
- **目標**：2026 年底前在 Google Play 正式發布。這是**目標，不是承諾**，以第 7 節的驗收門檻為準
- **公開 repo 注意**：本文不得出現法定姓名／地址、Console 帳號 ID、金鑰與密碼、Relay 信箱位址、審核用 API key。這些放在私人筆記

## 0. 怎麼讀這份文件

| 標記 | 意思 |
|---|---|
| ✅ | 你已明確回覆的決定 |
| ⚠️ | 需要你本人決定，我不預設答案 |
| 🔍 | 尚未驗證，不可當成事實使用 |
| ❌ | 我先前說錯或過時，已更正 |

證據等級：**A** 官方文件原文；**B** 本機程式碼或量測；**C** 第三方文章或論壇；**D** 推論。每項主張的來源在第 9 節。

## 1. 範圍

| 項目 | 範圍 |
|---|---|
| 上架 | `phone-app` 的 `play` flavor（AAB，手動上傳 Console） |
| 不上架 | `glasses-app`（走 GitHub／側載／Rokid 管道）、舊 `app` 模組 |
| 持續發布 | `phone-app` 的 `github` flavor（保留舊 id、現有金鑰、CXR、完整服務商清單、Ko-fi） |

## 2. 已決定事項 ✅

| ID | 決定 |
|---|---|
| D-01 | 只上架 `phone-app` |
| D-02 | 商店標題 **Glasses AI Companion**；簡短說明 "Unofficial AI companion for Rokid glasses. Not affiliated with Rokid."；商店說明明確聲明與 Rokid 無隸屬關係 |
| D-03 | Play 版 `applicationId` = `io.github.zero2005x.glassesaicompanion` |
| D-04 | `namespace` 同樣改為 `io.github.zero2005x.glassesaicompanion`（兩個 flavor 共用）。Play 並不要求這項，成本見 §4 D4 |
| D-05 | `play`／`github` 兩個 flavor；GitHub 版保留 `com.example.rokidphone` 與現有金鑰，獨立發布，不做資料遷移 |
| D-06 | 簽署：Play App Signing（Google 產生簽署金鑰）＋全新 upload key，與 `release-keystore.jks` 完全分開 |
| D-07 | Play 版完全移除 CXR（`client-m`、原生 `.so`、`sn_auth_file.lc`）；眼鏡功能只走 SPP 加側載的 `glasses-app` |
| D-08 | 服務商：LLM＝Gemini、OpenAI、Anthropic、自訂 OpenAI 相容端點；STT＝Gemini、OpenAI Whisper、Groq Whisper；排除 Gemini Live、AnythingLLM 與其餘服務商 |
| D-09 | TTS 只留系統 TTS；移除 Edge TTS 與 Google Translate TTS |
| D-10 | 自訂端點限 HTTPS，`localhost`／`127.0.0.1` 例外；區網 HTTP 不支援 |
| D-11 | 停用行事曆與聯絡人工具（含其工具定義，讓模型不會呼叫） |
| D-12 | 手機麥克風錄音只在 App 位於前景時進行，離開前景就停止並提示 |
| D-13 | Play 版移除 App 內 Ko-fi 連結（GitHub／網站保留） |
| D-14 | 首次啟動 4 張卡片，使用者按「我了解」才進入主畫面：①非官方聲明 ②眼鏡端說明（網頁連結）＋「先不用眼鏡」 ③資料揭露 ④旁人隱私提醒 |
| D-15 | `allowBackup="false"` 加 `dataExtractionRules` 全部排除；換機後對話歷史不自動還原 |
| D-16 | 日誌檢視與匯出保留，匯出時遮罩 API key 與回覆內文 |
| D-17 | 隱私政策與眼鏡端設定說明頁走 **Pages 來源 `/`（選項 A）**：在 repo 根目錄新增 `privacy.md`、`glasses-setup.md`；隱私政策網址 `https://zero2005x.github.io/RokidAIAssistant/privacy/`（英文＋繁中） |
| D-18 | 商店聯絡信箱、隱私政策聯絡信箱、AI 回報收件信箱統一使用同一個 Relay 別名（位址不入庫）；Play Console 帳戶擁有者仍是 Gmail |
| D-19 | 你選擇的 DSA 身分：**非交易者**（最終以 Console 實際題目與你的實際情況為準，見 §4 D6） |
| D-20 | 公開開發人員名稱改為 `zero2005x` |
| D-21 | 目標受眾 18 歲以上，不納入兒童 |
| D-22 | 商店語言 en-US（預設）＋ zh-TW；模擬器產生 13 種語言截圖存檔，v1.0 只上傳 2 套 |
| D-23 | 眼鏡端安裝說明只放**網頁連結**（設定說明頁），App 內與商店不放 APK 直連、不寫「開啟未知來源」之類步驟 |
| D-24 | `compileSdk`／`targetSdk` 升到 36，`minSdk` 維持 28；上傳 AAB；v1.0 手動上傳 Console |
| D-25 | Play 版首版 `versionName 1.2.0`、`versionCode 1`；GitHub 版 `versionCode` 獨立（這是我提出的預設，你未反對） |
| D-26 | 封閉測試招募 ≥16 位測試者（網路招募），10/24 前完成，並持續追蹤到 20 位 |

## 3. 更正紀錄 ❌

這些更正來自一份外部審查，以及我對官方頁面的逐項查證。

| # | 我先前的說法 | 查證結果 | 證據 | 對計畫的影響 |
|---|---|---|---|---|
| C-01 | 首次正式版用 20%→50%→100% 分階段推出 | **錯。** 官方：「Staged rollouts can only be used for app updates, not when publishing an app for the first time.」首次發布可限定國家，但不能用百分比 | E3 (A) | 首次正式發布方式改為 §4 D3；分階段推出只用於後續更新 |
| C-02 | AI 內容回報用 `mailto:` 即可 | **不符合政策。** 官方：App 必須提供「in-app user reporting or flagging features … without needing to exit the app」，並「utilize user reports to inform content filtering and moderation」 | E4 (A) | 見 §4 D1 |
| C-03 | 通過審核後立即作廢審核用 key | **錯。** 官方：審核用憑證須隨時可用、可重複使用、不受使用者地點影響，並持續維護；過期可能被拒 | E5 (A，經搜尋摘要) | 見 §4 D2 |
| C-04 | 16 KB 要求自 2026-05-01 起 | **日期來自第三方文章，過時。** 官方現寫：target Android 15+ 且 64 位元裝置的 App，自 **2027-02-01** 起不支援就不能發布更新。仍須完成，並以最終 AAB 驗收 | E6 (A) | 見 P2-1；不能只看 Gradle cache 的 `.so` |
| C-05 | 人數掉到 12 以下 14 天就整批重算 | **不精確。** 官方：每位測試者需連續加入 14 天，「opt in、測不到 14 天又退出」者不計；申請時須至少 12 位；不足或互動不足可能被要求延長測試 | E2 (A) | 招募 ≥16、追蹤到 20；每天追蹤有效人數 |
| C-06 | Google 審核時間查不到 | 官方：正式版存取權申請「通常 7 天內，但偶爾更久」。這不保證之後 App 審核的時程 | E2 (A) | M5 預留緩衝 |
| C-07 | 目前沒有首次啟動介面 | **不精確。** 已有 `InitialSetupDialog`（沒有 API key 時顯示），缺的是完整 onboarding | E10 (B) | P1-10 在現有基礎上擴充 |
| C-08 | `docs/` 沒被發布 | **錯。** `https://zero2005x.github.io/RokidAIAssistant/docs/` 已上線（Tailwind 頁，有「Download APK」按鈕連到 GitHub releases，沒有隱私政策） | E11 (B) | 隱私頁仍走 A；注意該頁含 APK 連結 |
| C-09 | App 名稱與 `applicationId` 上架後永遠不能改 | **混淆了兩者。** 只有 `applicationId` 不可改；App 名稱可後續修改（上限 30 字元） | E14 (C)、官方建立 App 說明 | 無影響 |
| C-10 | 資料安全「開發者不收集」 | 「收集」＝資料傳出使用者裝置，不論傳到你還是第三方（已於上輪更正）。若 App 內回報送到你的端點，你本人也成為接收者 | E7 (A) | §5 資料流矩陣 |
| C-11 | `com.example` 會被 Console 拒絕 | 證據是論壇回報，不是完整官方規則；改用自己的 id 仍合理 | E14 (C) | 以實際上傳 release AAB 驗收（P2-1） |
| C-12 | CXR 1.2.2 的 arm64 `.so` 是 16 KB 對齊 | 這只是我對 Gradle cache 內 AAR 檔案的**局部量測**，不是整個 App 合規的證明 | E9 (B) | Play 版移除 CXR 後，對最終 AAB 重新驗收 |

**審查提出，但我未採納或尚未驗證的項目：**
- 「身分聲明、回報信箱、18+、資料安全答案未獲確認」：實際對話中你已逐題明確回覆，所以列為 ✅。但 DSA 身分與資料安全答案仍應以送出當下的 Console 題目為準（§4 D6）。
- 「改開發人員名稱不會隱藏法定姓名」：我尚未驗證，列為 P0-2。
- 「CXR-L 是 Rokid 現行公開 SDK」：我搜尋兩次都無法確認，列為 P0-7，不當成事實。
- 「Android 開發者驗證」對 GitHub 版的影響：尚未驗證，列為 R13。

## 4. 待你決定 ⚠️

### D1 ✅ AI 內容回報機制：App 內表單＋極小接收端（你選 A）
- 每則 AI 回應（對話、照片分析、錄音分析、首頁對話）旁有旗標按鈕；點開後選原因、可填備註，**預設只附被回報的 AI 回應**，是否附上它所回答的訊息由使用者勾選，送出前對話框列出會傳送的內容
- 有傳送中、失敗（可重試／不可重試）與「已收到」確認；全程不離開 App
- 接收端：`tools/report-endpoint/` 提供 Cloudflare Worker＋KV（自動過期）的參考實作，並有 9 個 Node 測試。**你需要自行部署**並把網址放進 `local.properties` 的 `REPORT_ENDPOINT_URL`。Play 版 release 在沒有 https 網址時**建置會失敗**
- 代價（已接受）：你成為回報資料的接收者，資料安全表單與隱私政策已加上「回報」；保留期限見 D7
- 仍待辦：建立跨服務商的安全測試集、回報分級與處理紀錄（P1-11 後半）

### D2 ✅ 審核存取：專用 Gemini 免費 key 長期保留＋離線示範（你選 A＋C）
- **A**：專用的 Gemini 免費額度 key 寫在 Console「App 存取權」說明，**不作廢**，持續監控額度與有效性，每次更新前測試，輪替時同步更新 Console 說明。key 不入庫、不進 AAB（release 建置守門）
- **C**：App 內「離線示範」已實作：首次設定對話框與「設定」都有入口；回覆為預先寫好、不連網，並顯示橫幅。它只是補充，**不取代**真實功能的審核
- 風險不變：免費層可能限流；審核內容只用合成資料。若審核因限流被退，再升級為小額度付費 key

### D3 ✅ 首次正式發布：限定國家、100%（你選 A）
- 首次正式發布不能用百分比（C-01），改為限定少數國家發布，觀察後逐步新增國家。**國家清單由你在發布前決定**
- 之後的更新才使用 staged rollout

### D4 ✅ namespace 改名：照改（你已確認）
- 已完成：`phone-app` 的 `namespace` 與全部原始碼、測試、ProGuard 規則、Room schema 目錄、lint baseline 路徑改為 `io.github.zero2005x.glassesaicompanion`；`github` flavor 的 `applicationId` 仍是 `com.example.rokidphone`
- 驗證：兩個 flavor 的 816 個單元測試全過、`androidTest` 可編譯
- 尚未驗證：GitHub 版 v1.1.0 **覆蓋安裝後資料保留**（需要實機或模擬器上的安裝測試，列為 P2-3）
### D5 眼鏡路線
v1.0 Play 版移除 CXR，只走 SPP（已決定）。新增研究任務 P0-7：評估 CXR-L 與授權條件，結果只影響 v1.1 之後的路線，不阻擋 v1.0。

### D6 DSA 身分聲明
你選「非交易者」。這是有法律後果的自我聲明，由你本人在 Console 依實際題目回答，我不代填。Play 版目前無廣告、無內購，Ko-fi 只存在於 GitHub／網站。

### D7 資料保留期限
回報資料與日誌的保留期限由你決定。建議回報 90 天，日誌只存本機、使用者可自行清除。**目前程式、接收端與隱私政策暫時都寫 90 天**（`REPORT_RETENTION_DAYS`、`RETENTION_DAYS`、`privacy.md`），你決定後再一起改。

### D8 Gemini 免費層提醒
Gemini API 非付費服務條款允許 Google 將內容用於改善產品，且人工可能審閱，並要求不要送出敏感、機密或個人資訊（歐洲經濟區、瑞士、英國例外）。建議在首次啟動卡片 3 加入這項提醒。**請決定是否加入**（目前卡片 3 尚未加入；隱私政策頁已寫明免費層條款）。

### D9 系統 TTS 是否可能連網
Android `Voice.isNetworkConnectionRequired()` 顯示部分系統語音需要網路，所以「系統 TTS」不等於離線。選項：A 在隱私政策與卡片 3 揭露；B 提供「僅用離線語音」開關。建議 A 加 B。（目前隱私政策已揭露「文字轉語音可能由系統引擎提供者透過網路處理」，App 內尚無離線語音開關。）

## 5. 資料流矩陣（草稿；🔍 為待盤點）

| 資料 | 離開裝置 → 接收者 | 本機保存 | 外部保留／次級用途 | Data safety 申報 | 刪除 |
|---|---|---|---|---|---|
| 使用者文字訊息 | 是 → 使用者所選服務商 | Room 對話歷史 | 🔍 依各服務商方案；Gemini 免費層可能用於改善並由人工審閱 | 收集：訊息／App 內內容 | App 內刪除對話；外部資料需向服務商申請 |
| 歷史上下文 | 是 → 同上（隨請求附帶） | 同上 | 同上 | 同上 | 同上 |
| 照片（眼鏡／相簿） | 是（影像分析時）→ 同上 | App 私有儲存 | 🔍 | 收集：照片 | App 內刪除 |
| 錄音／語音 | 是（STT）→ Gemini／OpenAI／Groq | 錄音檔 | 🔍 | 收集：音訊 | App 內刪除錄音 |
| STT 轉錄文字 | 是 → LLM 服務商 | 對話歷史 | 🔍 | 同文字訊息 | 同上 |
| TTS 文字 | 🔍 視系統語音引擎而定（D9） | — | 🔍 | 🔍 | — |
| API key | 僅作為對所選服務商請求的憑證 | 加密儲存，備份排除，日誌遮罩 | 不傳給開發者 | 🔍 是否須申報 | 使用者可在設定刪除；解除安裝清除 |
| AI 回報（D1） | 是 → 開發者的接收端 | — | 開發者保留（D7） | 收集：回報內容 | 🔍 依 D7 |
| 日誌 | 否；僅使用者分享時離開 | 本機 | — | 不收集 | 使用者清除／解除安裝 |
| 眼鏡 SPP 傳輸 | 藍牙近距離，不經網路 | — | 眼鏡端 App 不在 Play 範圍 | — | — |
| 分析／當機 | 無 SDK；Android vitals 由 Google 收集 | — | — | 無 | — |

**待確認**：Data safety「用途」、「是否分享」、「是否短暫處理」不能先挑最簡單答案。每條資料路徑須記錄適用哪項例外，並由畫面、操作或合約支持（官方文字的判讀見 E7；這不是 Google 的保證）。

## 6. 工作分解與驗收門檻

> 工程在新分支 `play-release-prep` 以獨立 PR 進行。**不會自動 commit 或 push。**

### P0 帳號與證據（本週）

| ID | 工作 | 負責 | 驗收門檻 |
|---|---|---|---|
| P0-1 | 在 Console 建立 App 條目；確認是否出現 12 人封閉測試任務、標題是否可用 | 你 | 不含私人資訊的結論文字 |
| P0-2 | 在 Console「開發者設定檔」預覽公開資訊（法定姓名、國家、Email 的顯示範圍） | 你 | 確認公開內容符合預期 |
| P0-3 | 招募測試者並建立 Google 群組；招募貼文由我撰寫 | 你／我 | ≥16 人，持續追蹤到 20 |
| P0-4 | 寄信給 Rokid（附錄 B，包含 `glasses-app` 使用的 `cxr-service-bridge`） | 你 | 已寄出，回信存檔（不入庫） |
| P0-5 | Relay 信箱收信測試 | 你 | 能收到 Console 驗證信 |
| P0-6 | 建立證據清單與保存原始輸出 | 我 | §9 完整 |
| P0-7 | 評估 CXR-L（查 Rokid 官方文件） | 我 | 書面結論，標明未驗證項目 |

### P1 工程

| ID | 工作 | 驗收門檻 |
|---|---|---|
| P1-1 | `play`／`github` flavor；`client-m` 與 `.lc` 只進 `github`；CXR 空殼實作 | release 依賴圖、merged manifest、最終 AAB 內無 `client-m` 類別、無 `.so`、無 `sn_auth_file` |
| P1-2 | `compileSdk`／`targetSdk` 36 | 單元測試全綠；手動測 edge-to-edge、大螢幕、前景服務、藍牙 |
| P1-3 | namespace 改名（D4） | 全部單元與儀器測試綠；GitHub 版 v1.1.0 覆蓋安裝資料保留 |
| P1-4 | Play `applicationId` | 以該 id 的 release AAB 上傳 Console 成功 |
| P1-5 | release 建置禁止帶入開發者 `BuildConfig` key（移除 `PhoneAIService` 的 fallback） | 最終 AAB 字串掃描 0 命中（用合成 key 做負向測試證明掃描有效） |
| P1-6 | 服務商與功能精簡（D-08～D-11） | Play 版 UI 無被排除項目；工具定義不含停用工具 |
| P1-7 | 權限流程解耦：按功能請求；拒絕藍牙仍可手機模式；拒絕麥克風仍可文字聊天；拒絕通知不阻擋；移除未使用的 `BLUETOOTH_SCAN`／`BLUETOOTH_ADVERTISE`（先驗證）；`uses-feature required=false` | 權限拒絕矩陣手動測試通過。（現況：`MainActivity` 啟動時一次要求全部，任一被拒就不啟動服務） |
| P1-8 | 前景服務 `connectedDevice` 驗證 | Android 14+ 類型權限正確；Console 聲明文字完成 |
| P1-9 | 錄音僅前景（D-12） | 切到背景即停止並提示 |
| P1-10 | 首次啟動 4 卡片（含 D8、D9 揭露） | 4 卡片按「我了解」才進主畫面；可選純手機模式 |
| P1-11 | AI 回報（D1）、安全測試集、處理流程 | App 內送出成功／失敗／收件確認皆可驗證 |
| P1-12 | HTTPS-only＋localhost 例外 | 測試：區網 HTTP 被拒、localhost 允許、HTTPS 降級被擋、無效憑證不被忽略 |
| P1-13 | 備份規則（D-15） | 清資料、重裝、換機還原、裝置轉移測試 |
| P1-14 | 日誌遮罩驗收 | 用合成 key（header／query／Authorization／錯誤本文）與對話內容測 logcat、App 日誌、匯出檔 |
| P1-15 | Play 版移除 Ko-fi；Play 版 `app_name`、`home_welcome` 等 13 語言中的「Rokid」品牌字串處理 | 字串稽核 |
| P1-16 | 文件更新：play／github 建置指令、`PLAY_UPLOAD_*` 與 `RELEASE_*` 分離 | README 與本文同步 |

### P2 驗證

| ID | 工作 | 驗收門檻 |
|---|---|---|
| P2-1 | 最終 AAB 檢查：用 bundletool 產生 APK；`check_elf_alignment.sh` 或 `llvm-objdump -p` 看 LOAD 對齊（需 `align 2**14` 以上）；`zipalign -c -P 16 4`；merged manifest 權限審視；16 KB 環境（Android 15／16 的 16 KB 模擬器）啟動與主要功能測試 | 全部通過，輸出存檔 |
| P2-2 | 測試矩陣：無眼鏡、無 key、失效 key、限流、斷網、無 TTS 引擎、拒絕權限、大螢幕、深色、TalkBack、各語言 | 矩陣全通過 |
| P2-3 | 實機：Redmi Note 11 Pro+ 5G、模擬器 `api36_test`、SPP 搭配 GitHub 版 `glasses-app` | 主要流程通過 |
| P2-4 | release lint 與 Sonar quality gate | 通過 |

### P3 內容與 Console

| ID | 工作 | 驗收門檻 |
|---|---|---|
| P3-1 | `privacy.md`（英＋繁中）、`glasses-setup.md` | 公開可訪問、無登入、無轉址；內容與 App／資料安全表單一致；App 內有入口；Console 填最終網址 |
| P3-2 | 商店文案 en-US＋zh-TW：強調差異化（自備 key、Anthropic／自訂端點、錄音分析、對話歷史、照片分析）；非官方聲明；誠實揭露眼鏡端需求 | 你審稿 |
| P3-3 | 模擬器 `api36_test` 腳本產生 13 語言截圖；上傳 en-US＋zh-TW | 無 Rokid 商標與眼鏡實體圖 |
| P3-4 | 512×512 圖示 PNG、1024×500 feature graphic | 檔案規格符合 |
| P3-5 | 資料安全表單答案（依 §5，待 D1／D7／D9） | 逐項附證據 |
| P3-6 | 應用程式內容聲明：目標受眾 18+、內容分級、無廣告、AI 生成內容、前景服務聲明（`connectedDevice`，必要時附影片） | 由你送出 |
| P3-7 | App 存取說明（D2，英文）：說明純手機模式與硬體需求 | 由你送出 |

### P4 發布

| ID | 工作 | 驗收門檻 |
|---|---|---|
| P4-1 | 內部測試（可選） | — |
| P4-2 | 封閉測試；每天追蹤有效 opt-in 人數與回饋 | ≥12 位連續 opt-in 14 天 |
| P4-3 | 申請正式版存取權（表單三段：封閉測試經驗、App 價值、上線準備度） | 已送出 |
| P4-4 | 首次正式發布（D3，不用百分比） | 發布成功 |
| P4-5 | 後續更新才使用 staged rollout | 依崩潰、回饋與回報處理能力決定 |

### P5 維護
政策與服務商條款定期檢查；每年 8/31 前升 `targetSdk`（2027 年預期 API 37）；政策通知寄到帳戶擁有者 Gmail；`16 KB` 2027-02-01 前確認。

### 6.1 工程進度（2026-10-03，分支 `play-release-prep`，尚未 commit）

| ID | 狀態 | 說明／證據 |
|---|---|---|
| P1-1 | ✅ | `play`／`github` flavor；CXR 經 `CxrGlassesBridge` 隔離，`client-m`、retrofit 等只進 `github`；`sn_auth_file.lc` 移到 `github`。Play 版 release runtime classpath 無 Rokid／retrofit |
| P1-2 | 🟡 | `targetSdk`／`compileSdk` 已是 36。**Robolectric 4.14.1 最高支援 SDK 35**，目前用 `robolectric.properties` 把模擬 SDK 固定為 35；待升級 Robolectric 後改回 36。edge-to-edge、大螢幕、前景服務等行為變更**尚未在實機測過** |
| P1-3 | ✅ | namespace 改名（見 D4） |
| P1-4 | 🟡 | `applicationId` 已設定；以該 id 的 release AAB 上傳 Console 尚未做 |
| P1-5 | ✅ | `play` 的 `BuildConfig` key 永遠是空字串；`github` release 在 `local.properties` 有開發者 key 時建置失敗（`-PallowDeveloperKeysInRelease=true` 可繞過）。最終 AAB 掃描見 P2-1 |
| P1-6 | ✅ | `DistributionRules`：Play 版只提供 Gemini／OpenAI／Anthropic／自訂；STT 只有 Gemini／OpenAI Whisper／Groq Whisper；TTS 只有系統；Ko-fi 按鈕搬到 `github` flavor（Play 版連字串都沒有）；日曆工具移除、`make_call` 不含 `contact_name`。Edge TTS 客戶端搬到 `github` flavor，Play 只留空殼 |
| P1-7 | ✅ | 不再於啟動時一次要求全部權限；藍牙在使用者啟動眼鏡連線時才要求，麥克風在開始錄音時才要求，通知不阻擋；Play 版移除 `BLUETOOTH_SCAN`／`BLUETOOTH_ADVERTISE`／`ACCESS_FINE_LOCATION`。仍待實機權限拒絕矩陣測試 |
| P1-8 | 🟡 | `connectedDevice` 前景服務保留；Android 14+ 類型權限在實機上的行為待驗證 |
| P1-9 | ✅ | 離開前景時停止手機錄音並提示（`ForegroundRecordingPolicy`，4 個測試）；螢幕旋轉不會中斷 |
| P1-10 | 🟡 | 首次啟動 4 卡片已完成（含眼鏡設定說明連結）。D8／D9 的額外提醒待你決定 |
| P1-11 | 🟡 | App 內回報、傳送端、對話框、接收端範例已完成（共 21 個測試）。安全測試集與處理紀錄未做 |
| P1-12 | ✅ | `network_security_config`：HTTPS，僅 `localhost`／`127.0.0.1` 允許明文；自訂端點 URL 驗證同步（9 個測試）。兩個 flavor 都適用 |
| P1-13 | ✅ | Play 版 `allowBackup=false`＋`dataExtractionRules`，已在合併後的 manifest 確認；備份／還原／換機行為待實機測試 |
| P1-14 | ✅ | `LogRedactor`（共用，`ProviderApiException` 也改用它）在**寫入**與**匯出**兩處遮罩 Bearer／Basic／`sk-`／`AIza`／JWT／query／JSON 欄位／`x-api-key` 等標頭；STT、Baidu、Gemini Live、Anthropic 的「記錄內容」log 改成只記長度；伺服器錯誤本文改用 `snippet()`（遮罩＋截斷）；網路攔截器不再記 query string。16 個測試（含「key 經過所有路徑都不會出現在匯出檔」）。首版實作曾把 Base64 的 `=` 誤判成 query，被既有測試抓到並修正 |
| P1-15 | ✅ | Play 版 `app_name`／`app_title`／`home_welcome`（13 語系）改為 Glasses AI Companion；通知頻道、通知標題、日誌標頭改吃資源 |
| P1-16 | ✅ | README／文件的指令與路徑已更新；CI 的覆蓋率路徑已改 `githubDebug`。Play 的 `bundlePlayRelease` 在沒有 `REPORT_ENDPOINT_URL` 時會失敗，CI 的 `assemblePlayRelease` 不受影響 |
| 新增 | ✅ | 47 個新字串已補齊 12 個語系；`privacy.md`（英＋繁中）與 `glasses-setup.md` 已寫好（尚未發布，等合併到 `main`） |

### 6.2 驗證結果（2026-10-03）

| 項目 | 結果 |
|---|---|
| 單元測試 | `play`、`github` 各 **832** 個全過、0 失敗（改動前 phone-app 為 782；新增 50 個）。Robolectric 仍固定模擬 SDK 35 |
| Lint（debug，兩個 flavor） | **0 個未列入 baseline 的錯誤**。警告多為既有項目。與本次相關的 3 項已修（`Modifier` 參數順序、`%d days` 複數提示、`dataExtractionRules` 缺 `fullBackupContent`） |
| 16 KB（lint） | `github` flavor 的 `com.rokid.cxr:client-m:1.0.4` 的 `libcaps.so` **不是** 16 KB 對齊（Play 版不含它）。若 GitHub 版要在 16 KB 裝置上使用，需升級 `client-m`（本機 cache 有 1.2.2，API 相容性未驗證）。`play` 的唯一警告來自測試用的 mockk，不會進 AAB |
| Play 版 release AAB（重建後，R8 開啟） | `bundlePlayRelease` 成功。**不含** `com.rokid`、`sn_auth`／`.lc`、`res/raw`、Edge TTS 端點或權杖、`ko-fi`；沒有開發者 API key。原生函式庫只有 androidx 的 2 個（4 個 ABI，共 8 個檔案，LOAD 對齊全為 16384）。唯一命中的 `rokid` 字樣是內部的共用協定函式庫名稱（`com.example.rokidcommon`）、藍牙服務名稱與樣式名稱，使用者看不到。第一次建置曾發現 `ko-fi` 字串還在 dex 裡（按鈕被藏起來但程式碼還在），已改成 flavor 分離並重建確認 |
| GitHub 版 release APK | 用既有 `RELEASE_*` 金鑰簽署成功，**簽署憑證 SHA-256 與 v1.1.0 發布說明一致**（`7ca3a3f7…e30b`），所以舊版使用者仍可原地升級。`github` 的 release 建置在 `local.properties` 有開發者 API key 時會失敗（守門） |
| Android 16 模擬器（API 36，x86_64，Play 版 debug） | **已驗證**：首次啟動畫面正常且啟動時沒有任何權限彈窗；接受後出現設定對話框，供應商清單只有 Gemini／OpenAI／Anthropic／自訂；離線示範可聊天；回報對話框正確顯示（原因未選時「送出」停用，端點不可達時可重試）；點「手機錄音」才詢問麥克風權限；錄音中按 Home 離開再回來，出現「錄音已停止」且錄音檔已儲存；點「啟動服務」才詢問「附近的裝置」與通知，兩者都拒絕後 App 仍可使用、無崩潰；設定頁沒有 Ko-fi。**注意**：模擬器使用軟體繪圖，很慢，曾出現系統 UI 的 ANR（非本 App） |
| 實機（含眼鏡） | **尚未測試**：與真眼鏡的 SPP 連線、前景服務在 Android 14+ 實機上的行為、備份／還原／換機、16 KB 頁面大小的裝置（模擬器為 4 KB）、v1.1.0 覆蓋安裝後資料保留、各語系版面（RTL 阿拉伯文）、TalkBack |

**待辦（工程）：** 實機測試（P2-2、P2-3）；Sonar；跨服務商安全測試集與回報處理紀錄；部署回報接收端並填入 `REPORT_ENDPOINT_URL`；`CxrMobileManager` 在 `github` flavor 的單元測試仍沿用既有覆蓋。

## 7. 時程（目標，以門檻為準）

| 里程碑 | 目標日 | 前置門檻 |
|---|---|---|
| M0 帳號與招募啟動 | 10/10 | P0-1～P0-5 |
| M1 工程完成 | 10/24 | P1 全部＋P2-1 初測 |
| M2 封閉測試開始 | 10/25 | AAB 通過 Console 上傳檢查；≥12 人已加入 |
| M3 14 天達標 | 11/08（最早） | 每天追蹤有效人數 ≥12 |
| M4 申請正式版存取權 | 11/09 | P3 完成 |
| M5 取得正式版存取權 | 11/16–11/30（預估） | 官方「通常 7 天內，可能更久」 |
| M6 首次正式發布 | 11 月下旬–12 月上旬 | D3 決定 |
| M7 首次更新（staged） | 12 月 | 依穩定度 |

## 8. 風險登錄

| ID | 風險 | 緩解 |
|---|---|---|
| R1 | 帳號建立日期未驗證；若 12 人規則不適用，時程可縮短 | P0-1 |
| R2 | 測試者流失或互動不足，可能被要求延長測試 | 招 ≥16、追到 20；每日追蹤；收集真實回饋 |
| R3 | 正式版存取權審核超時 | M5 預留緩衝；12/31 為目標而非承諾 |
| R4 | `targetSdk` 36 帶來行為回歸 | P1-2、P2-2 |
| R5 | 審核員存取失敗（限流、key 失效） | D2；事先測試限流與失效 |
| R6 | AI 內容政策不合規 | D1、安全測試集、處理紀錄 |
| R7 | 隱私政策、資料安全表單、App 行為不一致，導致更新被擋 | §5 逐項對照；P3-1 驗收 |
| R8 | 眼鏡端設定連結被視為促進側載。現行 Device and Network Abuse 文字我沒有看到逐字禁止「獨立 companion App 的設定連結」，2018 年先例（E13）只是參考；具體設計仍可能被審 | D-23；說明不刻意模糊；設定頁措辭保守 |
| R9 | Rokid SDK 與商標授權：`glasses-app` 仍打包 `cxr-service-bridge`，影響 GitHub 版散布與整體可交付性 | P0-4、P0-7 |
| R10 | namespace 改名造成回歸 | D4；獨立 PR |
| R11 | 一般使用者沒有眼鏡端 App 可裝而給差評 | 商店文案誠實揭露硬體需求 |
| R12 | 16 KB 合規 | P2-1；以最終 AAB 驗收 |
| R13 | Android 開發者驗證對 GitHub 版的影響 🔍 | 另行查證；不阻擋 Play |
| R14 | 單人維護 | 政策信件導到 Gmail；每年 targetSdk；定期檢查 |

## 9. 證據清單

| ID | 主張 | 來源 | 等級 | 狀態 |
|---|---|---|---|---|
| E1 | 自 2026-08-31 起新 App 與更新須 target API 36 | https://developer.android.com/google/play/requirements/target-sdk | A（經搜尋摘要，未逐字讀原頁） | 建議上傳前再讀原頁 |
| E2 | 新個人帳號（2023-11-13 後建立）需 12 位測試者連續 opt-in 14 天；審核通常 7 天內 | https://support.google.com/googleplay/android-developer/answer/14151465 | A | 已查（2026-10-03） |
| E3 | 分階段推出僅限更新，不能用於首次發布 | https://support.google.com/googleplay/android-developer/answer/6346149 | A | 已查 |
| E4 | AI 生成內容須提供 App 內回報，不需離開 App | https://support.google.com/googleplay/android-developer/answer/13985936 | A | 已查 |
| E5 | 審核用憑證須隨時可用、可重複使用、不受地點影響 | https://support.google.com/googleplay/android-developer/answer/15748846 | A（經搜尋摘要） | 建議上傳前再讀原頁 |
| E6 | 16 KB 要求日期 2027-02-01、驗證步驟 | https://developer.android.com/guide/practices/page-sizes | A | 已查 |
| E7 | 資料安全「收集」「分享」定義與例外 | https://support.google.com/googleplay/android-developer/answer/10787469 | A | 已查；例外的判讀屬我的解讀 |
| E8 | Gemini 免費層內容用於改善、可能人工審閱；EEA／CH／UK 例外 | https://ai.google.dev/gemini-api/terms | A | 已查 |
| E9 | `client-m` 1.0.4 arm64 `.so` LOAD 對齊 4096；1.2.2 為 16384 | 本機 Gradle cache 量測 | B | 🔍 僅 AAR 內檔案，非最終 AAB |
| E10 | phone-app 現況：`targetSdk 34`、`allowBackup true`、`BuildConfig` key fallback、`InitialSetupDialog`、權限一次要求 | repo 程式碼 | B | 已查 |
| E11 | Pages 來源 `/`；`/docs/` 已上線且含 APK 連結；repo 公開、無 LICENSE | `gh api`、網頁擷取 | B | 已查 |
| E12 | CXR-M 需開發者認證與 SN 鑑權檔；個人帳號綁定 10 台 | 本機私人 Rokid 文件（未入庫） | C | 🔍 未對照 Rokid 官方 SDK 頁 |
| E13 | 2018 年因彈窗引導下載站外配套 APK 而被下架的先例 | https://9to5google.com/2018/09/04/google-pulls-rootless-pixel-launcher-play-store/ | C | 僅先例，不是現行政策文字 |
| E14 | `com.example` 被 Console 拒絕 | 論壇回報 | C | 🔍 以實際上傳驗收 |
| E15 | CXR-L 是現行公開 SDK | 無 | — | 🔍 未驗證 |
| E16 | 個人帳號改顯示名稱後，法定姓名／國家／Email 的公開範圍 | 無 | — | 🔍 P0-2 |
| E17 | 「交易者」定義 | 第三方（Apple 版）整理 | C | 🔍 以 Console 為準 |
| E18 | Edge TTS 使用非公開端點，條款未驗證 | repo 程式碼 | B | 🔍 條款未驗證；Play 版已移除 |

## 附錄 A：upload key 流程（不含任何密碼）

1. 在自己的電腦產生新 upload key（不要與 `release-keystore.jks` 共用）：

```bash
keytool -genkeypair -v -keystore play-upload.jks -alias play-upload -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=zero2005x Play upload"
```

2. **憑證識別名稱用中性字串**（上面的 `-dname`），不要填真名。我檢查 GitHub 版 APK 時發現既有的 `release-keystore.jks` 憑證裡含有你的真名與學校資訊，而且它會隨 APK 公開；Play 版的簽署憑證由 Google 持有，但 upload key 仍建議用中性名稱
3. 密碼存密碼管理器，另備離線加密備份；檔案**不進 repo**（`.gitignore` 已排除 `*.jks`）
4. 在 `local.properties` 使用獨立的 `PLAY_UPLOAD_*` 設定，與現有 `RELEASE_*` 分開
5. 第一次上傳 AAB 時選用 Play App Signing；遺失 upload key 可在 Console 申請重設

## 附錄 B：給 Rokid 的詢問信稿（英文）

> Subject: Questions about publishing a companion app that uses Rokid SDKs
>
> Hello Rokid team, I am an individual developer (Rokid developer account: `<your ID>`) building an unofficial, free companion app for Rokid glasses. The phone app will be published on Google Play; the glasses-side app is distributed separately (GitHub). Could you please confirm: (1) whether redistributing the CXR-M SDK (`com.rokid.cxr:client-m`) and the CXR-S SDK (`com.rokid.cxr:cxr-service-bridge`) inside apps on public channels is permitted; (2) whether the SN authorization (`.lc`) model allows end users' own glasses to connect, or only devices bound to my developer account, and whether an enterprise/ISV tier lifts the 10-device limit; (3) how CXR-L differs from CXR-M for third-party phone apps and whether it is intended for public distribution; (4) whether I may refer to "Rokid" nominatively in the store description (e.g. "for Rokid glasses, unofficial, not affiliated"). Thank you.

## 變更紀錄

| 版本 | 日期 | 變更 |
|---|---|---|
| v0.2 | 2026-10-03 | 初稿。整合外部審查與官方文件查證；更正 C-01～C-12；新增待你決定 D1～D9、資料流矩陣、驗收門檻與證據清單 |
| v0.3 | 2026-10-03 | D1（App 內回報）、D2（A＋C）、D3（限定國家）、D4（照改）已決定；新增工程進度 §6.1。首次使用 play-release-prep 分支實作 |
| v0.4 | 2026-10-03 | 日誌遮罩完成（P1-14）；Ko-fi 與 Edge TTS 改為 flavor 分離；新增 §6.2 驗證結果；CI 守門調整 |
| v0.5 | 2026-10-03 | 新增 Play 專用的供應商／TTS 說明文字（13 語系）；重建 AAB 並確認 Ko-fi 已移除；GitHub 版簽署憑證與 v1.1.0 一致；新增 API 36 模擬器煙霧測試結果；upload key 改用中性 -dname |
