# Windows Device Smoke Test (2026-09-30)

> 📖 [繁體中文版](zh-TW/DEVICE_SMOKE_TEST.md)

This section preserves the results of the build tested on September 30; see **2026-10-01** below for the latest fixes and tests after replacing the API key. The earlier 401, preview, and CXR results should not be treated as conclusions for the latest build.

## Devices and Versions

- Windows ADB; phone `eeaas88ts4kn6l8t` (21091116UG, Android 13), glasses `1901092544022855` (RG_glasses, Android 12L).
- Phone `com.example.rokidphone`, glasses `com.example.rokidglasses`: 1.1.0 / versionCode 5.
- Using the previously installed debug APKs re-signed with the existing release certificate. See `DEVICE_INSTALLATION.md` for installation hashes and data-preservation evidence.
- Tested around 21:57–22:15 (Asia/Taipei). No recompilation, installation, or app code changes were performed during this run.

## Avoiding Interference with Other Debugging Work

Another Claude Desktop session was working on RideFlux. Before each input or launch, we read the device foreground package and operated only on this app, additionally allowing the device's native launcher when starting up. We did not touch RideFlux, stop other adb processes, restart the adb server, clear logcat, reset Bluetooth pairing, or clear app data. This check reduces conflicts, though it cannot guarantee another process will not interact with the device immediately after the check.

## Verified Items

| Item | Measured Result |
| --- | --- |
| Launch and navigation | Both apps launch; phone home screen, Settings, dedicated model routing page, dedicated display page, and chat screen are accessible. |
| Phone / glasses text channel | After the phone started listening, selecting Redmi Note 11 Pro+ 5G from the glasses device list showed connected on both ends; SPP connected at 22:05:20. |
| Saved display settings | Upon connection, the glasses received the existing settings: monospace font, 17 sp, width 51%, height 46%, horizontal 23%, vertical 45%. Screen content rendered within this viewport. |
| Sync only on Apply | Switching to the system font on the phone without tapping Apply left the glasses text nodes and positions unchanged; after tapping Apply, the layout width of the device name changed and a `SYSTEM_CONFIG` transmission was logged. |
| Font size sync | Temporarily changing 17 sp to 24 sp and tapping Apply scaled up the glasses main text, connection prompt, phone name, and bottom hint together; applying 17 sp afterward restored the original size. |
| Reset draft | Tapping Reset changed the phone preview back to default values while the glasses kept their applied settings; leaving the unapplied draft and re-entering read back the saved values. |
| Settings restoration | Confirmed all six values matched their pre-test state at the end; the Apply button returned to disabled and the glasses text size was restored. |
| AI error handling | A live text request received Gemini HTTP 401 (invalid key). The phone displayed "AI request failed" with the original reason and did not add an assistant reply to the chat; the glasses received `AI_ERROR` and showed the retry hint. |
| Crash logs | `AndroidRuntime` logs available since 21:57 showed no crash markers for either package; this does not imply all features or long-term stability are verified. |

## Issues and Blockers Found

1. **AI credential blocker:** Gemini returned HTTP 401. Although Settings showed Jev enabled and all three slots populated, no answer was obtained, so we cannot claim live verification of Jev difficulty classification, cross-provider answers, routing reasons, or multi-turn context. Keys were not modified, displayed, or extracted. The user was asked to check the credentials on the phone before re-testing.
2. **Rokid CXR BLE:** `Failed(error=BLE connect failed)` was logged at 22:07:30, 22:09:44, 22:12:04, etc., followed by continuous retries while the SPP text / config channel remained healthy. The root cause was not yet isolated; SPP connection success does not imply camera channel availability, nor can the failure be attributed to Claude. Photo capture was not executed.
3. **Phone preview text overlap:** At width 51%, height 46%, and 24 sp, the answer text in the illustrative preview overlapped with the top-right status and bottom hint. This was a measured layout issue in the phone preview; the glasses use a different layout, so this does not imply pagination on the glasses failed. Recorded without replacing the APK under test.
4. **ADB IME limitation:** The first synthetic English prompt was converted into mixed text by the phone's Zhuyin IME; the request was sent, but it cannot be counted as a correctness test for "7 + 5". We did not modify the user's IME or classify this as an app input defect.

## Items Not Yet Verified

- Successful AI answers, Jev/Laya tiering, cross-model context, and live routing reasons; Laya still requires a server URL and valid configuration.
- Glasses pagination and page-by-page completeness on long AI answers, plus the full matrix of maximum font size and minimum viewport bounds.
- Voice capture / transcription / TTS, photo analysis, Gemini Live, other providers, and on-device checks across all 13 languages.
- Actual optical field of view and wearing comfort on the glasses; ADB screenshots are 480×640 and cannot replace wearing confirmation by the user.

## Local Evidence

Screenshots and UI XML dumps are stored in the ignored directory `build/device-smoke-20260930/`; screenshots of the user's chat list were not committed to version control:

- `phone-display-current`, `phone-display-draft`, `phone-reset-draft`, `phone-restored-final`.
- `glasses-unapplied`, `glasses-applied-system`, `glasses-size-24-applied`, `glasses-restored-final`.
- `phone-text-first-response`: raw HTTP 401 display; `phone-size-draft`: preview overlap.
- `log-summary.json`: retains only connection / message types / exception classes, without keys, prompts, or raw request payloads.

Screenshots and UI XML dumps were captured in two consecutive reads and may not be at the exact same instant during transitions; connection and apply results were cross-checked against subsequent stable screens and logs.

## 2026-10-01: Fixed Build and New Gemini Key

Operated on the same two devices over Windows ADB using a data-preserving APK update. The user approved office photo capture and replaced the Gemini API key on the phone. Before each input, both the resumed activity and current focused window were checked, stopping whenever LINE or Chrome was in the foreground; RideFlux and the shared adb server were not disturbed.

| Item | Result |
| --- | --- |
| New Gemini key | The user asked `123&321=?` directly; the app answered 65 and displayed `gemini-3.8-flash` along with the reason that Gemini structured classification selected the Fast tier. This prompt was entered by the user rather than an automated synthetic test. |
| Live provider connectivity | On-device opt-in Android instrumentation: one synthetic `7 + 5` chat and one Traditional Chinese multi-step architecture classification each for Gemini and OpenAI, `OK (4 tests)` in 18.417 s. Keys were read from encrypted on-device storage and never left the phone. |
| Decision timeout difference | Native app Gemini model routing was observed succeeding; the instrumentation classification contract test uses a 60-second total timeout, so it alone does not prove both backends always finish within the production 3.5-second budget. |
| Office photo capture and analysis | After replacing the key, a new photo was captured on the glasses, transferred over SPP to the phone, analyzed by Gemini, and displayed across 4 pages on the glasses with no 401 error. An earlier photo before the key change transferred over SPP but failed analysis with 401 and is not counted as an analysis success. |
| Camera capability and aspect ratio | Logs confirmed receipt of `DISPLAY_METRICS`, `SPP; display 480x640`. The native glasses companion app used the SPP camera and no longer triggered the earlier CXR BLE retries. This does not imply the legacy SDK path on older devices is fixed. |
| New phone preview | Displayed `APP 顯示範圍：480 × 640 px`, matching the actual app aspect ratio. At 24 sp, 51%×46%, monospace font, status, main text, and bottom hint did not overlap, and the main text scrolled properly. |
| Apply and restore | While the 24 sp draft was unapplied, the glasses stayed at 17 sp; after tapping Apply, all text scaled up together. Finally, applying 17 sp, monospace, width 51%, height 46%, horizontal 23%, vertical 45% restored the baseline, disabled the Apply button on the phone, and reconnected both ends cleanly. |

Evidence is stored in the ignored directory `build/device-smoke-20260930/`: `glasses-new-key-photo`, `glasses-photo-page-3`, `glasses-photo-page-4`, `phone-preview-size-24-draft`, `glasses-reconnected-before-apply`, `glasses-current-size-24-applied`, `phone-baseline-restored-final`, `glasses-current-restored-baseline`. No API key screens were saved; screenshots were skipped whenever non-password input fields were visible, and input fields in XML dumps were masked.

Still untested: live Jev / Laya classification quality (no Laya URL provided), all font-size and viewport combinations, cross-model multi-turn context, voice capture / transcription / TTS, Gemini Live, remaining providers, all 13 languages, and actual optical wearing field of view. Agents remain deferred per user direction.
