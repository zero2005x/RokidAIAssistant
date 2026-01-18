# Rokid AI 助手

[繁體中文](README_TW.md) | **简体中文** | [English](README_EN.md)

一款运行在 Android 手机上，与 Rokid 智能眼镜配合使用的 AI 语音助手应用程序。

## 功能特点

- 🔗 **蓝牙连接**：手机作为 SPP 服务器，眼镜作为客户端连接
- 🎤 **语音交互**：通过眼镜麦克风录制语音
- 🤖 **多供应商 AI**：支持 11 个 AI 供应商（Gemini、OpenAI、Claude、Perplexity 等）
- 📺 **AR 显示**：在眼镜屏幕上显示对话内容
- 🌍 **多语言支持**：支持 13 种语言
- 🔐 **安全存储**：API 密钥使用 EncryptedSharedPreferences 加密存储

## 系统架构

```
┌─────────────────┐      Bluetooth SPP      ┌─────────────────┐      WiFi      ┌─────────────────┐
│   Rokid 眼镜    │ ◄──────────────────────► │     手机 App    │ ◄────────────► │    AI APIs      │
│  (glasses-app)  │     语音/指令/响应       │   (phone-app)   │   HTTP/REST   │    (云端)       │
└─────────────────┘                          └─────────────────┘                └─────────────────┘
        │                                            │
        │                                            │
   ┌────┴────┐                              ┌───────┴───────┐
   │  录音   │                              │  AI 处理      │
   │  显示   │                              │  设置管理     │
   └─────────┘                              └───────────────┘
```

## 项目结构

```
RokidAIAssistant/
├── phone-app/                    # 手机应用程序
│   ├── data/
│   │   ├── ApiSettings.kt        # AI 供应商设置
│   │   ├── AppLanguage.kt        # 语言定义
│   │   └── SettingsRepository.kt # 设置存储
│   ├── service/
│   │   ├── PhoneAIService.kt     # 主要前台服务
│   │   ├── BluetoothSppManager.kt# 蓝牙 SPP 服务器
│   │   └── ai/                   # AI 服务实现
│   │       ├── GeminiService.kt
│   │       ├── OpenAiService.kt
│   │       ├── AnthropicService.kt
│   │       └── ...
│   └── ui/
│       ├── SettingsScreen.kt     # 设置界面
│       └── ...
│
├── glasses-app/                  # 眼镜应用程序
│   ├── service/
│   │   ├── BluetoothSppClient.kt # 蓝牙 SPP 客户端
│   │   └── WakeWordService.kt    # 唤醒词检测
│   └── viewmodel/
│       └── GlassesViewModel.kt   # UI 状态管理
│
└── common/                       # 共用模块
    ├── Message.kt                # 蓝牙消息格式
    ├── MessageType.kt            # 消息类型定义
    └── Constants.kt              # 共用常量
```

## 支持的 AI 供应商

| 供应商             | 对话 | 语音识别     | 视觉 |
| ------------------ | ---- | ------------ | ---- |
| Google Gemini      | ✅   | ✅           | ✅   |
| OpenAI             | ✅   | ✅ (Whisper) | ✅   |
| Anthropic Claude   | ✅   | ❌           | ✅   |
| DeepSeek           | ✅   | ❌           | ✅   |
| Groq               | ✅   | ✅ (Whisper) | ✅   |
| xAI (Grok)         | ✅   | ❌           | ✅   |
| 阿里云通义千问     | ✅   | ❌           | ✅   |
| 智谱 AI (ChatGLM)  | ✅   | ❌           | ✅   |
| 百度文心一言       | ✅   | ❌           | ✅   |
| Perplexity         | ✅   | ❌           | ❌   |
| 自定义 (Ollama 等) | ✅   | ❌           | ❌   |

## 支持的语言

应用程序界面支持以下 13 种语言：

| 语言     | 代码  | 原生名称   |
| -------- | ----- | ---------- |
| 英文     | en    | English    |
| 简体中文 | zh-CN | 简体中文   |
| 繁体中文 | zh-TW | 繁體中文   |
| 日文     | ja    | 日本語     |
| 韩文     | ko    | 한국어     |
| 越南文   | vi    | Tiếng Việt |
| 泰文     | th    | ไทย        |
| 法文     | fr    | Français   |
| 西班牙文 | es    | Español    |
| 俄文     | ru    | Русский    |
| 乌克兰文 | uk    | Українська |
| 阿拉伯文 | ar    | العربية    |
| 意大利文 | it    | Italiano   |

## 快速开始

### 前置需求

- Android Studio Hedgehog (2023.1.1) 或更新版本
- Android SDK 34
- Kotlin 2.x
- Rokid 眼镜设备（用于 glasses-app）
- 至少一个 AI 供应商的 API 密钥

### 设置步骤

1. **克隆项目**

   ```bash
   git clone <repository-url>
   cd RokidAIAssistant
   ```

2. **配置 API 密钥**

   复制 `local.properties.template` 为 `local.properties` 并填入您的密钥：

   ```properties
   sdk.dir=<您的 Android SDK 路径>
   GEMINI_API_KEY=<您的 Gemini API 密钥>
   OPENAI_API_KEY=<您的 OpenAI API 密钥>
   ```

3. **构建并运行**

   ```bash
   # 构建手机应用程序
   ./gradlew :phone-app:assembleDebug

   # 构建眼镜应用程序
   ./gradlew :glasses-app:assembleDebug
   ```

### 使用方式

1. 在 Android 手机上安装 `phone-app`
2. 在 Rokid 眼镜上安装 `glasses-app`
3. 打开手机应用程序并点击「启动服务」
4. 通过蓝牙将眼镜与手机配对
5. 在眼镜上，点击触控板或说出唤醒词开始录音
6. 说出您的问题，松开后即可获得 AI 回应

## 设置选项

所有设置都可以在手机应用程序的设置页面中配置：

- **AI 供应商**：从 11 个供应商中选择
- **AI 模型**：选择该供应商的模型
- **API 密钥**：每个供应商的密钥安全存储
- **语音识别**：选择语音识别服务
- **系统提示词**：自定义 AI 行为
- **应用程序语言**：界面语言选择

## 蓝牙通讯协议

### SPP UUID

```
a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### 消息格式

JSON 格式，使用换行符分隔，二进制数据使用 Base64 编码。

### 消息类型

| 类型             | 方向      | 说明               |
| ---------------- | --------- | ------------------ |
| VOICE_START      | 眼镜→手机 | 开始录音           |
| VOICE_END        | 眼镜→手机 | 录音结束，包含音频 |
| AI_PROCESSING    | 手机→眼镜 | 处理状态           |
| USER_TRANSCRIPT  | 手机→眼镜 | 语音转文字结果     |
| AI_RESPONSE_TEXT | 手机→眼镜 | AI 文字响应        |
| AI_ERROR         | 手机→眼镜 | 错误消息           |

## 音频格式

- **采样率**：16000 Hz
- **声道**：单声道
- **位深度**：16-bit
- **格式**：PCM → WAV（调用 API 前转换）

## 开发信息

### 构建需求

| 组件                  | 版本                   |
| --------------------- | ---------------------- |
| Android Gradle Plugin | 9.0.0                  |
| Kotlin                | 2.2.10                 |
| Gradle                | 9.1.0                  |
| Min SDK               | 26（眼镜）/ 28（手机） |
| Target SDK            | 34                     |

### 主要依赖

| 依赖              | 版本       |
| ----------------- | ---------- |
| Compose BOM       | 2024.02.00 |
| Generative AI SDK | 0.9.0      |
| Retrofit          | 2.11.0     |
| OkHttp            | 4.12.0     |

## 安全注意事项

⚠️ **重要**：

- `local.properties` 包含敏感的 API 密钥 - **请勿提交到 Git**
- API 密钥使用 EncryptedSharedPreferences 加密存储
- 所有密钥已在 `.gitignore` 中排除版本控制

## 授权

私有项目，仅供内部使用。

## 文档

- [架构指南](ARCHITECTURE.md)
- [API 设置指南](API_SETTINGS_CN.md)
- [疑难排解指南](TROUBLESHOOTING_CN.md)
