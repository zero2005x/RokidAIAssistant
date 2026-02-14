# Rokid AI Assistant

> 📖 [English Version](../../README.md)

**Rokid AR 眼鏡的 AI 語音與視覺助手。**

---

## 🚀 快速開始（5 分鐘）

```bash
# 1. 複製專案
git clone https://github.com/your-repo/RokidAIAssistant.git && cd RokidAIAssistant

# 2. 設定 API 金鑰
cp local.properties.template local.properties
# 編輯 local.properties → 新增 GEMINI_API_KEY（必要）

# 3. 建置與安裝
./gradlew :phone-app:installDebug    # 安裝手機應用
./gradlew :glasses-app:installDebug  # 安裝眼鏡應用（在 Rokid 裝置上）
```

> **最低需求**：只需要 `GEMINI_API_KEY` 即可執行。前往 [Google AI Studio](https://ai.google.dev/) 取得。

---

## 範圍

### 涵蓋範圍

- Rokid AR 眼鏡上的語音轉文字與 AI 聊天
- 眼鏡相機拍照並進行 AI 圖像分析
- 手機 ↔ 眼鏡透過 Rokid CXR SDK 通訊
- 多 AI/STT 服務商支援（Gemini、OpenAI、Anthropic 等）
- 對話記錄持久化

### 不涵蓋範圍

- 眼鏡獨立運作（需要手機進行 AI 處理）
- 離線 AI 推論
- 影片串流或即時 AR 疊加

---

## 功能特色

| 功能             | 說明                                                                                                                                                                                                                                      |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 🎤 語音互動      | 透過眼鏡或手機與 AI 對話                                                                                                                                                                                                                  |
| 📷 照片分析      | 使用眼鏡相機拍攝圖片，取得 AI 分析                                                                                                                                                                                                        |
| 🎙️ 錄音與分析    | 透過手機或眼鏡錄音，自動進行 AI 語音辨識與分析                                                                                                                                                                                            |
| 🤖 多 AI 服務商  | 13 個服務商：Gemini、OpenAI、Anthropic、DeepSeek、Groq、xAI、Alibaba (Qwen)、Zhipu (GLM)、Baidu、Perplexity、Moonshot (Kimi)、Gemini Live、Custom（OpenAI 相容端點）                                                                      |
| 🎧 多 STT 服務商 | 18 個服務商：Gemini、OpenAI Whisper、Groq Whisper、Deepgram、AssemblyAI、Azure Speech、iFLYTEK、Google Cloud STT、AWS Transcribe、Alibaba ASR、Tencent ASR、Baidu ASR、IBM Watson、Huawei SIS、Volcengine、Rev.ai、Speechmatics、Otter.ai |
| 📱 手機-眼鏡通訊 | 透過 Rokid CXR SDK 和藍牙 SPP                                                                                                                                                                                                             |
| 💬 對話記錄      | Room 資料庫持久儲存                                                                                                                                                                                                                       |
| 🌍 多語言支援    | 13 種語言：English、简体中文、繁體中文、日本語、한국어、Español、Français、Italiano、Русский、Українська、العربية、Tiếng Việt、ไทย                                                                                                        |

---

## 模組 / 目錄導覽

```
RokidAIAssistant/
├── phone-app/                    # 📱 手機應用（主要 AI 中心）
│   └── src/main/java/.../rokidphone/
│       ├── MainActivity.kt       # 進入點
│       ├── service/ai/           # AI 服務商實作
│       ├── service/stt/          # STT 服務商實作
│       ├── service/cxr/          # CXR SDK 管理器
│       ├── data/db/              # Room 資料庫
│       ├── ui/                   # Compose UI 畫面
│       └── viewmodel/            # ViewModels
│
├── glasses-app/                  # 👓 眼鏡應用（顯示/輸入）
│   └── src/main/java/.../rokidglasses/
│       ├── MainActivity.kt       # 進入點
│       ├── service/photo/        # 相機服務
│       ├── ui/                   # Compose UI
│       └── viewmodel/            # GlassesViewModel
│
├── common/                       # 📦 共用協定函式庫
│   └── src/main/java/.../rokidcommon/
│       ├── Constants.kt          # 共用常數
│       └── protocol/             # Message, MessageType, ConnectionState
│
├── app/                          # 🧪 原始整合應用（僅開發用）
├── doc/                          # 📚 文件
└── gradle/libs.versions.toml     # 版本目錄
```

| 模組          | App ID                     | 用途                          |
| ------------- | -------------------------- | ----------------------------- |
| `phone-app`   | `com.example.rokidphone`   | AI 處理、STT、CXR SDK、資料庫 |
| `glasses-app` | `com.example.rokidglasses` | 顯示、相機、喚醒詞            |
| `common`      | （函式庫）                 | 共用協定與常數                |

---

## 技術棧

| 類別      | 技術                         | 版本           |
| --------- | ---------------------------- | -------------- |
| 程式語言  | Kotlin                       | 2.2.10         |
| 最低 SDK  | Android                      | 28 (9.0 Pie)   |
| 目標 SDK  | Android                      | 34 (14)        |
| 編譯 SDK  | Android                      | 36             |
| 建置工具  | Gradle + Kotlin DSL          | 9.0            |
| UI        | Jetpack Compose + Material 3 | BOM 2026.01.00 |
| 非同步    | Kotlin Coroutines            | 1.10.2         |
| 資料庫    | Room                         | 2.8.4          |
| 網路      | Retrofit + OkHttp            | 3.0 / 5.3      |
| Rokid SDK | CXR client-m                 | 1.0.4          |

---

## 建置與執行

### 前置需求

- **Android Studio**: Ladybug (2024.2) 或更新版本
- **JDK**: 21（建議，與 AGP 9 / CI 一致）
- **Android SDK**: 已安裝 API 36

### 環境設定

```bash
# 複製範本並編輯您的金鑰
cp local.properties.template local.properties
```

**`local.properties` 中的必要金鑰：**

```properties
# 必要
GEMINI_API_KEY=your_gemini_api_key

# 眼鏡連接必要
ROKID_CLIENT_SECRET=your_rokid_secret_without_hyphens

# 選用
OPENAI_API_KEY=your_openai_key
ANTHROPIC_API_KEY=your_anthropic_key
```

### Gradle 指令

```bash
# 建置所有模組（debug）
./gradlew assembleDebug

# 建置特定模組
./gradlew :phone-app:assembleDebug
./gradlew :glasses-app:assembleDebug

# 安裝到連接的裝置
./gradlew :phone-app:installDebug
./gradlew :glasses-app:installDebug

# 建置 release APK
./gradlew assembleRelease

# 清除建置
./gradlew clean
```

### APK 輸出位置

```
phone-app/build/outputs/apk/debug/phone-app-debug.apk
phone-app/build/outputs/apk/release/phone-app-release.apk
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
glasses-app/build/outputs/apk/release/glasses-app-release.apk
```

---

## Debug vs Release

| 方面        | Debug          | Release                  |
| ----------- | -------------- | ------------------------ |
| 壓縮        | ❌ 停用        | ✅ 啟用（ProGuard）      |
| 可除錯      | ✅ 是          | ❌ 否                    |
| 簽署        | Debug keystore | Release keystore（必要） |
| BuildConfig | API 金鑰可見   | API 金鑰可見（已混淆）   |
| 效能        | 較慢           | 已最佳化                 |

---

## 測試

目前已實作單元測試與整合測試，涵蓋 protocol、service、factory、data layer。

### 測試指令

```bash
# 跨模組單元測試
./gradlew :common:testDebugUnitTest :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest

# 目標測試群組
./gradlew :common:testDebugUnitTest --tests "com.example.rokidcommon.protocol.*"
./gradlew :phone-app:testDebugUnitTest --tests "com.example.rokidphone.service.ai.*"
./gradlew :phone-app:testDebugUnitTest --tests "com.example.rokidphone.service.stt.*"

# phone-app 儀器測試（Room / data layer）
./gradlew :phone-app:connectedDebugAndroidTest
```

### 手動測試檢查清單

1. **手機應用**
   - [ ] 啟動應用，確認設定畫面載入
   - [ ] 設定 AI 服務商（Gemini），測試文字聊天
   - [ ] 測試手機麥克風語音輸入
   - [ ] 確認對話記錄在重啟後持續

2. **眼鏡應用**
   - [ ] 安裝到 Rokid 眼鏡，確認 UI 顯示
   - [ ] 測試相機拍照
   - [ ] 確認照片傳輸到手機

3. **整合測試**
   - [ ] 透過 CXR SDK 配對手機與眼鏡
   - [ ] 測試從眼鏡發送語音指令 → AI 回應顯示
   - [ ] 測試拍照 → AI 分析 → 結果顯示

---

## 常見開發任務

### 新增 AI 服務商

1. 在 `phone-app/src/.../service/ai/YourProvider.kt` 建立實作
2. 實作 `AiServiceProvider` 介面（參見 [ARCHITECTURE.md](ARCHITECTURE.md#ai-服務提供者介面)）
3. 在 `AiServiceFactory.kt` 中註冊
4. 加入設定中的 `AiProvider` 列舉

### 新增畫面（Compose）

1. 在 `phone-app/src/.../ui/yourscreen/YourScreen.kt` 建立畫面 composable
2. 在 `phone-app/src/.../viewmodel/YourViewModel.kt` 建立 ViewModel
3. 在 `phone-app/src/.../ui/navigation/AppNavigation.kt` 加入路由

### 新增權限

1. 新增到 `AndroidManifest.xml`：
   ```xml
   <uses-permission android:name="android.permission.YOUR_PERMISSION" />
   ```
2. 在 Activity/ViewModel 中執行時請求（針對危險權限）

---

## FAQ 與疑難排解

### 建置問題

**Q: 建置失敗顯示「API key not found」**

```
A: 確認 local.properties 存在且包含 GEMINI_API_KEY。
   檢查檔案是否在專案根目錄，而非模組資料夾中。
```

**Q: Gradle 同步失敗顯示版本錯誤**

```
A: 確認 Android Studio 已安裝 SDK 36。
   File → Settings → SDK Manager → 安裝 API 36。
```

**Q: JDK 版本不符**

```
A: 專案建議使用 JDK 21。
   File → Settings → Build → Gradle → Gradle JDK → 選擇 JDK 21。
```

### 執行時問題

**Q: 應用啟動時當機**

```
A: 檢查 Logcat 是否有遺失 API 金鑰錯誤。
   確認所有必要權限已授予。
```

**Q: 無法連接眼鏡**

```
A: 1. 確認 ROKID_CLIENT_SECRET 已設定（不含連字號）
   2. 兩個裝置都啟用藍牙
   3. 確認眼鏡處於配對模式
```

**Q: AI 回應為空**

```
A: 1. 確認 API 金鑰有效且有配額
   2. 檢查網路連線
   3. 查看 Logcat 中的 API 錯誤回應
```

### Release 問題

**Q: Release 建置失敗顯示簽署錯誤**

```
A: 建立 release keystore 並在 build.gradle.kts 中設定：
   signingConfigs {
       create("release") {
           storeFile = file("path/to/keystore.jks")
           storePassword = "password"
           keyAlias = "alias"
           keyPassword = "password"
       }
   }
```

**Q: ProGuard 移除了必要的類別**

```
A: 在 proguard-rules.pro 加入 keep 規則：
   -keep class com.your.package.** { *; }
```

---

## 文件

| 文件                            | 說明                       |
| ------------------------------- | -------------------------- |
| [API 設定指南](API_SETTINGS.md) | 所有服務商的完整 API 設定  |
| [架構概覽](ARCHITECTURE.md)     | 系統設計、資料流、元件詳情 |

---

## 授權

本專案為專有軟體。
