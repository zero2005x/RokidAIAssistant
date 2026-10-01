# 審查修正與建置驗證

日期：2026-09-30（Asia/Taipei）。對象為 `phone-app`、`glasses-app` 與 `common`；使用者安裝手機／眼鏡兩個 APK。

## 修正範圍

- 眼鏡回答依 Compose 實際字形、寬度、高度與密度分頁，保留換行、空白行與 emoji；超高單行仍保留並提供捲動。
- 所有眼鏡文字共用字形與字級倍率，裝置選擇器位於設定可視區內。狀態列／提示限制高度並提供捲動，避免擠掉主回答。
- 每輪從資料庫補回生成模型歷史；快速／均衡信心要求至少 0.5，高品質至少 0.3，並檢查完整機率分布。
- 決策呼叫限制總時間 3.5 秒，失敗回到主要模型。保留主要服務錯誤，不儲存為回答或播放 TTS。
- Gemma 未載入模型與舊版百度認證／API 失敗也提供獨立錯誤狀態，避免把既有服務回退字串當成成功答案。
- 選模理由以代碼保存並依目前語言顯示；新增文案涵蓋原有 13 種語言。設定揭露問題文字會送到決策後端。
- 路由與眼鏡設定拆成獨立頁面；眼鏡變更按套用後才儲存／同步。
- GPT-Transcribe 語言提示改成 multipart `languages[]`；供應商型號／能力與退役遷移更新見 [AI 服務審查](AI_PROVIDER_API_AUDIT.md)。

操作與限制詳見 [Jev / Laya 與眼鏡顯示設定](DECISION_ROUTING_AND_DISPLAY.md)。

## 驗證方式

Windows Gradle 在本機因 Unix domain socket／loopback 初始化錯誤無法執行，改以 Kali WSL、JDK 21、Gradle 9.6.1、Android build-tools 36 建置。暫時 SDK 路徑只用於建置期間，完畢還原 Windows 路徑；未修改 release 簽署資料。WSL 先前被換掉的 debug keystore 已還原，打包透過本機 init script 指向 Windows debug keystore。

```text
:common:testDebugUnitTest
:phone-app:testDebugUnitTest
:glasses-app:testDebugUnitTest
:common:lintDebug
:phone-app:lintDebug
:glasses-app:lintDebug
:phone-app:assembleDebug
:glasses-app:assembleDebug
--continue
```

完整測試已通過：`common` 73 項、`phone-app` 810 項、`glasses-app` 27 項，均無失敗、錯誤或略過。手機報告時間為 2026-09-30 14:26–14:39，共用與眼鏡結果由先前成功執行的 Gradle 快取沿用。原始 XML 保存於 `build/verification/full-test-run-20260930/{module}/`。

此前 6 個手機失敗為 DeepSeek 舊能力斷言，以及 Gemini／Claude 既有回退文字斷言，已修正對應測試或恢復既有服務回退字串。完整測試通過後又補上 Gemma／舊百度的獨立錯誤狀態，並修正眼鏡高度限制的 Compose 作用域編譯問題；因此進行以下最終回歸驗證，不把第一次打包當成最終 APK：

```text
:phone-app:compileDebugKotlin :glasses-app:compileDebugKotlin
:phone-app:testDebugUnitTest
  --tests '*.DecisionRouterTest'
  --tests '*.BaiduServiceTest'
  --tests '*.LocalGemmaServiceTest'
  --tests '*.BaseAiServiceTest'
:glasses-app:testDebugUnitTest
:phone-app:lintDebug :glasses-app:lintDebug
:phone-app:assembleDebug :glasses-app:assembleDebug
--continue
```

最終來源已重新編譯通過。最後的 Gemma／百度錯誤狀態修正以手機 4 個測試類、40 項回歸驗證，眼鏡重新執行 3 個測試類、27 項，均無失敗、錯誤或略過。手機回歸報告從 14:56 開始，眼鏡從 14:49 開始；目前 `phone-app/build/test-results/testDebugUnitTest/` 內為這 40 項回歸，完整 810 項報告請查看前述保存目錄。

| 手機最後回歸類別 | 項數 |
| --- | ---: |
| `DecisionRouterTest` | 8 |
| `BaiduServiceTest` | 18 |
| `LocalGemmaServiceTest` | 8 |
| `BaseAiServiceTest` | 6 |

最後建置 `BUILD SUCCESSFUL in 19m 39s`，兩端 `assembleDebug` 均成功。完整執行紀錄為 `build/final-regression-verification.log`；先前完整測試的建置紀錄為 `build/verification/full-test-run-20260930/build.log`（該次完整測試通過，眼鏡編譯曾失敗，後續已修正）。

## lint 結果

| 模組 | 報告中的錯誤 | 報告中的警告 | 既有 baseline 過濾 |
| --- | ---: | ---: | --- |
| common | 0 | 13 | 4 個警告 |
| phone-app | 0 | 22 | 10 個錯誤、134 個警告 |
| glasses-app | 0 | 13 | 3 個錯誤、206 個警告 |

三個模組 lint gate 均通過。baseline 仍有既有問題，因此本次結果代表沒有新增未被 baseline 過濾的錯誤。各模組 `build/reports/lint-results-debug.xml`／`.html` 保留詳細結果。

## 最終 APK

| APK | 打包完成時間（+08:00） | 位元組 |
| --- | --- | ---: |
| `phone-app/build/outputs/apk/debug/phone-app-debug.apk` | 2026-09-30 15:05:18 | 84,311,872 |
| `glasses-app/build/outputs/apk/debug/glasses-app-debug.apk` | 2026-09-30 15:05:27 | 29,782,229 |

檔案 SHA-256：

```text
phone-app-debug.apk
EE267D12532D3EF6A0A5A2E7699D7FF244128F8C64FF74346AE739F09F2D8110

glasses-app-debug.apk
C7ED22F9DD39254E341FEE7ED34D34F0F311488C2D6213592BB7C66F5B41EE0C
```

兩個 APK 均經 `apksigner verify --verbose --print-certs` 驗證成功，使用 APK v2 簽署、單一 RSA 2048-bit 簽署者。兩者的憑證 SHA-256 皆符合 Windows `C:\Users\liangtinglin\.android\debug.keystore` 的 `androiddebugkey`：

```text
E5E4197221577137AB023428BA2A9B1D6CCC9DB4CCAA5CAACA6867398906DF5C
```

兩份 APK 包含最後修正的來源；最後一次手機／眼鏡 Kotlin 編譯發生在這些來源修改後。建置時尚未連接裝置；後續已於 2026-09-30 17:03 使用既有正式金鑰重新簽署的副本完成兩端更新安裝，詳見 [裝置安裝紀錄](DEVICE_INSTALLATION.md)。

## 環境還原

建置結束後確認 `local.properties` 的 SDK 路徑已回到 `C:\Users\liangtinglin\AppData\Local\Android\Sdk`，且此目錄存在。`build/local.properties.windows-backup` 已由還原流程清除。還原使用完整原始檔案位元組，release 簽署設定保留；本報告不包含其密碼。WSL 的 `debug.keystore.codex-backup` 已於先前還原原始 WSL keystore 後清除。

`git -c core.whitespace=cr-at-eol diff --check` 通過。

## 尚待實測／資訊

- 兩端 APK 已安裝；藍牙同步、可視範圍、按鍵／捲動與 Gemini Live 的功能實測仍待執行。ADB 顯示眼鏡型號為 RG_glasses；預覽為 16:9 示意，實際投影比例與可視範圍仍需上機確認。
- 未使用真實 TypeSafe／Laya key 與實際錄音驗證分類準確率及 GPT-Transcribe 繁中品質；單元測試驗證契約與備援，不能取代服務端／硬體測試。
- Laya HTTPS 可設定。區網 HTTP 仍等待實際私有主機位址，以便加入限於該主機的 Android 網路政策。自動審核拒絕全域開放明文流量，因為這會放寬所有目的地；本次沒有套用該全域設定。
- 各模組 lint 沿用既有 baseline，過濾數量見上表。
- 手機 lint 發現既有 Rokid `client-m:1.0.4` 的 `arm64-v8a/libcaps.so` 未做 16 KB 對齊；16 KB page size 裝置相容性仍須 SDK 更新或實機確認。本次未修改這個原生 SDK 二進位檔。

## 2026-10-01 最新完整驗證

本節取代上述 9 月 30 日 APK／待實測狀態。Gemini／OpenAI 決策、畫面尺寸回報、預覽及 SPP 相機能力修正均已編譯；Agents 依使用者指示延後。

執行任務：

```text
:common:testDebugUnitTest
:phone-app:testDebugUnitTest
:glasses-app:testDebugUnitTest
:phone-app:assembleDebug
:glasses-app:assembleDebug
:phone-app:assembleDebugAndroidTest
:phone-app:lintDebug
:glasses-app:lintDebug
```

最終 `BUILD SUCCESSFUL in 15m 37s`，191 個任務中 35 個執行、156 個已是最新。WSL 測試遇到 Windows classpath 目錄讀取延遲，僅在忽略的本機 init script 將 JVM 測試 classpath 目錄封裝為 Linux 本機 JAR；未變更 App 打包輸入。

| 模組 | 測試類 | 測試 | 失敗／錯誤／略過 |
| --- | ---: | ---: | --- |
| common | 8 | 75 | 0 / 0 / 0 |
| phone-app | 68 | 818 | 0 / 0 / 0 |
| glasses-app | 3 | 27 | 0 / 0 / 0 |
| 合計 | 79 | 920 | 0 / 0 / 0 |

測試報告保存於 `build/verification/full-test-run-20261001/<module>/TEST-*.xml`，摘要為同目錄 `summary.json`，建置 daemon 紀錄為 `gradle-daemon.log`。報告時間：common 10:50:03–10:50:08、phone-app 10:54:35–10:58:37、glasses-app 10:52:19–10:52:34（+08:00）。本次包括 `LlmDecisionClientTest` 4 項、`ImageFailureTest` 3 項、`GlassesDisplayMetricsTest` 2 項及既有路由／分頁測試。

| 最新 lint gate | 未被 baseline 過濾的錯誤 | 警告 | baseline 過濾 |
| --- | ---: | ---: | --- |
| phone-app | 0 | 22 | 9 個錯誤、134 個警告 |
| glasses-app | 0 | 13 | 3 個錯誤、206 個警告 |

兩端 lint 通過，沿用既有 baseline，未新增 baseline 項目。原生 SDK 的 16 KB 對齊限制仍存在。

| 編譯 APK | 完成時間（+08:00） | 位元組 |
| --- | --- | ---: |
| phone-app-debug.apk | 2026-10-01 10:59:21 | 82,672,312 |
| glasses-app-debug.apk | 2026-10-01 10:24:59 | 29,784,065 |

本輪眼鏡 APK 是已編譯的最新來源，因此最終任務為 up-to-date；手機最後資源更新已重新打包。两份編譯 APK 的 SHA-256：

```text
phone-app-debug.apk
030C8E095763D01536001F78F67587A7765CD911F7BB48875ACA77EEF094B3D5

glasses-app-debug.apk
7C73A654A6E057F647C3F18D77B3FEFEC47C591D2C8ECA0E9E658378240F7BAA
```

部署副本使用既有 release 憑證，簽章驗證成功；兩端已安裝且装置雜湊相符，詳見 [安裝紀錄](DEVICE_INSTALLATION.md)。更換金鑰後，Gemini／OpenAI 對話及分類 4 項 Android 真實 API 測試通過，SPP 拍照及 Gemini 分析成功；24 sp 預覽與套用同步驗證後還原既有設定。功能測試的限制見 [實機紀錄](DEVICE_SMOKE_TEST.md)。

建置結束後確認 Windows SDK 路徑已還原、實際目錄存在、暫存 SDK 備份已移除，WSL 無殘留 debug keystore 備份。`git -c core.whitespace=cr-at-eol diff --check` 通過。
