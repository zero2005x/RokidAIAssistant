# Rokid AI Assistant 架構文檔

## 概述

Rokid AI Assistant 是一個智能眼鏡助手應用，採用「手機中繼」架構，通過藍牙將眼鏡與手機連接，由手機負責 AI 處理。

## 架構圖

```
┌─────────────────┐      Bluetooth SPP      ┌─────────────────┐      WiFi      ┌─────────────────┐
│   Rokid 眼鏡    │ ◄──────────────────────► │     手機 App    │ ◄────────────► │   Gemini API    │
│  (glasses-app)  │     語音/指令/回應       │   (phone-app)   │   HTTP/REST   │  (Google Cloud) │
└─────────────────┘                          └─────────────────┘                └─────────────────┘
```

## 模組說明

### 1. glasses-app (眼鏡端)

- **主要功能**: 語音錄製、喚醒詞偵測、顯示 AI 回應
- **BluetoothSppClient**: 藍牙 SPP 客戶端，連接到手機
- **GlassesViewModel**: 管理 UI 狀態和語音錄製
- **WakeWordService**: 喚醒詞偵測服務

### 2. phone-app (手機端)

- **主要功能**: 藍牙伺服器、語音識別、AI 對話、設定管理
- **BluetoothSppManager**: 藍牙 SPP 伺服器，接收眼鏡連接
- **GeminiSpeechService**: 調用 Gemini API 進行語音識別和 AI 對話
- **PhoneAIService**: 前台服務，管理藍牙和 AI 處理
- **SettingsRepository**: 管理 API 設定

### 3. common (共用模組)

- **Message**: 藍牙通訊訊息格式 (JSON + Base64 編碼)
- **MessageType**: 訊息類型定義
- **ConnectionState**: 連接狀態枚舉

## 通訊協議

### 藍牙 SPP 協議

- **UUID**: `a1b2c3d4-e5f6-7890-abcd-ef1234567890`
- **格式**: JSON + 換行符分隔
- **音訊編碼**: Base64 (用於 binaryData 欄位)

### 訊息類型

| 類型             | 方向      | 說明                   |
| ---------------- | --------- | ---------------------- |
| VOICE_START      | 眼鏡→手機 | 開始錄音               |
| VOICE_END        | 眼鏡→手機 | 結束錄音，包含音訊數據 |
| AI_PROCESSING    | 手機→眼鏡 | 正在處理中             |
| USER_TRANSCRIPT  | 手機→眼鏡 | 語音轉文字結果         |
| AI_RESPONSE_TEXT | 手機→眼鏡 | AI 文字回應            |
| AI_ERROR         | 手機→眼鏡 | 處理錯誤               |

## 音訊格式

- **取樣率**: 16000 Hz
- **通道**: 單聲道 (Mono)
- **位元深度**: 16-bit
- **格式**: PCM → WAV (發送給 API 前轉換)

## 支援的 AI 服務

### 語音識別 (Speech-to-Text)

1. **Google Gemini** - 使用 multimodal 模型處理音訊
2. **OpenAI Whisper** - (規劃中)
3. **Google Cloud STT** - (規劃中)

### AI 對話

1. **Google Gemini** - gemini-2.5-pro, gemini-2.5-flash, gemini-2.5-flash-lite
2. **OpenAI GPT** - gpt-5.2, gpt-5, gpt-4o, gpt-4o-mini, o3-pro, o4-mini
3. **Anthropic Claude** - claude-opus-4.5, claude-sonnet-4.5, claude-haiku-4.5
4. **DeepSeek** - deepseek-chat, deepseek-reasoner, deepseek-vl-3
5. **Groq** - llama-4-70b, llama-3.3-70b, qwen-3-32b, llama-4-vision-90b
6. **xAI** - grok-4, grok-4-fast, grok-3, grok-2-vision-1212
7. **Alibaba Qwen** - qwen3-max, qwen3-plus, qwen3-turbo, qwen3-vl-max
8. **Zhipu AI** - glm-4.7, glm-4-plus, glm-4-flash, glm-4v-plus
9. **Baidu Ernie** - ernie-5.0, ernie-x1, ernie-4.5-turbo, ernie-vision-pro
10. **Perplexity** - sonar-pro, sonar, sonar-reasoning-pro, sonar-reasoning
11. **Custom** - Ollama, LM Studio 等 OpenAI 相容 API

## 設定項目 (phone-app)

手機端提供完整的 API 設定介面：

### 支援的 AI 提供商

| 提供商        | 支援狀態    | 模型                                                    |
| ------------- | ----------- | ------------------------------------------------------- |
| Google Gemini | ✅ 完整支援 | gemini-2.5-pro, gemini-2.5-flash, gemini-2.5-flash-lite |
| OpenAI        | ✅ 完整支援 | gpt-5.2, gpt-5, gpt-4o, gpt-4o-mini, o3-pro, o4-mini    |
| Anthropic     | ✅ 完整支援 | claude-opus-4.5, claude-sonnet-4.5, claude-haiku-4.5    |
| DeepSeek      | ✅ 完整支援 | deepseek-chat, deepseek-reasoner, deepseek-vl-3         |
| Groq          | ✅ 完整支援 | llama-4-70b, llama-3.3-70b, qwen-3-32b                  |
| xAI           | ✅ 完整支援 | grok-4, grok-4-fast, grok-3, grok-2-vision-1212         |
| Alibaba       | ✅ 完整支援 | qwen3-max, qwen3-plus, qwen3-turbo, qwen3-vl-max        |
| Zhipu AI      | ✅ 完整支援 | glm-4.7, glm-4-plus, glm-4-flash, glm-4v-plus           |
| Baidu         | ✅ 完整支援 | ernie-5.0, ernie-x1, ernie-4.5-turbo                    |
| Perplexity    | ✅ 完整支援 | sonar-pro, sonar, sonar-reasoning-pro                   |
| Custom        | ✅ 完整支援 | Ollama, LM Studio 等 OpenAI 相容 API                    |

### 可配置項目

- **AI 提供商** - 選擇 Gemini/OpenAI/Anthropic
- **AI 模型** - 選擇對應提供商的模型
- **API Keys** - 各提供商的 API Key (加密存儲)
- **語音識別服務** - Gemini 音訊識別/Whisper/Google Cloud STT
- **系統提示詞** - 自定義 AI 行為

### 設定存儲

- 使用 `EncryptedSharedPreferences` 安全存儲 API Key
- 設定變更即時生效，無需重啟服務

### 相關文件

- `phone-app/src/main/java/com/example/rokidphone/data/ApiSettings.kt` - 設定數據類
- `phone-app/src/main/java/com/example/rokidphone/data/SettingsRepository.kt` - 設定存儲
- `phone-app/src/main/java/com/example/rokidphone/ui/SettingsScreen.kt` - 設定 UI

## 開發環境

- **Android Gradle Plugin**: 9.0.0
- **Kotlin**: 2.2.10
- **Gradle**: 9.1.0
- **Min SDK**: 26
- **Target SDK**: 34

## 版本歷史

### v1.0.0 (2026-01-17)

- 基礎架構完成
- 藍牙 SPP 通訊
- Gemini API 整合
- 眼鏡端語音錄製
- 手機端 AI 處理
