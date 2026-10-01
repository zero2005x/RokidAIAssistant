# Windows 實機測試（2026-09-30）

本節保留 9 月 30 日受測版本的結果；最新修正與更換金鑰後的測試見下方「2026-10-01」。先前的 401、預覽及 CXR 結果不可當作最新版本的結論。

## 裝置與版本

- Windows ADB；手機 `eeaas88ts4kn6l8t`（21091116UG，Android 13），眼鏡 `1901092544022855`（RG_glasses，Android 12L）。
- 手機 `com.example.rokidphone`、眼鏡 `com.example.rokidglasses`：1.1.0 / versionCode 5。
- 使用先前已安裝、以原有 release 憑證重簽的 debug APK。安裝雜湊與資料保留證據見 `DEVICE_INSTALLATION.md`。
- 本次實測約 21:57–22:15（Asia/Taipei）。本次未重新編譯、安裝或修改 App 程式碼。

## 避免干擾其他除錯工作

另一個 Claude Desktop 正在處理 RideFlux。每次輸入或啟動前讀取裝置前景套件；僅對本 App 操作，啟動時另允許裝置原有 launcher。未操作 RideFlux、停止其他 adb 程序、重啟 adb server、清空 logcat、重設藍牙配對或清除 App 資料。這項檢查能降低衝突，無法保證另一個程序不在檢查後同時操作裝置。

## 已驗證

| 項目 | 實測結果 |
| --- | --- |
| 啟動與導覽 | 兩個 App 可啟動；手機首頁、設定、獨立選模頁、獨立顯示頁、聊天頁可進入。 |
| 手機／眼鏡文字通道 | 手機開始監聽後，在眼鏡裝置清單選取 Redmi Note 11 Pro+ 5G，兩端顯示已連線；SPP 於 22:05:20 連線。 |
| 儲存的顯示設定 | 連線後眼鏡收到既有設定：等寬字體、17 sp、寬 51%、高 46%、水平 23%、垂直 45%。畫面內容位於此範圍。 |
| 套用才同步 | 手機改選系統字體，未套用時眼鏡文字節點與位置不變；按套用後裝置名称的排版寬度改變，並有 SYSTEM_CONFIG 傳送紀錄。 |
| 字級同步 | 暫時將 17 sp 改成 24 sp 並套用，眼鏡主文字、連線提示、手機名稱與底部提示一起放大；接著套用還原為 17 sp。 |
| 重設草稿 | 按重設後手機預覽改為預設值，眼鏡維持原設定；離開未套用的草稿並重新進入，讀回原有儲存值。 |
| 設定還原 | 最後確認全部六項值均為測試前的值；套用按鈕回到停用狀態，眼鏡文字大小也還原。 |
| AI 錯誤處理 | 實際文字請求收到 Gemini HTTP 401（金鑰無效）。手機顯示「AI 請求失敗」及原始原因，聊天中沒有新增 assistant 回答；眼鏡收到 AI_ERROR 並顯示重試提示。 |
| 崩潰紀錄 | 21:57 起可取得的 AndroidRuntime 紀錄未找到這兩個套件的崩潰標記；不代表所有功能或長時間穩定性都通過。 |

## 發現問題與阻礙

1. **AI 憑證阻礙：** Gemini 回傳 HTTP 401。設定頁顯示 Jev 已啟用、三個槽位已填，但未成功取得回答，因此不能宣稱 Jev 難度判斷、跨供應商成功回答、選模理由或對話脈絡實測通過。沒有修改、顯示或擷取金鑰。請使用者在手機確認憑證後再測。
2. **Rokid CXR BLE：** 22:07:30、22:09:44、22:12:04 等時間記錄 `Failed(error=BLE connect failed)`，之後持續重試；SPP 文字／設定通道仍正常。尚未定位原因，不能以 SPP 連線成功推論相機通道可用，也不能將失敗歸因於 Claude。未執行拍照。
3. **手機預覽文字重疊：** 在寬 51%、高 46%、24 sp 時，示意預覽的回答文字與右上狀態、底部提示重疊。這是手機預覽的實測排版問題；眼鏡使用不同排版，不能由此推論眼鏡回答分頁也失敗。本次僅記錄，未更換受測 APK。
4. **ADB 輸入法限制：** 第一筆合成英文題目被手機注音輸入法轉成混合文字；請求有實際送出，但不能當作「7 + 5」正確性測試。未修改使用者輸入法或把此問題列為 App 輸入缺陷。

## 尚未通過的項目

- 成功 AI 回答、Jev/Laya 分級、跨模型脈絡、實際選模理由；Laya 尚需服務網址與有效設定。
- 正常 AI 長回答的眼鏡分頁／逐頁完整性、最大字級與最小範圍的完整組合。
- 語音收音／轉錄／TTS、照片分析、Gemini Live、其他供應商及 13 語系實機檢查。
- 眼鏡實際光學可視範圍與配戴舒適度；ADB 截圖為 480×640，不能取代使用者配戴確認。

## 本機證據

截圖與 UI XML 在忽略目錄 `build/device-smoke-20260930/`，未把使用者聊天清單截圖加入版本控制：

- `phone-display-current`、`phone-display-draft`、`phone-reset-draft`、`phone-restored-final`。
- `glasses-unapplied`、`glasses-applied-system`、`glasses-size-24-applied`、`glasses-restored-final`。
- `phone-text-first-response`：原始 HTTP 401 顯示；`phone-size-draft`：預覽重疊。
- `log-summary.json`：僅保留連線／訊息類型／例外類別，不保留金鑰、題目或原始請求內容。

截圖與 UI XML 為連續兩次讀取，轉場時可能不完全同時；判斷連線／套用結果另以後续穩定畫面與日誌核對。

## 2026-10-01：修正版本與新 Gemini 金鑰

以 Windows ADB 對同兩台裝置操作，使用保留資料的 APK 更新。使用者同意辦公室拍攝，並在手機更換 Gemini 金鑰。每次輸入前同時檢查 resumed activity 與目前焦點視窗，遇到 LINE／Chrome 前景時停止；未操作 RideFlux 或共用 adb server。

| 項目 | 本次結果 |
| --- | --- |
| 新 Gemini 金鑰 | 使用者自行提出 `123&321=?`，App 成功回答 65；畫面顯示 `gemini-3.8-flash` 及 Gemini 結構化分類選擇快速的理由。這筆題目由使用者提出，非自動化合成測試。 |
| 真實供應商連線 | 手機端 opt-in Android instrumentation：Gemini／OpenAI 各一筆合成 `7 + 5` 對話與一筆繁中多步驟架構分類，`OK (4 tests)`，18.417 秒。金鑰讀取手機內加密設定，未移出手機。 |
| 決策時限的差別 | 原生 App 的 Gemini 選模已觀察到成功；instrumentation 的分類契約測試用 60 秒總時限，不能據此宣稱兩個後端均能在正式 3.5 秒預算內完成。 |
| 辦公室拍照與分析 | 更換金鑰後重新拍照，照片經 SPP 傳到手機，Gemini 成功分析，眼鏡顯示 4 頁回答；本次沒有 401。另一次更換前的拍照傳输成功但分析 401，不能混算成分析成功。 |
| 相機能力與比例 | 日誌顯示收到 `DISPLAY_METRICS`，`SPP; display 480x640`。原生眼鏡伴隨 App 使用 SPP 相機，未觀察到此前 CXR BLE 重試。此結果不代表舊裝置的 SDK 路徑已修復。 |
| 新手機預覽 | 顯示 `APP 顯示範圍：480 × 640 px`，採實際 APP 畫面比例。24 sp、51%×46%、等寬字體下，狀態、主文字與底部提示無重疊；主文字可捲動。 |
| 套用與還原 | 24 sp 草稿尚未套用時眼鏡維持 17 sp；套用後所有文字一起放大。最後套用還原 17 sp、等寬、寬 51%、高 46%、水平 23%、垂直 45%，手機套用按鈕停用，兩端重連成功。 |

證據位於忽略目錄 `build/device-smoke-20260930/`：`glasses-new-key-photo`、`glasses-photo-page-3`、`glasses-photo-page-4`、`phone-preview-size-24-draft`、`glasses-reconnected-before-apply`、`glasses-current-size-24-applied`、`phone-baseline-restored-final`、`glasses-current-restored-baseline`。未保存 API key 畫面；有可見非密碼輸入欄時跳過截圖，XML 的輸入欄均遮罩。

仍未實測：Jev／Laya 真實分類品質（Laya 未提供網址）、所有字級與範圍組合、跨模型多輪脈絡、語音收音／轉錄／TTS、Gemini Live、其餘供應商、13 語系及實際光學配戴範圍。Agents 依使用者指示延後。
