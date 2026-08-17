# Time Tracker

Start and stop timers, run a pomodoro, edit past entries, and see where your time actually went. It runs two ways, chosen at setup:

- **Against a self-hosted [Kimai](https://www.kimai.org/) server** — the app is a front-end, every entry lives on your instance, and you never open the web UI.
- **On the device alone** — no server, no account, no network. Everything is stored in the app's own storage on the phone.

Either way nothing is sent anywhere else: there is no hosted account, no sign-up, and no server run by this project.

---

## Requirements

| | |
|---|---|
| **Android 8.0 (API 26) or newer** | Built and tested against Android 16 (API 36). |
| **A Kimai server you control** | *Server mode only.* Kimai 2.x, reachable from the phone. |
| **An API token** | *Server mode only.* Kimai → *User → API Access*. Legacy `X-AUTH` credentials also work for older servers. |
| **One project to track against** | *Server mode only*, chosen once during setup; all activities come from it. Local-only mode makes its own. |

In server mode the phone has to reach the server: same LAN, a VPN such as Tailscale, or a public HTTPS host. Cleartext HTTP is permitted by the app so a LAN address like `http://192.168.0.110:8000` works.

### Local-only mode

Switch on **Do not use server** during setup and the URL and token fields disappear — nothing else is needed. Activities are created in the app, and the whole history lives in one file in the app's private storage.

Three things are worth knowing before choosing it:

- **Nothing is backed up.** The data exists on that phone and nowhere else. Clearing the app's data or losing the phone takes the history with it.
- **There is no size limit.** Unlike the offline cache, the local store is never pruned.
- **It never merges with a server.** Local data is not uploaded to a server configured afterwards; switching to one starts the app over on the server's data, and the local file is simply left alone until you switch back.

Turning it on for an install that *had* a server keeps that server's cached data visible — the app carries on the way it does when the server is unreachable, except it no longer even tries to reach it. From then on, new entries are written locally.

---

## Setup

1. Clone the repo and point `local.properties` at your SDK:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```
2. Build and install:
   ```bash
   ./gradlew installDebug
   ```
3. Launch the app. On first run it opens straight into setup:
   - **Do not use server** — switch on to keep everything on the device; the fields below disappear and **Use this device only** finishes setup (see [Local-only mode](#local-only-mode))
   - **Server URL** — e.g. `http://192.168.0.110:8000`
   - **API token** — from Kimai under *User → API Access*
   - **Legacy auth** — switch on only for older Kimai servers that want a username + API password
   - **Theme** — pick an accent now or change it later (see [Theme](#theme))
   - Tap **Connect**, then choose a **customer** and **project**. Both are remembered.

Setup is reachable again at any time from the gear icon on the Timer screen; the theme picker lives in the same place. Applying a colour there closes it again — reconnecting is not part of changing your theme.

> **Build note:** the Android Gradle Plugin needs **JDK 17–21**. A newer JDK (Java 25, say) will fail. Point `JAVA_HOME` or `org.gradle.java.home` at a JDK 21 install if your system default is newer.

---

## Screens

The burger menu on the top left switches between six screens.

### Timer

The home screen: one big button.

- **Tap to start** — pick an activity from the project. **Tap again to stop.**
- **Two at once.** A second activity can run alongside the first; the earlier one keeps the big clock and the later one is listed under it. Stopping with two running asks which to stop.
- **Tags are remembered per activity.** The first time you start an activity it asks which tags to attach; every later start reuses them silently. Long-pressing a name in the picker re-opens that prompt.
- **Search** the picker to find an activity by name once the list gets long.
- **Create an activity** in-app with the **+** button — no need to go to the web UI.
- A running timer posts an **ongoing notification** that shows on the lock screen and ticks by itself, even if the app is killed. It disappears when the last timer stops.

### Pomodoro

A pomodoro session is **one unbroken entry** covering all of its work and break periods, not one entry per period.

- Start it the same way — the picker offers only activities tagged **`productive`**.
- The lengths (gear, top right) are **work**, **break**, **long break**, and **how many breaks before a long one** — 25 / 5 / 15 / 3 by default. Edits apply to the *next* session; one already running keeps the lengths it began with.
- While **working**, the screen is deliberately bare: static grey on black, no menu, no settings.
- While on a **break**, the stop button turns to the familiar pulsing red.
- **Skip** (the pill, top right) ends the current period there and then and starts the next one — work → break or break → work. The schedule moves with it, so everything after lands on the new rhythm.
- At every boundary the app **wakes the phone, puts itself on screen** — over the lock screen — and plays a short tone on the alarm stream, so a break still ends on time with the screen off and the app closed.
- Stopping writes the session's shape into the entry's **description**: how many work periods, how long, how many were cut short by skipping, and the lengths in force. Nothing about the phases is stored anywhere else.

### Visualisations

- **Pie** — share of time by **Activities** or by **Productivity** (tags), over a **day / week / month / year**. Arrows page back through previous periods, and a button in the bar returns to the present.
- **Tap a slice** to pull it out of the ring; the centre then names it and gives its time. **Tap it again** to open the Timesheet filtered to it over the period on screen — the same jump a legend row makes.
- The smallest activities are drawn as a single grey **Other** wedge, taking in as many of them as fit while the wedge itself stays within **5%** of the pie — so "Other" is a sliver, never a chunk — and only if at least **three** land in it. That is a drawing decision only: the legend below still lists every one of them, and tapping the wedge twice opens the Timesheet on exactly that set of activities.
- **Bar** — daily totals over the last 30 days.
- Colours come from the activity's own colour, falling back to a fixed accessible palette so an activity always keeps the same slot.

#### Parallel timers and the productivity score

Two timers can run at once, so time can be claimed twice over. The Productivity
pie won't have it: every instant belongs to **one** tag, the most productive one
running at the time.

- Precedence is **productive > semi-productive > unproductive > everything else**, untagged included.
- Only the **intersection** is contested. Where a lower-ranked entry runs alone, that stretch stays its own — one entry inside another simply disappears, one hanging off the end keeps its tail.
- Two entries sharing a tag can't double-count their overlap either; equal ranks are settled by whichever started first.
- **`life things` is exempt.** It isn't a judgement about productivity, so it neither takes time from a classified entry nor loses any to one — a walk logged over a work session leaves both intact. It is still de-duplicated against itself.

The score in the centre is the productive share of *classified* (tagged) time,
with semi-productive counted at half weight. The **Activities** pie is untouched
by all this: it reports each activity's own time, overlaps and all.

### Timesheet

Every entry, newest first.

- **Filter** by activity, by tag (including *untagged*), and by day/week/month/year or a custom range.
- **Tap to edit** — start, end (as a time or a duration), description, tags, and the activity's colour. With no server reachable, only queued entries can be changed, and only their times; see [the signal queue](#the-signal-queue).
- **Swipe left to delete**, which parks a Delete button open; tapping it asks for confirmation. Three deliberate steps, because deletions are not recoverable from the app.

### Calendar

A week-view grid of entries laid out against the clock.

- **Three days** in portrait, **seven** in landscape.
- A **red rule with an arrow at its left edge** crosses today's column at the current time, and the grid opens scrolled to it — the line an hour or so below the top edge. With today off screen it opens at **07:00** instead, so the small hours are a scroll away.
- Arrows page day by day, and a button returns to today.

### Tools

- **Frequency** — for one activity over a date range: how many sessions, how much time, and how both work out per day/week/month/year, plus a full-year projection of the time. Rates divide by the **whole range**, empty days included, so they read as a rate rather than an intensity: a fortnight off pulls the average down. The number of days that did have sessions is reported alongside, as context.
  - A period the range is **too short to average** — a monthly figure from a fortnight, a yearly one from anything under a year — reports the **time logged so far** instead, marked as such, rather than multiplying a small window up into a number that isn't true.
  - Long spans are written in **days, hours and minutes**: twelve and a half days is a length of time in a way that 300 hours isn't.
  - Under **30 days of data** — counted from the activity's first session in the range, since anything earlier is a period we know nothing about — the result carries a note saying the rates may be well off. Ranges with no sessions at all say so outright.
- **Batch edit** — narrow entries down by activity, tag, duration and date range, then apply one action to all of them: rename the activity, move them to another activity, set tags, set a colour, or delete. Deletion asks twice.
- **Import/Export** — a timesheet out as a file, and a file back in. Server-backed installs only.
  - **Export**: a date range as **CSV**, **Excel**, **PDF**, or straight to the **system print dialog**. Files land in **Downloads**; printing keeps no file.
  - The files are written **on the device** from the entries the app holds, not fetched from Kimai. Kimai's own export is a page of its web dashboard, reachable only with a browser login session — an API token cannot get at it — so the columns here match what the app knows (date, times, duration, decimal hours, customer, project, activity, description, tags) and the layout is this app's.
  - Anything still sitting in [the signal queue](#the-signal-queue) is included, with a warning saying so: until it is sent, the export won't match what Kimai holds.
  - **Import**, at the foot of the same page: pick a **CSV or Excel** file — one this app exported, or anything carrying the same **Date, Begin, End and Activity** columns — and what's missing is added to the server. The reader is forgiving about what a spreadsheet does to a file on the way past: shared string tables, dates and times rewritten as serial numbers, semicolon separators, and columns it doesn't need are all fine.
  - It only ever **adds**. An entry the server already holds is counted and left alone — the same activity begun at the same minute is the same entry, which is the only identity a timesheet has once ids are gone — so importing the same file twice changes nothing. **Activities are matched by name without regard to case** and created only when nothing of that name exists, hidden ones included, so an export that has been through a spreadsheet can't grow a second *Reading*.
  - Rows still running (no end time) are **skipped**: a timer belongs to the device it's running on. Anything Kimai refuses — an entry overlapping a running timer, say — is counted separately rather than costing you the rest of the file. Everything that happened is reported: added, already there, skipped, refused.
  - Import needs the server. The offline queue carries starts and stops, and a file's worth of history has no business being replayed as either.
- **Storage** — how much the offline cache is holding, or, local-only, how big the database itself has grown.

---

## Theme

Everything accented in the app is drawn from a single colour, chosen in setup:

- **Green, Red, Blue** presets, or
- **hue, saturation and brightness** sliders for anything else. Each track is painted in the colours it would produce — the hue strip is a rainbow, the brightness strip runs from black to the colour at full light — so the sliders show the choice rather than describing it.

**Nothing is applied until you press *Use this colour*.** Tapping a preset only moves the sliders onto it; pressing the button applies the colour *and closes settings*, returning you to where you were rather than through the connection flow. Backing out with the system gesture leaves without changing anything.

A colour too close to the near-black background is **refused**, not applied: the candidate must clear a **2:1 contrast ratio** against both the background and the surface used for dialogs. The current ratio is shown live above the button.

The **stop button and running-timer red are deliberately exempt** — a live timer has to read the same whatever accent is set.

> The launcher icon is a static resource per hue, switched to the nearest one. Android tears down the app's task when that switch happens, so it is deferred until the app is next put in the background — where the same work is invisible.

> The play glyph inside the icon is sized to come out at exactly the size of the button on the Timer screen when Android draws it on the launch screen, so opening the app doesn't change the size of the thing you were just looking at. Android fixes the launch-screen circle at 160dp against the button's 220dp, so the two circles differ; the glyphs don't.

---

## Offline behaviour

*Server mode only.* Timesheet, calendar, visualisation and tool data is cached on the device after each successful load — as are the timers that were running the last time the server answered. When the server can't be reached, those screens render the cached copy behind an amber banner saying how old it is and why.

**Deciding the server is gone takes about four seconds.** The connection timeout is short on purpose: every screen waits on that verdict, the offline path behind it is ready to take over, and a Kimai on the same network answers in milliseconds. Once a request *has* been answered it is given far longer — a slow answer is still an answer. When a queued signal has just failed to reach the server, the refresh behind it doesn't ask again; the answer is already in.

**A timer that has been stopped stays stopped.** The running timer is saved without an end — that is what makes it the running one — so its end is written into the saved copy at the moment the timer stops, whether that stop reached the server or went to the queue. Otherwise the next load with no server puts the timer back on screen and counts it up again. A timer stopped somewhere else entirely (Kimai's web dashboard, another device) is caught on the next successful load: the server names every entry that really is running, and saved entries that disagree are re-read from it — or, if they're more than a month old, simply dropped.

### The signal queue

**Timing does not stop when the server does.** Two actions — and only two — are written to a queue on the device and replayed the next time the server answers:

1. **Stopping** a running activity, at the moment you stopped it. It is remembered with that instant, not replayed later as "stop it now", so the entry lands with the length it really had.
2. **Starting** one (or two) activities, chosen from the list already on the device. The same ceiling of two concurrent timers applies, so what the queue hands over is something the server would have accepted at the time.

A queued entry is a real entry everywhere it matters: it shows on the **timesheet** marked **QUEUED** in amber, and it counts in the **visualisations** and the **frequency calculator** exactly as a server entry does. The Timer screen says how many changes are waiting.

Stops are sent before starts, since freeing a running entry is what makes room for a queued start under Kimai's limit. A server that is merely still absent leaves the queue untouched; anything the server actively refuses is surfaced, because such a signal will never leave on its own.

**Everything else is unchanged and still needs the server.** Batch edit, and editing or deleting entries the server already knows about, behave exactly as they did — which offline means they fail. The two exceptions concern queued entries only:

- a queued entry's **times can be changed**, and it can be **deleted** — deleting it is the whole of the deletion, since the server was never told about it;
- **no tags, colours or activity names can be edited while offline**, on any entry, queued or not. Those belong to the server, and a queued entry has none of its own to change yet.

A pomodoro started offline works the same way: the session is tracked against a queued entry, and the period breakdown written when you stop it rides along with it.

Local-only mode has nothing to be offline from, and nothing to queue: every screen reads the device, the banner never appears.

---

## Tech stack

- Kotlin + Jetpack Compose (Material 3), `compileSdk`/`targetSdk` **36**, `minSdk` 26
- MVVM — a single `AndroidViewModel` with `StateFlow` per screen
- Retrofit + Moshi + OkHttp against the Kimai REST API
- `AlarmManager.setAlarmClock` + a full-screen-intent notification for pomodoro boundaries
- `SharedPreferences` for settings; JSON files for the offline cache and the local-only database
- Local-only mode is the same `KimaiApi` interface implemented against a file, so no screen knows the difference

## Project structure

```
app/src/main/java/com/fizaan/timetracker/
├── MainActivity.kt        # Compose entry point, drawer, theme host
├── MainViewModel.kt       # All screen state and API orchestration
├── RunningNotifier.kt     # The lock-screen face of a running timer
├── data/
│   ├── KimaiApi.kt        # Retrofit interface + client provider
│   ├── AuthInterceptor.kt # Bearer / legacy auth headers
│   ├── Models.kt          # API data classes
│   ├── Prefs.kt           # SharedPreferences wrapper
│   ├── TimesheetCache.kt  # Offline snapshot of the last good load
│   ├── PendingQueue.kt    # Signals taken with no server, and their replay
│   ├── LocalStore.kt      # The database of a local-only install
│   └── LocalApi.kt        # KimaiApi implemented against LocalStore
├── export/
│   ├── ExportModel.kt     # Formats, columns, entries flattened to rows (pure)
│   ├── Writers.kt         # CSV and hand-written xlsx (pure)
│   ├── Readers.kt         # CSV and xlsx read back to a grid of cells (pure)
│   ├── Importer.kt        # Grid → entries, and what an import would do (pure)
│   ├── PdfWriter.kt       # Paginated A4 table
│   └── ExportStore.kt     # Saving to Downloads via MediaStore
├── pomodoro/
│   ├── Pomodoro.kt        # Phase arithmetic and session summary (pure)
│   └── PomodoroAlarm.kt   # Boundary alarm, receiver, full-screen alert
├── ui/
│   ├── MainScreen.kt      # Timer, activity picker, tag prompt
│   ├── PomodoroScreen.kt  # Pomodoro face and settings
│   ├── VizScreen.kt       # Pie and bar charts
│   ├── SheetScreen.kt     # Timesheet list, edit and delete
│   ├── CalendarScreen.kt  # Week-view grid
│   ├── ToolsScreen.kt     # Frequency tool, storage
│   ├── BatchTool.kt       # Batch edit
│   ├── ImportExportTool.kt # Export, the print handoff, and the file picker
│   ├── SetupScreen.kt     # Credentials, project, theme
│   ├── Theme.kt           # Accent, contrast rules, colour scheme
│   └── VizColors.kt       # Chart palette
└── util/
    ├── Time.kt            # Kimai timestamp parsing/formatting
    └── Overlap.kt         # Resolving parallel entries onto one timeline (pure)
```

`app/src/test/` holds JVM unit tests for the pure logic — currently the overlap
rules, the queue's merge arithmetic, the export writers (the xlsx is written by
hand, so it is worth pinning down) and the import readers, which are checked by
round-tripping the writers' own output back through them. Run them with
`./gradlew test`.

## Notes and limitations

- **Single project.** Everything is scoped to the one project chosen at setup (local-only mode has exactly one).
- **Two timers at once**, in both modes — the local store enforces the same ceiling Kimai does. Where they overlap, the Productivity pie credits only one of them; see above.
- **Kimai rounds to the minute** (begin down, end up), so a summary's elapsed total can differ from an entry's stored duration by up to a minute — including when a queued signal is finally sent.
- **A queued stop needs the entry's own start**, which the app only has for timers it saw running. One started on another device during an outage can't be stopped from this one until it reconnects.
- **An import can't be undone in one step.** Nothing is overwritten and duplicates are refused, but if the wrong file goes in, the entries it added have to be removed — batch edit by activity and date range is the quickest way.
- **Exports are the app's own files, not Kimai's.** Kimai only exports from its web dashboard, behind a browser login the API token can't reach, so nothing produced here will be byte-for-byte what the dashboard gives you. Rates and billing, which the app never sees, are not in the columns.
- The app is **dark only** — the system light/dark setting is ignored.
- Auto Backup is on, which means the API token can be included in a Google account backup. Turn `android:allowBackup` off in the manifest if that matters to you.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
