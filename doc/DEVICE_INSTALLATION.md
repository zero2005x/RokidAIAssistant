# 手機／眼鏡安裝紀錄

本文件保留先前安裝紀錄；目前裝置版本的 APK 雜湊見最下方 2026-10-01 更新。

2026-09-30（Asia/Taipei）。使用 15:05 完成編譯的手機／眼鏡 Debug APK。

## 安裝結果

| 裝置 | ADB 序號 | 套件 | 更新時間 | 結果 |
| --- | --- | --- | --- | --- |
| 手機 21091116UG | eeaas88ts4kn6l8t | com.example.rokidphone | 17:02:56 | Success |
| 眼鏡 RG_glasses | 1901092544022855 | com.example.rokidglasses | 17:03:08 | Success |

兩端版本均為 `1.1.0`、`versionCode=5`。採用 `adb -s <serial> install -r <apk>` 更新，保留 App 資料與設定。已用 `dumpsys package` 核對更新時間，並用裝置端 `sha256sum` 核對安裝的 base.apk 與部署 APK 完全一致。

## 簽章與部署包

第一次安裝發現既有 App 使用正式簽章，與編譯輸出的 Windows Debug 簽章不同；手機 ADB 通道也曾失去回應，透過針對手機的 reconnect 恢復。核對兩端既有 APK 後，確認專案 `local.properties` 設定的正式金鑰與裝置憑證相同，遂用該金鑰重新簽署已編譯 APK 的副本。

APK 編譯模式仍是 Debug；重新簽署後的程式碼、資源與 AndroidManifest 均相同，ZIP 差異只在 `META-INF/M365KEY.RSA`、`META-INF/M365KEY.SF`、`META-INF/MANIFEST.MF` 簽章項目。兩份部署包皆經 `apksigner verify` 驗證成功。

正式憑證 SHA-256：

```text
7CA3A3F7BAC7483C0D16BB9E1EBDB657F094CE779483DDBC7FE364324601E30B
```

部署檔案：

- [手機更新包](../build/device-install-check/phone-app-device.apk)
- [眼鏡更新包](../build/device-install-check/glasses-app-device.apk)

裝置與部署檔案 SHA-256：

```text
phone-app-device.apk
B5AB475C5136AE3770B80BD9BB2950E1FE4B890C447B58DD25317E005C67569A

glasses-app-device.apk
A8D54D60A95EA9656158F8F369AB47FCD8B34DA647C7AA411EBDF57890840CD7
```

本次完成安裝與檔案核對；功能／藍牙／真實 API 測試仍需另外執行。建置及單元測試紀錄見 [修正驗證報告](REVIEW_FIX_VERIFICATION.md)。

## 2026-10-01 更新

兩端仍為 `1.1.0 / versionCode 5`，以 `install -r` 更新並保留資料。手機最後更新包含新增 Gemini／OpenAI 決策、實際 APP 比例預覽、SPP 能力辨識與照片錯誤處理，以及最後的「自動選模」標題修正。眼鏡更新包含尺寸與 SPP 相機能力回報。

| 裝置 | 套件 | 裝置確認的更新時間（+08:00） | 部署 APK 位元組 |
| --- | --- | --- | ---: |
| 手機 eeaas88ts4kn6l8t | com.example.rokidphone | 2026-10-01 11:00:49 | 82,718,213 |
| 眼鏡 1901092544022855 | com.example.rokidglasses | 2026-10-01 10:30:06 | 29,798,314 |

裝置 `base.apk` SHA-256 與下列部署檔案逐一相符：

```text
phone-app-device.apk
3069BC57FAF48D733E36959451AC06FB68D453AFEC8483C5A43BFC9E7CF17ADC

glasses-app-device.apk
0E590F2D95CB1314EDF2A90DCB3E8463815C03AAFC3575BC412900E333F511D6
```

兩份部署包的 `apksigner verify --verbose --print-certs` 均成功，使用 v3、單一 RSA 2048 位元簽署者；憑證仍為上方既有 release 憑證。與各自編譯 APK 的 ZIP 差異僅為三個 META-INF 簽章項目，程式碼與資源逐項相同。安裝與雜湊核對證據存 `build/device-install-check/oct01-installed-proof.json`。

最後啟動成功，手機與眼鏡均顯示已連線；字級與顯示範圍維持原值。真實金鑰、照片及顯示測試結果見 [實機測試](DEVICE_SMOKE_TEST.md)。
