# API 設定指南

[English](API_SETTINGS_EN.md) | [简体中文](API_SETTINGS_CN.md) | **繁體中文**

本文件說明如何在 Rokid AI 助手中配置各個 AI 供應商的 API 設定。

## 目錄

- [概述](#概述)
- [支援的供應商](#支援的供應商)
- [API 金鑰取得方式](#api-金鑰取得方式)
- [設定方式](#設定方式)
- [常見問題](#常見問題)

## 概述

Rokid AI 助手支援多個 AI 供應商，您可以根據需求選擇不同的供應商和模型。所有 API 金鑰都使用 Android 的 `EncryptedSharedPreferences` 安全儲存。

## 支援的供應商

### 1. Google Gemini ⭐ 推薦

**特點**：

- 支援語音識別（直接處理音訊）
- 支援視覺理解
- 多語言支援優秀

**可用模型**：
| 模型 | 說明 |
|------|------|
| gemini-2.5-pro | 最強大模型，原生多模態推理 |
| gemini-2.5-flash | 速度與效率優化，支援高吞吐量 |
| gemini-2.5-flash-lite | 極致經濟輕量版 |

**取得 API 金鑰**：

1. 前往 [Google AI Studio](https://ai.google.dev/)
2. 登入 Google 帳號
3. 點擊「Get API Key」
4. 建立新的 API 金鑰

---

### 2. OpenAI

**特點**：

- 業界標準
- Whisper 語音識別
- GPT-4o 視覺理解

**可用模型**：
| 模型 | 說明 |
|------|------|
| gpt-5.2 | 最新旗艦模型，無與倫比的通用智能 |
| gpt-5 | 多模態旗艦模型，頂尖視覺分析 |
| gpt-4o | 高效能多模態模型 |
| gpt-4o-mini | 經濟實惠版 |
| o3-pro | 進階推理模型 |
| o4-mini | 輕量推理模型 |

**取得 API 金鑰**：

1. 前往 [OpenAI Platform](https://platform.openai.com/)
2. 登入或註冊帳號
3. 進入 API Keys 頁面
4. 點擊「Create new secret key」

---

### 3. Anthropic Claude

**特點**：

- 強大的推理能力
- 長上下文支援
- 安全性設計

**可用模型**：
| 模型 | 說明 |
|------|------|
| claude-opus-4.5 | 最強推理能力 |
| claude-sonnet-4.5 | 均衡性能 |
| claude-haiku-4.5 | 快速響應 |

**取得 API 金鑰**：

1. 前往 [Anthropic Console](https://console.anthropic.com/)
2. 註冊帳號
3. 建立 API 金鑰

---

### 4. DeepSeek

**特點**：

- 性價比高
- 中文支援優秀
- OpenAI 相容 API
- 支援視覺理解

**可用模型**：
| 模型 | 說明 |
|------|------|
| deepseek-chat | 通用對話 (V3.2) |
| deepseek-reasoner | 進階推理 (R2) |
| deepseek-vl-3 | 視覺理解模型 |

**取得 API 金鑰**：

1. 前往 [DeepSeek Platform](https://platform.deepseek.com/)
2. 註冊帳號
3. 取得 API 金鑰

---

### 5. Groq

**特點**：

- 超快推理速度
- 硬體加速
- 支援 Whisper 語音識別

**可用模型**：
| 模型 | 說明 |
|------|------|
| llama-4-70b | Llama 4 通用大模型 |
| llama-3.3-70b-versatile | 多功能大模型 |
| qwen-3-32b | Qwen 3 快速模型 |
| llama-4-vision-90b | 視覺理解模型 |
| llama-3.2-11b-vision-preview | 輕量視覺模型 |

**取得 API 金鑰**：

1. 前往 [Groq Console](https://console.groq.com/)
2. 註冊帳號
3. 建立 API 金鑰

---

### 6. xAI (Grok)

**特點**：

- Elon Musk 的 xAI
- 即時資訊更新
- 視覺理解

**可用模型**：
| 模型 | 說明 |
|------|------|
| grok-4 | 最新旗艦版本 |
| grok-4-fast | 快速版本 |
| grok-3 | 穩定版本 |
| grok-2-vision-1212 | 支援視覺 |

**取得 API 金鑰**：

1. 前往 [xAI API](https://x.ai/)
2. 申請 API 存取權限

---

### 7. 阿里雲通義千問 (Qwen)

**特點**：

- 強大的中文能力
- 視覺理解
- 長上下文

**可用模型**：
| 模型 | 說明 |
|------|------|
| qwen3-max | 最強版 |
| qwen3-plus | 均衡版 |
| qwen3-turbo | 快速版 |
| qwen3-vl-max | 視覺最強版 |
| qwen3-vl-plus | 視覺均衡版 |

**取得 API 金鑰**：

1. 前往[阿里雲 DashScope](https://dashscope.aliyun.com/)
2. 註冊阿里雲帳號
3. 開通 DashScope 服務
4. 取得 API Key

---

### 8. 智譜 AI (ChatGLM)

**特點**：

- 國產大模型
- 中文能力強
- 視覺理解

**可用模型**：
| 模型 | 說明 |
|------|------|
| glm-4.7 | 最新版 |
| glm-4-plus | 進階版 |
| glm-4-flash | 快速版 |
| glm-4v-plus | 視覺進階版 |
| glm-4v-flash | 視覺快速版 |

**取得 API 金鑰**：

1. 前往[智譜 AI 開放平台](https://open.bigmodel.cn/)
2. 註冊帳號
3. 取得 API Key

---

### 9. 百度文心一言 (Ernie)

**特點**：

- 需要 API Key + Secret Key
- 中文能力強
- 支援視覺理解

**可用模型**：
| 模型 | 說明 |
|------|------|
| ernie-5.0 | 最新版 |
| ernie-x1 | 進階推理 |
| ernie-4.5-turbo | 快速版 |
| ernie-vision-pro | 視覺理解 |

**取得 API 金鑰**：

1. 前往[百度智能雲](https://cloud.baidu.com/)
2. 註冊帳號
3. 開通文心一言服務
4. 取得 API Key 和 Secret Key

⚠️ **注意**：百度需要同時填寫 API Key 和 Secret Key

---

### 10. Perplexity

**特點**：

- 即時網路搜尋能力
- 結合搜尋與推理
- OpenAI 相容 API

**可用模型**：
| 模型 | 說明 |
|------|------|
| sonar-pro | 進階搜索模型 |
| sonar | 標準搜索模型 |
| sonar-reasoning-pro | 進階推理搜索 |
| sonar-reasoning | 標準推理搜索 |
| r1-1776 | 離線推理模型 |

**取得 API 金鑰**：

1. 前往 [Perplexity API](https://www.perplexity.ai/settings/api)
2. 登入帳號
3. 取得 API Key

---

### 11. 自訂 / 自託管

**特點**：

- 支援 OpenAI 相容 API
- 可連接本地模型
- 支援 Ollama、LM Studio 等

**設定方式**：

1. 填入 Base URL（例如：`http://localhost:11434/v1/`）
2. 填入模型名稱（例如：`llama3.2`）
3. API Key 選填

## 設定方式

### 方法一：透過應用程式設定

1. 開啟手機應用程式
2. 點擊右上角齒輪圖示進入設定
3. 選擇「AI 供應商」
4. 輸入對應的 API 金鑰
5. 選擇模型

### 方法二：透過 local.properties（開發用）

在專案根目錄的 `local.properties` 中設定：

```properties
GEMINI_API_KEY=your_gemini_key_here
OPENAI_API_KEY=your_openai_key_here
```

這些金鑰會作為 BuildConfig 常數編譯進應用程式，當使用者沒有設定時作為預設值。

## 常見問題

### Q: API 金鑰儲存在哪裡？

A: API 金鑰使用 Android 的 `EncryptedSharedPreferences` 加密儲存在應用程式私有目錄中。

### Q: 如何切換供應商？

A: 在設定頁面選擇新的供應商，並確保已填入該供應商的 API 金鑰。

### Q: 為什麼語音識別只支援部分供應商？

A: 只有 Gemini、OpenAI（Whisper）和 Groq（Whisper）原生支援語音識別。其他供應商需要另外配置語音識別服務。

### Q: 自訂供應商如何設定？

A:

1. 選擇「自訂 / 自託管」
2. 填入 Base URL（OpenAI 相容格式）
3. 填入模型名稱
4. 如有需要，填入 API Key

### Q: 出現「API Key 未設定」錯誤怎麼辦？

A:

1. 確認已在設定中輸入正確的 API 金鑰
2. 確認 API 金鑰沒有過期或被撤銷
3. 嘗試重新輸入 API 金鑰
