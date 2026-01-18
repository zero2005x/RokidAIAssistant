# Rokid CXR SDK Integration

This directory is for placing the Rokid CXR SDK AAR file.

## How to Get the SDK

1. Visit Rokid Developer Portal: https://developer.rokid.com/
2. Download the CXR SDK (cxr-client-m-x.x.x.aar)
3. Place the AAR file in this directory
4. Rename it to `cxr-client-m.aar` for simplicity

## Enabling the SDK

After placing the AAR file, uncomment this line in `build.gradle.kts`:

```kotlin
implementation(files("libs/cxr-client-m.aar"))
```

## SDK Features

The CXR SDK provides access to Rokid glasses hardware through the system service layer:

### Camera APIs

- `CxrApi.getInstance().openGlassCamera(width, height, quality)` - Open camera
- `CxrApi.getInstance().takeGlassPhoto(width, height, quality, callback)` - Take photo
- Camera is auto-managed by system, no need to explicitly close

### Status Codes

- `RESPONSE_SUCCEED` - Operation successful
- `RESPONSE_TIMEOUT` - Operation timed out
- `RESPONSE_INVALID` - Invalid parameters

### Callbacks

- `PhotoResultCallback` - Returns photo as ByteArray
- `PhotoPathCallback` - Returns path to saved photo

## Why Use CXR SDK

The CXR SDK communicates with Rokid's system service (`com.rokid.os.sprite.assistserver`)
to coordinate camera access, avoiding conflicts that occur with direct Camera2 API access.

Without the SDK, you may see errors like:

```
CameraService_proxy: Recent task package name: com.example.rokidglasses
doesn't match with camera client package name: com.rokid.os.sprite.assistserver
```

## Fallback Mode

If the SDK is not available, the app will automatically fall back to using
Android Camera2 API, which may have limited functionality on Rokid glasses.
