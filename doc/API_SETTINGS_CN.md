# API 设置指南

[English](API_SETTINGS_EN.md) | **简体中文** | [繁體中文](API_SETTINGS_TW.md)

本文档说明如何在 Rokid AI 助手中配置各个 AI 供应商的 API 设置。

## 目录

- [概述](#概述)
- [支持的供应商](#支持的供应商)
- [API 密钥获取方式](#api-密钥获取方式)
- [设置方式](#设置方式)
- [常见问题](#常见问题)

## 概述

Rokid AI 助手支持多个 AI 供应商，您可以根据需求选择不同的供应商和模型。所有 API 密钥都使用 Android 的 `EncryptedSharedPreferences` 安全存储。

## 支持的供应商

### 1. Google Gemini ⭐ 推荐

**特点**：

- 支持语音识别（直接处理音频）
- 支持视觉理解
- 多语言支持优秀

**可用模型**：
| 模型 | 说明 |
|------|------|
| gemini-2.5-pro | 最强大模型，原生多模态推理 |
| gemini-2.5-flash | 速度与效率优化 |
| gemini-2.5-flash-lite | 极致经济轻量版 |

**获取 API 密钥**：

1. 前往 [Google AI Studio](https://ai.google.dev/)
2. 登录 Google 账号
3. 点击「Get API Key」
4. 创建新的 API 密钥

---

### 2. OpenAI

**特点**：

- 业界标准
- Whisper 语音识别
- GPT-4o 视觉理解

**可用模型**：
| 模型 | 说明 |
|------|------|
| gpt-5.2 | 最新旗舰模型，无与伦比的通用智能 |
| gpt-5 | 多模态旗舰模型，顶尖视觉分析 |
| gpt-4o | 高效能多模态模型 |
| gpt-4o-mini | 经济实惠版 |
| o3-pro | 高级推理模型 |
| o4-mini | 轻量推理模型 |

**获取 API 密钥**：

1. 前往 [OpenAI Platform](https://platform.openai.com/)
2. 登录或注册账号
3. 进入 API Keys 页面
4. 点击「Create new secret key」

---

### 3. Anthropic Claude

**特点**：

- 强大的推理能力
- 长上下文支持
- 安全性设计

**可用模型**：
| 模型 | 说明 |
|------|------|
| claude-opus-4.5 | 最强推理能力 |
| claude-sonnet-4.5 | 均衡性能 |
| claude-haiku-4.5 | 快速响应 |

**获取 API 密钥**：

1. 前往 [Anthropic Console](https://console.anthropic.com/)
2. 注册账号
3. 创建 API 密钥

---

### 4. DeepSeek

**特点**：

- 性价比高
- 中文支持优秀
- OpenAI 兼容 API
- 支持视觉理解

**可用模型**：
| 模型 | 说明 |
|------|------|
| deepseek-chat | 通用对话 (V3.2) |
| deepseek-reasoner | 高级推理 (R2) |
| deepseek-vl-3 | 视觉理解模型 |

**获取 API 密钥**：

1. 前往 [DeepSeek Platform](https://platform.deepseek.com/)
2. 注册账号
3. 获取 API 密钥

---

### 5. Groq

**特点**：

- 超快推理速度
- 硬件加速
- 支持 Whisper 语音识别

**可用模型**：
| 模型 | 说明 |
|------|------|
| llama-4-70b | Llama 4 通用大模型 |
| llama-3.3-70b-versatile | 多功能大模型 |
| qwen-3-32b | Qwen 3 快速模型 |
| llama-4-vision-90b | 视觉理解模型 |
| llama-3.2-11b-vision-preview | 轻量视觉模型 |

**获取 API 密钥**：

1. 前往 [Groq Console](https://console.groq.com/)
2. 注册账号
3. 创建 API 密钥

---

### 6. xAI (Grok)

**特点**：

- Elon Musk 的 xAI
- 实时信息更新
- 视觉理解

**可用模型**：
| 模型 | 说明 |
|------|------|
| grok-4 | 最新旗舰版本 |
| grok-4-fast | 快速版本 |
| grok-3 | 稳定版本 |
| grok-2-vision-1212 | 支持视觉 |

**获取 API 密钥**：

1. 前往 [xAI API](https://x.ai/)
2. 申请 API 访问权限

---

### 7. 阿里云通义千问 (Qwen)

**特点**：

- 强大的中文能力
- 视觉理解
- 长上下文

**可用模型**：
| 模型 | 说明 |
|------|------|
| qwen3-max | 最强版 |
| qwen3-plus | 均衡版 |
| qwen3-turbo | 快速版 |
| qwen3-vl-max | 视觉最强版 |
| qwen3-vl-plus | 视觉均衡版 |

**获取 API 密钥**：

1. 前往[阿里云 DashScope](https://dashscope.aliyun.com/)
2. 注册阿里云账号
3. 开通 DashScope 服务
4. 获取 API Key

---

### 8. 智谱 AI (ChatGLM)

**特点**：

- 国产大模型
- 中文能力强
- 视觉理解

**可用模型**：
| 模型 | 说明 |
|------|------|
| glm-4.7 | 最新版 |
| glm-4-plus | 高级版 |
| glm-4-flash | 快速版 |
| glm-4v-plus | 视觉高级版 |
| glm-4v-flash | 视觉快速版 |

**获取 API 密钥**：

1. 前往[智谱 AI 开放平台](https://open.bigmodel.cn/)
2. 注册账号
3. 获取 API Key

---

### 9. 百度文心一言 (Ernie)

**特点**：

- 需要 API Key + Secret Key
- 中文能力强
- 支持视觉理解

**可用模型**：
| 模型 | 说明 |
|------|------|
| ernie-5.0 | 最新版 |
| ernie-x1 | 高级推理 |
| ernie-4.5-turbo | 快速版 |
| ernie-vision-pro | 视觉理解 |

**获取 API 密钥**：

1. 前往[百度智能云](https://cloud.baidu.com/)
2. 注册账号
3. 开通文心一言服务
4. 获取 API Key 和 Secret Key

⚠️ **注意**：百度需要同时填写 API Key 和 Secret Key

---

### 10. Perplexity

**特点**：

- 实时网络搜索能力
- 结合搜索与推理
- OpenAI 兼容 API

**可用模型**：
| 模型 | 说明 |
|------|------|
| sonar-pro | 高级搜索模型 |
| sonar | 标准搜索模型 |
| sonar-reasoning-pro | 高级推理搜索 |
| sonar-reasoning | 标准推理搜索 |
| r1-1776 | 离线推理模型 |

**获取 API 密钥**：

1. 前往 [Perplexity API](https://www.perplexity.ai/settings/api)
2. 登录账号
3. 获取 API Key

---

### 11. 自定义 / 自托管

**特点**：

- 支持 OpenAI 兼容 API
- 可连接本地模型
- 支持 Ollama、LM Studio 等

**设置方式**：

1. 填入 Base URL（例如：`http://localhost:11434/v1/`）
2. 填入模型名称（例如：`llama3.2`）
3. API Key 选填

## 设置方式

### 方法一：通过应用程序设置

1. 打开手机应用程序
2. 点击右上角齿轮图标进入设置
3. 选择「AI 供应商」
4. 输入对应的 API 密钥
5. 选择模型

### 方法二：通过 local.properties（开发用）

在项目根目录的 `local.properties` 中设置：

```properties
GEMINI_API_KEY=your_gemini_key_here
OPENAI_API_KEY=your_openai_key_here
```

这些密钥会作为 BuildConfig 常量编译进应用程序，当用户没有设置时作为默认值。

## 常见问题

### Q: API 密钥存储在哪里？

A: API 密钥使用 Android 的 `EncryptedSharedPreferences` 加密存储在应用程序私有目录中。

### Q: 如何切换供应商？

A: 在设置页面选择新的供应商，并确保已填入该供应商的 API 密钥。

### Q: 为什么语音识别只支持部分供应商？

A: 只有 Gemini、OpenAI（Whisper）和 Groq（Whisper）原生支持语音识别。其他供应商需要另外配置语音识别服务。

### Q: 自定义供应商如何设置？

A:

1. 选择「自定义 / 自托管」
2. 填入 Base URL（OpenAI 兼容格式）
3. 填入模型名称
4. 如有需要，填入 API Key

### Q: 出现「API Key 未设置」错误怎么办？

A:

1. 确认已在设置中输入正确的 API 密钥
2. 确认 API 密钥没有过期或被撤销
3. 尝试重新输入 API 密钥
