# TreeCast

A personal podcast recorder and organiser for Android. Record audio, sort it into a hierarchical topic tree, and play it back — all on-device with no cloud dependency.

---

## Features

- **One-tap recording** — launches straight into record mode if you haven't opened the app in 4+ hours
- **Podcast tree** — organise recordings into nested categories with custom icons and colours
- **Inbox** — uncategorised recordings land here until you sort them
- **Playback with marks** — drop timestamp markers during playback and jump back to them later
- **Persistent settings** — scrub-back/forward intervals and auto-navigate preference survive app restarts
- **Mini player** — persistent playback bar above the bottom nav, visible from any tab
- **Back navigation** — Android back button retraces your tab history; within Library it navigates sub-pages before leaving the tab

---

## Navigation

The app has four tabs managed by a `ViewPager2`:

| Tab | Description                                           |
|-----|-------------------------------------------------------|
| **Settings** | Playback preferences and session stats                |
| **Record** | Live recording with stop/save                         |
| **Library** | Podcast Tree and Inbox sub-pages                      |
| **Listen** | Full playback controls, topic picker, and marks panel |

The back button walks your tab history in reverse order. Inside the Library tab, pressing back from the Inbox sub-page returns to Podcast Tree before leaving the tab entirely.

---

## Project Structure

```
app/src/main/java/com/treecast/app/
├── TreeCastApp.kt           (Application class — repository singleton)
├── ui/
│   ├── MainActivity.kt      (ViewPager2, bottom nav, back stack, mini player)
│   ├── MainViewModel.kt     (shared state, MediaPlayer, settings via SharedPreferences)
│   ├── MainPagerAdapter.kt
│   ├── SplashActivity.kt    (quick-record launch logic)
│   ├── record/
│   │   └── RecordFragment.kt
│   ├── listen/
│   │   └── ListenFragment.kt
│   ├── library/
│   │   ├── LibraryFragment.kt       (hosts sub-page ViewPager2, handles back press)
│   │   ├── LibraryTilesAdapter.kt
│   │   ├── InboxTileFragment.kt
│   │   └── CategoryTileFragment.kt
│   ├── tree/
│   │   ├── TreeViewFragment.kt
│   │   ├── TreeItemAdapter.kt
│   │   ├── RecordingsAdapter.kt
│   │   └── PlaybackBottomSheet.kt
│   ├── settings/
│   │   └── SettingsFragment.kt
│   └── common/
│       └── CategoryPickerView.kt
├── data/
│   ├── db/
│   │   └── AppDatabase.kt
│   ├── dao/
│   │   ├── SessionDao.kt
│   │   ├── CategoryDao.kt
│   │   ├── RecordingDao.kt
│   │   └── MarkDao.kt
│   ├── entities/
│   │   ├── SessionEntity.kt
│   │   ├── CategoryEntity.kt
│   │   ├── RecordingEntity.kt
│   │   └── MarkEntity.kt
│   └── repository/
│       ├── TreeCastRepository.kt
│       └── TreeBuilder.kt
```

---

## Database

### `sessions`
| Column | Type | Purpose |
|--------|------|---------|
| id | INTEGER PK | Auto-increment |
| opened_at | INTEGER | Epoch ms — app came to foreground |
| closed_at | INTEGER? | Epoch ms — app left foreground (null if active) |
| duration_ms | INTEGER? | closed_at − opened_at |
| session_type | TEXT | `"IDLE"` \| `"RECORD"` \| `"PLAYBACK"` |
| app_version | INTEGER | Build version code |

### `categories`
Self-referential tree via `parent_id` (nullable = root node). Cascade-deletes children on removal.

### `recordings`
Belongs to a category (nullable = Inbox). Stores file path, duration, playback position, tags, favourite flag, and listened flag.

### `marks`
Timestamp markers belonging to a recording. Used by the marks panel on the Listen tab.

---

## Settings Persistence

User preferences are stored in `SharedPreferences` (`treecast_settings`) and loaded into the ViewModel on startup. The following settings persist across sessions:

| Preference | Key | Default |
|------------|-----|---------|
| Jump to recording after saving | `jump_to_library_on_save` | true |
| Auto-navigate to Listen on play | `auto_navigate_to_listen` | false |
| Scrub-back interval (seconds) | `scrub_back_secs` | 15 |
| Scrub-forward interval (seconds) | `scrub_forward_secs` | 15 |

---

## Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- Android SDK 34
- JDK 11+

### Build
```bash
git clone https://github.com/you/treecast
cd treecast
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First Launch
1. Grant **Microphone** permission when prompted.
2. If this is your first launch (or you haven't opened the app in 4+ hours), the app opens directly to Record and begins recording automatically.
3. Tap **■ STOP & SAVE** to save the recording to your Inbox.
4. Switch to **Library → Inbox** to find it.
5. Long-press the recording to move it into a category.

---

## Dependencies

| Library | Purpose |
|---------|---------|
| Room 2.6 | Local SQLite database with KSP codegen |
| ViewPager2 | Tab and sub-page navigation |
| MediaRecorder | Audio capture (M4A/AAC) |
| MediaPlayer | Playback with resume position |
| Material Components | Buttons, BottomSheets, dialogs |
| Kotlin Coroutines + Flow | Reactive data pipeline from DB to UI |
| MPAndroidChart | Available for future waveform/stats charts |

---

## License
MIT