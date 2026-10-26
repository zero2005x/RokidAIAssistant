# Phone / Glasses Installation Record

> 📖 [繁體中文版](zh-TW/DEVICE_INSTALLATION.md)

This document preserves the earlier installation records; see the **2026-10-01 Update** at the bottom for the APK hashes of the current device builds.

2026-09-30 (Asia/Taipei). Using the phone / glasses Debug APKs compiled at 15:05.

## Installation Results

| Device | ADB Serial | Package | Update Time | Result |
| --- | --- | --- | --- | --- |
| Phone 21091116UG | eeaas88ts4kn6l8t | com.example.rokidphone | 17:02:56 | Success |
| Glasses RG_glasses | 1901092544022855 | com.example.rokidglasses | 17:03:08 | Success |

Both devices are on version `1.1.0`, `versionCode=5`. Updated via `adb -s <serial> install -r <apk>`, preserving app data and settings. Update timestamps were verified with `dumpsys package`, and on-device `sha256sum` confirmed that each installed `base.apk` is identical to the deployed APK.

## Signing and Deployment Packages

During the first installation attempt, we found that the existing apps on the devices were signed with the release key rather than the Windows Debug key produced by the build; the phone ADB channel also briefly stopped responding and was recovered with a targeted phone reconnect. After inspecting the existing APKs on both devices, we confirmed that the release key configured in the project's `local.properties` matches the device certificate, and re-signed copies of the compiled APKs with that key.

The APK build type remains Debug; the code, resources, and `AndroidManifest` after re-signing are identical, and the ZIP diff consists solely of the `META-INF/M365KEY.RSA`, `META-INF/M365KEY.SF`, and `META-INF/MANIFEST.MF` signature entries. Both deployment packages passed `apksigner verify`.

Release certificate SHA-256:

```text
7CA3A3F7BAC7483C0D16BB9E1EBDB657F094CE779483DDBC7FE364324601E30B
```

Deployment files:

- [Phone update APK](../build/device-install-check/phone-app-device.apk)
- [Glasses update APK](../build/device-install-check/glasses-app-device.apk)

Device and deployment file SHA-256:

```text
phone-app-device.apk
B5AB475C5136AE3770B80BD9BB2950E1FE4B890C447B58DD25317E005C67569A

glasses-app-device.apk
A8D54D60A95EA9656158F8F369AB47FCD8B34DA647C7AA411EBDF57890840CD7
```

This step completed installation and file verification; functional, Bluetooth, and live API tests are recorded separately. See the [Review Fix Verification Report](REVIEW_FIX_VERIFICATION.md) for build and unit test records.

## 2026-10-01 Update

Both devices remain on `1.1.0 / versionCode 5`, updated with `install -r` while preserving data. The final phone update includes Gemini / OpenAI decision routing, actual app aspect-ratio preview, SPP capability detection, photo error handling, and the final "model routing" title fix. The glasses update includes display metrics and SPP camera capability reporting.

| Device | Package | Device-Confirmed Update Time (+08:00) | Deployed APK Bytes |
| --- | --- | --- | ---: |
| Phone eeaas88ts4kn6l8t | com.example.rokidphone | 2026-10-01 11:00:49 | 82,718,213 |
| Glasses 1901092544022855 | com.example.rokidglasses | 2026-10-01 10:30:06 | 29,798,314 |

Each on-device `base.apk` SHA-256 matches the deployed files below:

```text
phone-app-device.apk
3069BC57FAF48D733E36959451AC06FB68D453AFEC8483C5A43BFC9E7CF17ADC

glasses-app-device.apk
0E590F2D95CB1314EDF2A90DCB3E8463815C03AAFC3575BC412900E333F511D6
```

`apksigner verify --verbose --print-certs` succeeded for both deployment packages, using v3 with a single RSA 2048-bit signer; the certificate is the same existing release certificate listed above. The ZIP diff against each compiled APK consists solely of the three `META-INF` signature entries, with identical code and resources. Installation and hash verification evidence is saved in `build/device-install-check/oct01-installed-proof.json`.

The final launch succeeded with both phone and glasses showing connected; font size and display viewport remained at their original values. For live API key, photo, and display test results, see [Device Smoke Test](DEVICE_SMOKE_TEST.md).
