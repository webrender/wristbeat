# Wristbeat

A rhythm game in the spirit of Rhythm Heaven: short one-button minigames with original music and
art. `HANDOFF.md` is the original design brief Jeremy wrote before implementation started — it's
still useful for the *why* (hard requirements, scaling rules, input mapping, backlog), but treat it
as historical intent, not current status. Some of it is already resolved (the architecture decision
it asks you to make with Jeremy has been made — see below) and some of it hasn't been built yet
(a chart format/editor). This file is the living status doc.

`wristbeat-prototype.html` is the original single-file HTML/Canvas/Web Audio prototype Jeremy built
to try out the feel. It's a useful reference for art and sound design (e.g. `drawCrab`, `SND.bass`)
but isn't part of the shipping app.

## Architecture

Kotlin Multiplatform + Compose Multiplatform, chosen so one codebase covers web, Android, and Wear
OS. The web (`wasmJs`) target is the one that's built out. Android and Wear OS are scaffolded (they
build and launch the shared `App()`), but their platform actuals are placeholders.

- **`core/`** — pure Kotlin, no UI framework, unit-tested (`core/src/commonTest`). Chart data,
  timing/judgment math, and the stage state machines live here so they can be shared and tested
  without a UI.
  - `Timing.kt` — beat/clock constants (`SECONDS_PER_BEAT` for the shared 116 BPM, `MANGO_CHOP_BPM`).
    Charts are authored in beats; each stage exposes its own seconds-per-beat where it differs.
  - `Judgment.kt` — `Grade`, `ScoreTally`, `Rank` and the PERFECT/OK/miss math.
  - `Chart.kt` — `SoundId`, `ChartEvent`, and `Charts` (procedural chart generators:
    `snapCrabsBacking`, the prototype's ukulele band, and `mangoChopBacking`, Mango Chop's own soca
    song in A minor with keyboard stabs, shaker and a steel pan tune).
  - `CalibrateStage.kt`, `SnapCrabsStage.kt`, `MangoChopStage.kt` — per-stage state machines
    (`recordTap`/`recordAction`, `updateMisses`, `tally`, and `perceivedBeat`, which the screens draw
    at so visuals follow the calibrated offset).
- **`composeApp/`** — the Compose UI and platform adapters.
  - `commonMain` — `App.kt` (stage switching), `CalibrateScreen.kt`, `SnapCrabsScreen.kt`,
    `MangoChopScreen.kt`,
    `Hud.kt` (shared HUD text/chip styling), `StageHud.kt` (each stage's top HUD — title, chart
    toggle, legend — laid out around the stage tabs), `NoteHighway.kt` (the shared note highway), `InputHandling.kt` (keyboard tap support),
    `Calibration.kt` (the calibration offset, saved per audio output — see **Calibration** below), and
    `expect` declarations for `AudioClock`/`AudioEngine`/`HapticEngine`.
  - `wasmJsMain` — the `actual` implementations: `AudioClock` wraps `AudioContext`'s output
    timestamp, `AudioEngine` synthesizes every sound via Web Audio `js()` interop
    (oscillators/noise/filters — no audio files), `WebAudioContext.kt` holds the shared
    `AudioContext` + cached noise buffer, `main.kt` is the `CanvasBasedWindow` entry point.
  - `androidMain` — Android actuals shared by phone and watch. `HapticEngine` uses `Vibrator`.
    `AndroidAudio.kt` owns one low-latency float `AudioTrack` fed by a render thread that mixes
    `SynthVoice.kt`'s software voices (ports of the Web Audio osc/noise/bass graphs, same
    parameters); `AudioEngine` maps `SoundId`s onto those voices and `AudioClock` reads the stream's
    `AudioTrack.getTimestamp()` output position, so both share one timebase like `WebAudioContext`
    on web. Pure Kotlin/JVM — no NDK/Oboe yet. The stream pauses (and the clock holds) while the
    app is backgrounded. `WristbeatAndroid.init(context)` supplies the `Context` and registers that
    lifecycle hook, since the `expect` classes take no constructor args.
- **`androidApp/`**, **`wearApp/`** — thin `com.android.application` shells. `androidApp`'s
  `MainActivity` → `App()`. `wearApp` declares `android.hardware.type.watch` and is standalone
  (minSdk 30); its `MainActivity` → `WearApp()` (see **Watch UI** below).

Timing is judged against the audio clock (`AudioClock.now()`), not frame time — taps are converted
into the same timebase before judging, per HANDOFF's timing-accuracy requirement.

## Current status (playable today, on web)

- **Calibrate** — 20-beat click-track calibration (4-beat count-in, then 16 taps) with a progress ring, duration blurb,
  count-in distinction, live tap counter, and a timing strip. Runs first by default.
- **Snap Crabs** — fully playable: a lead crab snaps a call-and-response pattern, a note highway
  shows upcoming calls/targets sliding toward a hit line, backing band (kick/rim/hat/bass/uke +
  a melody line) plays underneath, and there's a live Perfect/OK/Miss tally with a results screen.
  The note highway (the "visual beat indicator chart") can be toggled off via the `Chart: On/Off`
  button under the stage label, for playing by ear alone. That setting is app-wide (held in
  `App.kt`), so it applies to every stage with a highway.
- **Mango Chop** — playable at 140 BPM (faster than the other stages' 116, since it's the harder
  level), with its own song (`Charts.mangoChopBacking`) rather than Snap Crabs' band. A whistle marks each toss: mangoes land 2 beats later and limes 1 beat later, and
  both are chopped with a tap. Pineapples (introduced in the third section, tossed from the right
  with a falling double whistle) land 2 beats later and need a **swipe** to slice. The wrong action
  is a stray: it consumes nothing, and the fruit bounces off as a miss if it isn't corrected in time.
  Its note highway shows each whistle on the top row and each landing on the bottom row (pineapples
  as slanted swipe arrows, which the legend repeats), and hides with the same chart toggle.
- **Input** — pointer/touch taps everywhere, plus Space/J/F/Enter on the keyboard
  (`InputHandling.kt`'s `rememberTapKeyModifier`), matching HANDOFF's documented web input mapping.
  Taps are judged when the finger **lands** (`detectTapGestures(onPress = …)`), not on release, so
  Calibrate's offset doesn't include how long a tap is held and applies to every stage.
  Mango Chop's swipe: on touch (and mouse drag) a swipe fires when the pointer moves 24dp. To avoid
  delaying chops, a press chops immediately when the nearest open fruit wants a chop. When a
  pineapple is nearest, the press waits: it slices if it becomes a swipe, and otherwise chops on
  release, judged at the moment the finger landed. On the keyboard, D/K/arrow keys slice.
- **Calibration** — Calibrate's offset is saved per audio output (`Calibration.kt`): Android keys it
  by the routed device (the `AudioTrack`'s routed device, or a Bluetooth > wired > speaker guess
  before the stream starts) in `SharedPreferences`; the web can't see the output device, so it keeps
  one offset in `localStorage`. The output is re-checked every second and at the start of each run,
  so connecting a Bluetooth headset switches to its offset. Besides shifting judgment, the offset
  also delays the visuals (`perceivedBeat`), so on Bluetooth the highway and animations wait for the
  sound instead of running ahead of it. Calibrate's own dial runs on the raw clock, so its copy asks
  the player to tap to the sound.
- **UI conventions established through iteration** (deviating from these should be a deliberate
  choice, not an accident):
  - No app-level header/footer chrome. `App.kt` has no title bar; stage switching is a small tab
    strip floated in a corner over the game (`StageTabs`), not a page nav row.
  - Top HUD layout: stages put their title/chart toggle/legend in `StageHeader`, never positioned by
    hand. Wide screens stack it in the top-left column with the tabs top-right; below 720dp
    (`COMPACT_HUD_MAX_WIDTH`) the tabs switch to short labels centered on the top edge, and the
    header drops its title and flows centered underneath, so nothing overlaps on a phone. Both
    respect `WindowInsets.safeDrawing` (display cutouts on Android).
  - Each stage screen is a single full-bleed `Canvas(Modifier.fillMaxSize())` — status text,
    counters, and legends are HUD overlays positioned with `Box` + `Alignment`, not stacked above
    or below the canvas in a `Column`.
  - Desktop/tablet scaling: compute `squareExtent = minOf(size.width, size.height)` and center a
    square stage in the full canvas; background art bleeds to the full canvas size while
    interactive elements stay confined to the centered square. This directly implements HANDOFF's
    "keep the stage centered at the largest size that fits, extend background art in the margins"
    scaling rule — don't reintroduce a small watch-shaped clipped circle on desktop.
  - Text goes through `Hud.kt`'s `HudText`/`HudChip`, not plain Material `Text`, so it reads as
    game HUD copy: shadowed, letter-spaced, all-caps, and set in **Sniglet** (OFL-licensed, see
    `THIRD_PARTY_LICENSES/sniglet-OFL.txt`), a bubbly rounded display face loaded via Compose
    Multiplatform's resource system (`composeApp/src/commonMain/composeResources/font/`) — real
    ExtraBold/Regular font files, not synthetic bold on a system font. Every clickable control and
    HUD backdrop (tabs, toggles, chips) goes through `Hud.kt`'s `GameButton`: a small-radius
    rounded-rect panel with a color bevel, glassy top sheen, bright rim, and drop shadow, standing
    in for a "console UI" look. Don't reach for a bare `RoundedCornerShape(50)` pill/stadium
    shape or plain `Modifier.background()` chip — those read as web chips, not game UI, which is
    exactly what this replaced.

- **Watch UI** (`wearApp/.../WearApp.kt`) — the phone/web tabs and top HUD took up the whole
  watch face, so the watch has its own shell built on Wear Compose Material 1.4 (matches Compose
  1.7; Wear Material 3 would need a newer Compose). A native menu (`ScalingLazyColumn` of `Chip`s,
  `TimeText`, and a `ToggleChip` for the chart) picks a stage, with the saved calibration offset
  for the current output under Calibrate. Each stage opens full screen via `SwipeDismissableNavHost`; swiping right returns to the
  menu, except mid-run (swipe-to-dismiss is disabled while a run is in progress, since a sloppy tap
  or a Mango Chop slice would otherwise quit). The screen stays on during a run. Stages render
  through `App.kt`'s `WatchStageScreen`, which sets `HudLayout(watch = true)`: `StageHeader` draws
  nothing, HUD text and chips shrink, and status panels drop to the lines that fit on a round face
  (`tallyLine`, `statusBottomPadding`). The start instructions and in-run prompt fade out 3s after
  they appear (`WatchAutoHide`); results stay up, and so does Calibrate's timing strip. Stage screens report runs via `ReportRunning`.

## Build, test, run

- Unit tests (fast, no browser needed): `./gradlew :core:jvmTest`
- Compile check: `./gradlew :composeApp:compileKotlinWasmJs`
- APKs: `./gradlew :androidApp:assembleRelease :wearApp:assembleRelease` (or `assembleDebug`; needs an Android SDK;
  `local.properties` with `sdk.dir` is gitignored).
- CI: `.github/workflows/android.yml` runs the core tests and builds both release APKs (R8-optimized, signed with the debug key) on every push to
  `main`, then publishes a GitHub Release tagged `build-<run number>` with `wristbeat-android.apk`
  and `wristbeat-wear.apk` (stable link: `releases/latest/download/<name>.apk`). versionCode is the
  run number. CI signs with the `DEBUG_KEYSTORE_BASE64` repo secret when set so releases install
  over each other; without it each build gets a throwaway key. Local branch is `master`, tracking
  `origin/main`.
- Web: `.github/workflows/pages.yml` builds the production web bundle
  (`./gradlew :composeApp:wasmJsBrowserDistribution` → `composeApp/build/dist/wasmJs/productionExecutable`)
  on every push to `main` and deploys it to GitHub Pages at `https://webrender.github.io/wristbeat/`
  (needs the repo's Settings → Pages source set to "GitHub Actions").
- Dev server: `./gradlew :composeApp:wasmJsBrowserDevelopmentRun` → serves at `http://localhost:8080`

**The dev server does not hot-reload.** It's a `webpack-dev-server` in front of a compiled Wasm
bundle, not a file watcher — editing source and reloading the browser will *not* pick up changes.
After every source edit, kill it and relaunch:

```bash
ps aux | grep -E "wasmJsBrowserDevelopmentRun|webpack" | grep -v grep   # find the launcher + webpack pids
kill <pids>
nohup ./gradlew :composeApp:wasmJsBrowserDevelopmentRun --no-daemon > devserver.log 2>&1 &
disown
# then poll until it's back up:
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code}" http://localhost:8080 | grep -q 200 && echo UP && break; sleep 3; done
```

`devserver.log` is gitignored — it's scratch output from the command above, not a tracked artifact.

There's no browser automation available in this environment — visual verification of Compose
Canvas output has come entirely from Jeremy's own screenshots and feedback, not from an independent
screenshot check. Say so explicitly rather than claiming a visual change looks right sight unseen.

## Not started yet

An Oboe/AAudio (native) audio path — Android audio currently runs on a Java `AudioTrack`, which
works but may have more output latency on some devices — any Wear-specific UI (round-screen
layout beyond the menu and HUD trimming above, e.g. stage art tuned for a round face), and a chart format/editor (charts are currently hard-coded Kotlin, e.g.
`Charts.snapCrabsBacking`). None
of these are in progress — don't start them without Jeremy asking, per his stated plan to dial in
the web app first.
