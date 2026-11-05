# Upright

Android alarm that makes you get out of bed and is hard to dismiss half asleep.

Personal use, sideloaded. Not Play Store compatible (uses device admin and accessibility).

## Features

**Alarm**
- Escalating volume, ignores volume keys, rings through Do Not Disturb (with DND access).
- Survives reboots. Rings on boot if it was missed while off (up to 6 h late).
- Stop it by finishing your chosen steps in order:
  - **Scan a tag:** NFC card/sticker or barcode/QR
  - **Scan one of several tags:** any one of a set
  - **Lights on:** light sensor reads bright for 30 s
- **Still up?** 10 min later it asks; no answer in 60 s and it rings again.
- **Can't do this step:** swap the step for typing a 24-character code.
- **Hush:** 45 s silence, twice per alarm, logged.
- **Emergency:** printed code stops it outright and breaks your streak.

**Galaxy Watch** (Wear OS, Watch 4+)
- Buzzes with the phone, pauses on hush, stops when you finish.
- Keeps its own copy of the next alarm and rings by itself if the phone hasn't started 90 s after alarm time. Stoppable on the watch (hold 3 s) only when the phone is unreachable.

**Morning routine**
- Checklist with per-item time budgets, starting after the wake-up steps.
- Ongoing notification with countdown and Done/Skip. Nudges every 5 min when over.
- Can be turned off per alarm.

**Protection** (Settings → Protection)
- Uninstall lock (device admin).
- Settings guard (accessibility): from 2 h before an alarm until you're up, backs out of any settings screen mentioning Upright. While ringing, brings the alarm screen back.
- Changing, disabling or deleting an alarm in that window needs the emergency code. Unlocked otherwise.

**History**
- Last 30 days, streak, CSV export.
- Weekly report (History tab + Sunday notification): time to get up, still-up catches, slowest step, hushes, emergency stops, routine on-time rate, vs. the previous week.
- Tamper log (Settings): blocked screens, wrong codes, protection changes, hushes, step swaps, force-stops, uninstall-lock attempts.

All data stays on the phone. No account, no backups, no uploads. (Internet permission comes from Google's barcode scanner library.)

## Setup

1. Install the APK from [Releases](https://github.com/PresWiFi/upright/releases/latest).
2. Open Settings in the app and work through the checklist. On OnePlus, also set app info → Battery → **Unrestricted** and allow auto-launch.
3. Add tags (Settings → Tags), named by location.
4. Add an alarm with steps. **Test now** to try it.
5. Create the emergency code, store it away from bed, and turn **Protection on**.

## Watch app

Installed once over ADB Wi-Fi:

1. Install [platform-tools](https://developer.android.com/tools/releases/platform-tools).
2. Watch: Settings → About watch → Software → tap **Software version** 5 times.
3. Developer options → enable **ADB debugging** and **Wireless debugging** (same Wi-Fi as the computer).
4. Wireless debugging → **Pair new device**, then `adb pair <ip>:<pair-port>` and enter the code.
5. `adb connect <ip>:<port>` and `adb install Upright-watch-0.2.0.apk`.
6. Open Upright on the watch and allow notifications. Phone Settings → Galaxy Watch should say **Connected**.
7. Turn Wireless debugging off.

Watch and phone apps must share a signing key.

## Build

JDK 17 and Android SDK (compileSdk 37).

```
./gradlew testDebugUnitTest     # Logic.kt unit tests
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk and wear/build/outputs/apk/release/wear-release.apk
```

Release signing via `~/.gradle/gradle.properties`:

```
upright.keystore=/path/to/upright-release.jks
upright.keystorePassword=...
```

Without it, builds use the debug key. Updates need the same key, so back up the keystore. 0.3.0+ uses a new key; uninstall 0.1.0/0.2.0 first.
On WSL, build from the Linux filesystem; Gradle on `/mnt/c` breaks on file modes.

## Code map

- `Model.kt`: data
- `Logic.kt`: all rules, pure and unit-tested
- `Store.kt`: JSON in device-protected storage (works before first unlock)
- `Scheduler.kt`, `Receivers.kt`: alarms, boot, notification buttons
- `RingService.kt`: sound, vibration, volume; `RingActivity.kt`: ringing screen
- `GuardService.kt`: settings guard; `ui/`: screens
- `WatchLink.kt`: phone → watch sync; `wear/`: watch app
