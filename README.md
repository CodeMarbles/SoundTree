# SoundTree

A personal podcast recorder and organiser for Android. Record audio, sort it into a hierarchical topic tree, and play it back — all on-device with no cloud dependency.

---

## Prerequisites & Build

- Android Studio Hedgehog or newer
- Android SDK 34
- JDK 11+

```bash
git clone https://github.com/CodeMarbles/SoundTree
cd SoundTree
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Dependencies

| Library                  | Purpose                                    |
|--------------------------|--------------------------------------------|
| Room 2.6                 | Local SQLite database with KSP codegen     |
| ViewPager2               | Tab and sub-page navigation                |
| MediaRecorder            | Audio capture (M4A/AAC)                    |
| MediaPlayer              | Playback with resume position              |
| Material Components      | Buttons, BottomSheets, dialogs             |
| Kotlin Coroutines + Flow | Reactive data pipeline from DB to UI       |

---

## License

SoundTree is licensed under the [GNU General Public License v3.0](LICENSE).