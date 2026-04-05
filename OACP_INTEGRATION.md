# Adding OACP to Libre Camera — Step-by-Step Guide

This document describes every change made to add OACP (Open App Capability
Protocol) voice control to Libre Camera, a Flutter camera app. Use it as a
reference for adding OACP to any Flutter app.

## What OACP gives you

After integration, a voice assistant like Hark can:
- Discover your app's capabilities automatically
- Route voice commands to the correct action
- Launch your app with the right parameters

No changes to Hark or any assistant are needed — the app advertises its own
capabilities.

## Overview of changes

```
android/app/build.gradle.kts           — add SDK dependency
android/app/libs/oacp-android-release.aar — SDK binary
android/app/src/main/assets/oacp.json  — capability manifest (NEW)
android/app/src/main/assets/OACP.md    — LLM context document (NEW)
android/app/src/main/AndroidManifest.xml — add intent-filters
android/app/src/main/kotlin/.../MainActivity.kt — handle OACP intents
lib/main.dart                          — initialize OacpCommandService
lib/src/oacp/oacp_command_service.dart  — Dart bridge service (NEW)
lib/src/pages/camera_page.dart         — execute OACP commands
```

## Step 1: Add the OACP Android SDK

Copy `oacp-android-release.aar` into `android/app/libs/`.

Add to `android/app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/oacp-android-release.aar"))
    implementation("androidx.annotation:annotation:1.7.1")
}
```

The SDK auto-registers `OacpProvider` via manifest merger — this is the
ContentProvider that assistants query to discover your app. No manual
provider code needed.

## Step 2: Create oacp.json

Create `android/app/src/main/assets/oacp.json`. This is the machine-readable
manifest that describes what your app can do.

Key points:
- Use `"__APPLICATION_ID__"` for `appId` — the SDK substitutes the real
  package name at runtime (safe across debug/release variants)
- Each capability needs: `id`, `description`, `invoke` with `type` and `action`
- Use `"type": "activity"` for actions that need the UI (camera, scanner, etc.)
- Use `"type": "broadcast"` for background-safe actions (check battery, read data)
- Add `aliases`, `examples`, `keywords` to help the assistant match voice commands
- Add `parameters` with `extractionHint` for slot-filling

Example capability (take a photo):

```json
{
  "id": "take_photo_rear_camera",
  "description": "Take a photo with the rear camera after an optional countdown.",
  "aliases": ["take a photo", "take a picture", "capture a photo"],
  "examples": ["take a photo", "take a picture in 4 seconds"],
  "keywords": ["camera", "photo", "picture", "capture"],
  "parameters": [
    {
      "name": "duration_seconds",
      "type": "integer",
      "required": false,
      "description": "Countdown before taking the photo.",
      "extractionHint": "Extract the countdown duration in seconds. '5 seconds' → 5. Return null if not mentioned.",
      "minimum": 1,
      "maximum": 30
    }
  ],
  "confirmation": "never",
  "visibility": "public",
  "requiresForeground": true,
  "invoke": {
    "android": {
      "type": "activity",
      "action": "__APPLICATION_ID__.oacp.ACTION_TAKE_PHOTO_REAR_CAMERA",
      "extrasMapping": {
        "duration_seconds": "EXTRA_DURATION_SECONDS"
      }
    }
  }
}
```

### Disambiguation tip

If you have similar capabilities (front camera vs rear camera), keep generic
terms (`"photo"`, `"picture"`) on the default action only. The front camera
capability should only have `"selfie"` and `"front camera"` keywords to avoid
confusing the embedding model.

## Step 3: Create OACP.md

Create `android/app/src/main/assets/OACP.md`. This is the LLM-readable
context document — plain English for the assistant to understand your app.

```markdown
# My App — OACP Context

## What this app does
Brief description of the app.

## Capabilities
- capability_id: What it does. Parameters if any.

## Disambiguation
- "user utterance" → capability_id
```

## Step 4: Add intent-filters to AndroidManifest.xml

Add one `<intent-filter>` per capability inside your `<activity>` tag.
Use `${applicationId}` (Gradle variable) so it matches across build variants:

```xml
<intent-filter>
    <action android:name="${applicationId}.oacp.ACTION_TAKE_PHOTO_REAR_CAMERA" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

## Step 5: Handle OACP intents in MainActivity.kt

This is the bridge between the Android intent and your Flutter code.

### Add fields and constants

```kotlin
companion object {
    private const val OACP_CHANNEL = "com.yourapp.package/oacp"
    private const val METHOD_HANDLE_OACP_COMMAND = "handleOacpCommand"
}

private var oacpChannel: MethodChannel? = null
```

### Set up the channel in configureFlutterEngine

```kotlin
override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)

    oacpChannel = MethodChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        OACP_CHANNEL
    )
    dispatchOacpIntent(intent)
}
```

### Handle onNewIntent for warm starts

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    dispatchOacpIntent(intent)
}
```

### Build payload and dispatch with retry

The retry is essential — on cold start, the Dart isolate may not be ready when
`configureFlutterEngine` fires. The retry loop (40 × 250ms = 10s) handles this.

```kotlin
private fun dispatchOacpIntent(intent: Intent?) {
    val payload = buildOacpPayload(intent) ?: return
    sendOacpCommandWithRetry(payload, retriesLeft = 40)
    intent?.action = null  // prevent re-fire on config changes
}

private fun buildOacpPayload(intent: Intent?): HashMap<String, Any>? {
    val action = intent?.action ?: return null
    val payload = hashMapOf<String, Any>(
        "requestId" to System.currentTimeMillis().toString()
    )
    when {
        action.endsWith(".oacp.ACTION_TAKE_PHOTO_REAR_CAMERA") -> {
            payload["command"] = "take_photo"
            payload["camera"] = "rear"
        }
        // ... other actions
        else -> return null
    }
    // Extract parameters from intent extras
    val duration = intent.extras?.get("EXTRA_DURATION_SECONDS")
    if (duration is Int) payload["duration_seconds"] = duration
    return payload
}

private fun sendOacpCommandWithRetry(payload: HashMap<String, Any>, retriesLeft: Int) {
    val channel = oacpChannel ?: return
    channel.invokeMethod(METHOD_HANDLE_OACP_COMMAND, payload, object : MethodChannel.Result {
        override fun success(result: Any?) {}
        override fun error(code: String, msg: String?, details: Any?) {
            if (retriesLeft > 0) {
                Handler(Looper.getMainLooper()).postDelayed({
                    sendOacpCommandWithRetry(payload, retriesLeft - 1)
                }, 250)
            }
        }
        override fun notImplemented() {
            if (retriesLeft > 0) {
                Handler(Looper.getMainLooper()).postDelayed({
                    sendOacpCommandWithRetry(payload, retriesLeft - 1)
                }, 250)
            }
        }
    })
}
```

### Why `.endsWith()` instead of exact match?

Build variants change the package name (e.g., `com.example.app.debug`).
Using `.endsWith(".oacp.ACTION_...")` matches regardless of the prefix.

## Step 6: Create OacpCommandService in Dart

Create `lib/src/oacp/oacp_command_service.dart`. This is a singleton that
listens on the MethodChannel and exposes a stream of commands.

Key design decisions:
- **Singleton** — initialized once in `main()`, shared across widgets
- **Broadcast stream** — multiple listeners possible
- **Pending command** — stores the last command for cold-start pickup
  (the stream may fire before any widget listens)

Initialize in `lib/main.dart`:

```dart
await OacpCommandService.instance.initialize();
```

## Step 7: Wire into your app's UI

In the widget that handles the action (e.g., `CameraPage`):

### Listen for commands in initState

```dart
_oacpCommandSubscription = OacpCommandService.instance.commands.listen((cmd) {
  if (_handledOacpRequestIds.add(cmd.requestId)) {
    unawaited(_executeOacpCommand(cmd));
  }
});
```

### Check for pending commands after initialization

```dart
Future<void> _initializePage() async {
  await initializeMyWidget();  // e.g., camera setup

  final pending = OacpCommandService.instance.consumePendingCommand();
  if (pending != null && _handledOacpRequestIds.add(pending.requestId)) {
    await _executeOacpCommand(pending);
  }
}
```

### Execute the command

```dart
Future<void> _executeOacpCommand(OacpCommand command) async {
  switch (command.type) {
    case OacpCommandType.takePhoto:
      await takePicture(countdownOverrideSeconds: command.durationSeconds ?? 3);
    case OacpCommandType.startVideoRecording:
      await startVideoRecording();
  }
}
```

### Don't forget dispose

```dart
@override
void dispose() {
  unawaited(_oacpCommandSubscription?.cancel());
  super.dispose();
}
```

## Testing

### Verify discovery

```bash
adb shell content read --uri content://com.yourapp.package.oacp/manifest
adb shell content read --uri content://com.yourapp.package.oacp/context
```

### Test actions directly

```bash
# Photo with 5-second countdown
adb shell am start -n com.yourapp.package/.MainActivity \
  -a com.yourapp.package.oacp.ACTION_TAKE_PHOTO_REAR_CAMERA \
  --ei EXTRA_DURATION_SECONDS 5

# Video recording
adb shell am start -n com.yourapp.package/.MainActivity \
  -a com.yourapp.package.oacp.ACTION_START_VIDEO_RECORDING_REAR_CAMERA
```

### Test via Hark

1. Install your app on the same device as Hark
2. Restart Hark (forces re-discovery of OACP providers)
3. Say a voice command matching your capability
4. Check `adb logcat` for routing and execution logs

## Lessons learned

- **Activity transport for UI actions** — don't use broadcast + `startActivity`
  from the receiver. Modern Android restricts background activity launches.
  Use `"type": "activity"` directly.
- **Retry on cold start** — the Dart isolate takes 1-3 seconds to initialize.
  Without retry, the MethodChannel call fails silently.
- **Clear the intent action** — set `intent?.action = null` after dispatch to
  prevent re-firing on configuration changes.
- **Dedup with request ID** — both the stream listener and `consumePendingCommand`
  can trigger execution. Use a `Set<String>` of handled request IDs to prevent
  double execution.
- **Countdown UI needs override tracking** — if your app has a timer that reads
  from preferences, OACP countdown overrides won't show on screen unless you
  track the active override separately.
- **Keep default action keywords generic, specific action keywords narrow** —
  for "take a photo" vs "take a selfie", only the default (rear camera) should
  have generic terms like "photo", "picture". The front camera should only
  have "selfie", "front camera". This prevents the embedding model from
  splitting scores evenly.
