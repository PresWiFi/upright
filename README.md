# Upright

An Android alarm that makes you get out of bed, and is hard to get rid of when you're half asleep. Includes a simple med tracker.

Personal use, sideloaded. Not for the Play Store (it uses device admin and an accessibility service on purpose).

## What it does

**Alarm**
- Rings loud and gets louder, ignores the volume keys, and rings through Do Not Disturb (if you grant DND access).
- Survives reboots. If the phone was off at alarm time, it rings as soon as the phone turns back on (up to 6 hours late).
- To stop it, you finish the steps you picked, in order:
  - **Scan a tag**: tap an NFC card/sticker or scan a barcode/QR code. Put them far from bed.
  - **Lights on**: the light sensor has to read bright for 30 seconds.
  - **Take a med**: scan the bottle (if it has a tag), then tap Taken or Skip (with a reason).
- **Still up?** 10 minutes after you finish, it asks "still up?". No answer within 60 seconds and it rings again.
- **Can't do this step**: swaps the current step for typing a 24-character code (lost card, broken sensor...).
- **Emergency**: the printed emergency code stops the alarm outright. It breaks your streak.

**Hard to quit** (Settings → Protection)
- Uninstall lock (device admin): Android won't uninstall the app.
- Settings guard (accessibility): from 2 hours before an alarm until you're up, it backs out of any settings screen that mentions Upright (app info, uninstall, force stop, admin, accessibility). While ringing, it also brings the alarm screen back if you leave it.
- Changing, turning off or deleting an alarm within those 2 hours needs the emergency code.
- Outside alarm hours everything is unlocked, so daytime you can still change things.

**Meds**
- Meds with times and days, reminders that repeat every 10 minutes (up to 3 hours) until you tap Taken, Snooze or Skip.
- Pill count goes down as you log, with a warning a set number of days before you run out.
- History: last 30 days, mornings-in-a-row and days-all-meds-taken streaks, and CSV export.
- Everything stays on the phone. No internet permission, no backups.

## Setup on the phone

1. Install `Upright-0.1.0.apk`.
2. Open Settings in the app and work through the checklist. Each "Fix" button opens the right system page.
   - On OnePlus also go to app info → Battery → **Unrestricted**, and allow auto-launch if it's offered.
3. Add tags (Settings → Tags): tap each NFC card, or scan a barcode. Name them by where they'll live.
4. Add meds, and give the morning one a bottle tag (scan the pharmacy label barcode, or stick an NFC sticker on it).
5. Add an alarm with steps. Use **Test now** to try it.
6. Make the emergency code, write it down, put it somewhere you have to get up to reach. Turn **Protection on**.

## Build

Needs JDK 17 and the Android SDK (compileSdk 37).

```
./gradlew testDebugUnitTest     # unit tests for the alarm, session and med logic (Logic.kt)
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the debug key on purpose, so they install without a keystore.
If you build from WSL, copy the project onto the Linux filesystem first; Gradle on `/mnt/c` breaks on file modes.

## Code map

- `Model.kt`: data (alarms, steps, tags, meds, doses, wake-up session, settings).
- `Logic.kt`: all the rules, pure and unit-tested (next alarm time, session steps, still-up, dose states, reminders, streaks, refill, CSV).
- `Store.kt`: one JSON file in device-protected storage, so the alarm works before first unlock after a reboot.
- `Scheduler.kt`, `Receivers.kt`: Android alarms, boot handling, reminder buttons.
- `RingService.kt`: sound, vibration, volume. `RingActivity.kt`: the ringing screen and steps.
- `GuardService.kt`: the settings guard. `ui/`: the app screens.
