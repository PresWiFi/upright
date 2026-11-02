# Upright

An Android alarm that makes you get out of bed, and is hard to get rid of when you're half asleep.

Personal use, sideloaded. Not for the Play Store (it uses device admin and an accessibility service on purpose).

## What it does

**Alarm**
- Rings loud and gets louder, ignores the volume keys, and rings through Do Not Disturb (if you grant DND access).
- Survives reboots. If the phone was off at alarm time, it rings as soon as the phone turns back on (up to 6 hours late).
- To stop it, you finish the steps you picked, in order:
  - **Scan a tag**: tap an NFC card/sticker or scan a barcode/QR code. Put them far from bed.
  - **Scan one of several tags**: any one of the tags you pick counts (handy with a tag in each room, or a spare card).
  - **Lights on**: the light sensor has to read bright for 30 seconds.
- **Still up?** 10 minutes after you finish, it asks "still up?". No answer within 60 seconds and it rings again.
- **Can't do this step**: swaps the current step for typing a 24-character code (lost card, broken sensor...).
- **Hush**: silences the alarm for 45 seconds, twice per alarm, for a bad moment. Each use is logged.
- **Emergency**: the printed emergency code stops the alarm outright. It breaks your streak.

**Galaxy Watch** (Wear OS, Galaxy Watch 4 or newer)
- Buzzes while the phone rings, pauses during a hush, and stops when you finish your steps.
- Keeps its own copy of the next alarm. If the phone hasn't started ringing 90 seconds after alarm time (dead, off, out of range), the watch rings by itself. It can only be stopped from the watch (hold 3 seconds) when the phone can't be reached.

**Morning routine**
- A checklist (shower, dress, out the door) that starts as soon as you finish the wake-up steps. Each item has a time budget.
- An ongoing notification shows the current item with a countdown and Done / Skip buttons. Go over and it nudges you every 5 minutes.
- Can be switched off per alarm.

**Hard to quit** (Settings → Protection)
- Uninstall lock (device admin): Android won't uninstall the app.
- Settings guard (accessibility): from 2 hours before an alarm until you're up, it backs out of any settings screen that mentions Upright (app info, uninstall, force stop, admin, accessibility). While ringing, it also brings the alarm screen back if you leave it.
- Changing, turning off or deleting an alarm within those 2 hours needs the emergency code.
- Outside alarm hours everything is unlocked, so daytime you can still change things.

**History**
- Last 30 days, mornings-in-a-row streak, and CSV export.

**Weekly report** (History tab, plus a notification Sunday evening)
- How fast you got up, how often the still-up check caught you, your slowest step, hushes and emergency stops, routine on-time rate, all compared with the week before.

**Tamper log** (Settings)
- Every blocked settings screen, wrong code, protection change, hush, step swap, force-stop and uninstall-lock attempt, with the time.
- Everything stays on the phone, with no account and no backups. Upright never uploads your data. (The APK lists internet access because Google's barcode scanner library adds it.)

## Setup on the phone

1. Download the APK from [Releases](https://github.com/PresWiFi/upright/releases/latest) on the phone and install it (allow installs from your browser when asked).
2. Open Settings in the app and work through the checklist. Each "Fix" button opens the right system page.
   - On OnePlus also go to app info → Battery → **Unrestricted**, and allow auto-launch if it's offered.
3. Add tags (Settings → Tags): tap each NFC card, or scan a barcode. Name them by where they'll live.
4. Add an alarm with steps. Use **Test now** to try it.
5. Make the emergency code, write it down, put it somewhere you have to get up to reach. Turn **Protection on**.

## Install the watch app

Watch apps can't be sideloaded from the phone, so this uses ADB over Wi-Fi from a computer (once).

1. Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) on your computer.
2. On the watch: Settings → About watch → Software → tap **Software version** 5 times to turn on Developer options.
3. Settings → Developer options → turn on **ADB debugging** and **Wireless debugging**. Put the watch and computer on the same Wi-Fi.
4. In Wireless debugging, tap **Pair new device**. On the computer: `adb pair <ip>:<pair-port>` and type the code.
5. Then `adb connect <ip>:<port>` (the port shown on the Wireless debugging screen) and `adb install Upright-watch-0.2.0.apk`.
6. Open Upright on the watch once and allow notifications. Settings → Galaxy Watch on the phone should say **Connected**.
7. Turn Wireless debugging back off.

The watch and phone apps must come from the same build (same signing key), or they can't talk to each other.

## Build

Needs JDK 17 and the Android SDK (compileSdk 37).

```
./gradlew testDebugUnitTest     # unit tests for the alarm and session logic (Logic.kt)
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk and wear/build/outputs/apk/release/wear-release.apk
```

Release builds are signed with the key named in `~/.gradle/gradle.properties`:

```
upright.keystore=/path/to/upright-release.jks
upright.keystorePassword=...
```

Without those lines they fall back to the debug key. Updates only install over a build signed with the same key, so keep a backup of the keystore and its password. Releases from 0.3.0 on use a new key; 0.1.0 and 0.2.0 have to be uninstalled before installing 0.3.0.
If you build from WSL, copy the project onto the Linux filesystem first; Gradle on `/mnt/c` breaks on file modes.

## Code map

- `Model.kt`: data (alarms, steps, tags, wake-up session, settings).
- `Logic.kt`: all the rules, pure and unit-tested (next alarm time, session steps, still-up, routine, streaks, weekly report, CSV).
- `Store.kt`: one JSON file in device-protected storage, so the alarm works before first unlock after a reboot.
- `Scheduler.kt`, `Receivers.kt`: Android alarms, boot handling, reminder buttons.
- `RingService.kt`: sound, vibration, volume. `RingActivity.kt`: the ringing screen and steps.
- `GuardService.kt`: the settings guard. `ui/`: the app screens.
- `WatchLink.kt`: sends alarm state to the watch.
- `wear/`: the watch app (sync with the phone, backup alarm, buzzing).
