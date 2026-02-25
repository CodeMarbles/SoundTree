# TreeCast 🌳🎙️

**A tree-structured personal podcast / voice journal app for Android.**

---

## Concept

TreeCast treats your personal recordings like a file system — **folders** (categories) that can be infinitely nested, and **episodes** (recordings) that live inside them. The Library screen presents these as horizontal swipe-tile cards, with a **Tree View** card always pinned at the leftmost position, giving you a bird's-eye family-tree overview of everything you've recorded.

---

## Features

| Feature | Details |
|---------|---------|
| **Quick Record** | App detects long absences (≥4 hours); on relaunch it opens directly into the Record tab and auto-starts recording |
| **Session Tracking** | Every open/close is logged in the `sessions` DB table with duration, type, and version |
| **Live Waveform** | `WaveformView` scrolls amplitude bars in real time at 80 ms intervals |
| **Foreground Recording** | `RecordingService` continues recording even when the screen goes dark or you switch apps |
| **Lock Screen** | FAB in the Record tab dims and overlays the whole UI; only the Unlock button is tappable |
| **Tree View** | Swipe tile 0 — collapsible/expandable family tree of all categories and recordings |
| **Inbox Tile** | Swipe tile 1 — uncategorised recordings waiting to be sorted |
| **Playback Sheet** | Bottom sheet player with seekbar, play/pause, +/-15s skip, and resume-from-position |
| **Favourites & Tags** | Star any recording; search by title or comma-separated tags |
| **Rename / Move / Delete** | Long-press any recording for the options sheet |

---

## Architecture

```
TreeCast/
├── data/
│   ├── db/          AppDatabase (Room, 3 tables)
│   ├── entities/    SessionEntity · CategoryEntity · RecordingEntity
│   ├── dao/         SessionDao · CategoryDao · RecordingDao
│   └── repository/  TreeCastRepository · TreeBuilder · TreeNode
├── service/         RecordingService (foreground, MediaRecorder)
└── ui/
    ├── SplashActivity     (session gap check → route to MainActivity)
    ├── MainActivity        (2-tab ViewPager2, lock overlay dispatch)
    ├── MainViewModel       (shared state: tree, lock, search, sessions)
    ├── record/
    │   ├── RecordFragment  (waveform, timer, rec/pause/stop, lock FAB)
    │   └── WaveformView    (custom View — scrolling amplitude bars)
    ├── library/
    │   ├── LibraryFragment      (carousel ViewPager2)
    │   ├── LibraryTilesAdapter  (manages tiles)
    │   └── InboxTileFragment    (uncategorised recordings)
    └── tree/
        ├── TreeViewFragment     (tile 0 — collapsible tree)
        ├── TreeItemAdapter      (Node + Leaf ViewHolders with indentation)
        ├── PlaybackBottomSheet  (MediaPlayer, seekbar, resume)
        ├── RecordingOptionsSheet (rename/move/fav/delete)
        ├── RecordingsAdapter    (flat list for inbox etc.)
        └── Dialogs.kt           (NewCategoryDialog, RecordingOptionsSheet)
```

---

## Database Tables

### `sessions`
| Column | Type | Purpose |
|--------|------|---------|
| id | INTEGER PK | Auto-increment |
| opened_at | INTEGER | Epoch ms — app came to foreground |
| closed_at | INTEGER? | Epoch ms — app left foreground (null if active) |
| duration_ms | INTEGER? | closed_at − opened_at (convenience column) |
| session_type | TEXT | "IDLE" \| "RECORD" \| "PLAYBACK" |
| app_version | INTEGER | Build version code |

**Key queries:**
```sql
-- Time since last session
SELECT * FROM sessions ORDER BY opened_at DESC LIMIT 1;

-- All sessions from past 7 days
SELECT * FROM sessions WHERE opened_at >= (strftime('%s','now') - 604800) * 1000;

-- Total recording time ever
SELECT SUM(duration_ms) FROM sessions WHERE session_type = 'RECORD';
```

### `categories`
Self-referential tree via `parent_id` (nullable → root node). Cascade delete on parent removal.

### `recordings`
Belongs to a category (nullable = Inbox). Stores file path, duration, playback position, tags, favourite flag, and listened flag.

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
2. If this is your first launch (or you haven't opened the app in 4+ hours), the Record tab opens and recording begins automatically.
3. Tap **■ STOP & SAVE** to save the recording to your Inbox.
4. Switch to **Library** → swipe right from Tree View → **Inbox** to find it.
5. Long-press the recording → **Move to category** to organise it.

---

## Roadmap / TODO

- [ ] Category tile cards (tile 2+ per root category)  
- [ ] Drag-and-drop reordering in tree and within categories  
- [ ] Export recordings (share sheet)  
- [ ] Waveform scrubbing on playback seek bar  
- [ ] Tags UI (chip group)  
- [ ] Search bar in Library  
- [ ] Backup / restore via ZIP  
- [ ] Widget for one-tap record from home screen  
- [ ] Transcription (Whisper API integration)  

---

## Dependencies

| Library | Purpose |
|---------|---------|
| Room 2.6 | Local SQLite database with KSP codegen |
| ViewPager2 | Swipe-tile navigation in Library |
| MediaRecorder | Audio capture (M4A/AAC) |
| MediaPlayer | Playback with resume position |
| Material Components | Cards, FABs, BottomSheets, TabLayout |
| Kotlin Coroutines + Flow | Reactive data pipeline from DB to UI |
| MPAndroidChart | (Available for future waveform/stats charts) |

---

## License
MIT
