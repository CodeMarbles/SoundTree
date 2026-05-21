# SoundTree

**A personal audio recorder and organiser for Android.**

Record audio, sort it into a hierarchical topic tree, mark moments, and play it back — entirely on-device, with no accounts, no cloud, and no tracking.

> Licensed under [GPL-3.0](LICENSE)

---

## Screenshots

| Record | Waveform | Listen | Marks |
|:------:|:--------:|:------:|:-----:|
| ![Recording screen](fastlane/metadata/android/en-US/images/phoneScreenshots/01_record.png) | ![Live waveform](fastlane/metadata/android/en-US/images/phoneScreenshots/02_waveform.png) | ![Listen screen](fastlane/metadata/android/en-US/images/phoneScreenshots/03_listen.png) | ![Marks panel](fastlane/metadata/android/en-US/images/phoneScreenshots/04_marks.png) |

| Library | Topics | Details | Backup |
|:-------:|:------:|:-------:|:------:|
| ![Library](fastlane/metadata/android/en-US/images/phoneScreenshots/05_library.png) | ![Topic tree](fastlane/metadata/android/en-US/images/phoneScreenshots/06_topics.png) | ![Topic details](fastlane/metadata/android/en-US/images/phoneScreenshots/07_details.png) | ![Backup](fastlane/metadata/android/en-US/images/phoneScreenshots/08_backup.png) |

---

## What it does

- **Record** with a live scrolling waveform, pause/resume, save from the notification, and drop timestamp marks on moments worth revisiting
- **Organise** into a nested topic tree — as deep as you need, with an inbox for anything unsorted
- **Play back** with variable speed, configurable skip buttons, and automatic resume position
- **Back up** to a USB drive on connect — no subscription, no cloud
- **No network permission** — your recordings never leave your device

---

## Building from Source

**Requirements:** Android Studio Hedgehog or newer · Android SDK 34 · JDK 11+

```bash
git clone https://github.com/CodeMarbles/SoundTree
cd SoundTree
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## License

SoundTree is free software, licensed under the [GNU General Public License v3.0](LICENSE).