# Wristbeat

A rhythm game in the spirit of Rhythm Heaven: short one-button minigames with original music and
art. `HANDOFF.md` is the original design brief Jeremy wrote before implementation started — it's
still useful for the *why* (hard requirements, scaling rules, input mapping, backlog), but treat it
as historical intent, not current status. Some of it is already resolved (the architecture decision
it asks you to make with Jeremy has been made — see below) and some of it hasn't been built yet
(Android/Wear targets, a chart format/editor, Mango Chop). This file is the living status doc.

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
  - `Timing.kt` — beat/clock constants (`SECONDS_PER_BEAT`, judgment windows).
  - `Judgment.kt` — `Grade`, `ScoreTally`, `Rank` and the PERFECT/OK/miss math.
  - `Chart.kt` — `SoundId`, `ChartEvent`, and `Charts` (procedural chart generators, e.g.
    `snapCrabsBacking`).
  - `CalibrateStage.kt`, `SnapCrabsStage.kt` — per-stage state machines (`recordTap`,
    `updateMisses`, `tally`). Mango Chop doesn't have one yet — it's still a stub screen.
- **`composeApp/`** — the Compose UI and platform adapters.
  - `commonMain` — `App.kt` (stage switching), `CalibrateScreen.kt`, `SnapCrabsScreen.kt`,
    `Hud.kt` (shared HUD text/chip styling), `InputHandling.kt` (keyboard tap support), and
    `expect` declarations for `AudioClock`/`AudioEngine`/`HapticEngine`.
  - `wasmJsMain` — the `actual` implementations: `AudioClock` wraps `AudioContext`'s output
    timestamp, `AudioEngine` synthesizes every sound via Web Audio `js()` interop
    (oscillators/noise/filters — no audio files), `WebAudioContext.kt` holds the shared
    `AudioContext` + cached noise buffer, `main.kt` is the `CanvasBasedWindow` entry point.
  - `androidMain` — Android actuals shared by phone and watch. `HapticEngine` uses `Vibrator`;
    `AudioClock` is a monotonic system clock and `AudioEngine` is a silent no-op, both placeholders
    for the Oboe/AAudio-backed versions HANDOFF plans. `WristbeatAndroid.init(context)` supplies
    the `Context`, since the `expect` classes take no constructor args.
- **`androidApp/`**, **`wearApp/`** — thin `com.android.application` shells (`MainActivity` →
  `App()`). `wearApp` declares `android.hardware.type.watch` and is standalone; minSdk 30.

Timing is judged against the audio clock (`AudioClock.now()`), not frame time — taps are converted
into the same timebase before judging, per HANDOFF's timing-accuracy requirement.

## Current status (playable today, on web)

- **Calibrate** — full 36-beat click-track calibration with a progress ring, duration blurb,
  count-in distinction, live tap counter, and a timing strip. Runs first by default.
- **Snap Crabs** — fully playable: a lead crab snaps a call-and-response pattern, a note highway
  shows upcoming calls/targets sliding toward a hit line, backing band (kick/rim/hat/bass/uke +
  a melody line) plays underneath, and there's a live Perfect/OK/Miss tally with a results screen.
  The note highway (the "visual beat indicator chart") can be toggled off via the `Chart: On/Off`
  button under the stage label, for playing by ear alone.
- **Mango Chop** — stubbed (`enabled = false` in `App.kt`'s `Stage` enum), shows "coming in a later
  iteration."
- **Input** — pointer/touch taps everywhere, plus Space/J/F/Enter on the keyboard
  (`InputHandling.kt`'s `rememberTapKeyModifier`), matching HANDOFF's documented web input mapping.
- **UI conventions established through iteration** (deviating from these should be a deliberate
  choice, not an accident):
  - No app-level header/footer chrome. `App.kt` has no title bar; stage switching is a small tab
    strip floated in a corner over the game (`StageTabs`), not a page nav row.
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

## Build, test, run

- Unit tests (fast, no browser needed): `./gradlew :core:jvmTest`
- Compile check: `./gradlew :composeApp:compileKotlinWasmJs`
- Debug APKs: `./gradlew :androidApp:assembleDebug :wearApp:assembleDebug` (needs an Android SDK;
  `local.properties` with `sdk.dir` is gitignored).
- CI: `.github/workflows/android.yml` runs the core tests and builds both debug APKs on every push to
  `main`, then publishes a GitHub Release tagged `build-<run number>` with `wristbeat-android.apk`
  and `wristbeat-wear.apk` (stable link: `releases/latest/download/<name>.apk`). versionCode is the
  run number. CI signs with the `DEBUG_KEYSTORE_BASE64` repo secret when set so releases install
  over each other; without it each build gets a throwaway key. Local branch is `master`, tracking
  `origin/main`.
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

Real Android/Wear audio (Oboe synth + audio-output clock), any Wear-specific UI (round-screen
layout, Wear Compose), a chart format/editor (charts are currently hard-coded Kotlin, e.g.
`Charts.snapCrabsBacking`), Mango Chop, and per-device/per-audio-route calibration storage. None
of these are in progress — don't start them without Jeremy asking, per his stated plan to dial in
the web app first.
