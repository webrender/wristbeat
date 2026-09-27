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
  - `Timing.kt` — beat/clock constants (`SECONDS_PER_BEAT` for the shared 116 BPM, `MANGO_CHOP_BPM`,
    `BONGO_BLITZ_BPM`). Charts are authored in beats; each stage exposes its own seconds-per-beat
    where it differs.
  - `Judgment.kt` — `Grade`, `ScoreTally`, `Rank` and the PERFECT/OK/miss math.
  - `Chart.kt` — `SoundId`, `ChartEvent`, and `Charts` (procedural chart generators:
    `snapCrabsBacking`, the prototype's ukulele band; `mangoChopBacking`, Mango Chop's own soca
    song in A minor with keyboard stabs, shaker and a steel pan tune; and `bongoBlitzBacking`, a lean
    jungle percussion groove that stays out of the way of Bongo Blitz's calls). Each song has a real
    form, laid out in beats by `SnapCrabsSong`/`MangoChopSong`/`BongoBlitzSong`: a musical intro whose
    last bar is the stick count-in, verse/chorus sections (plus a bridge with its own progression in
    Mango Chop and Bongo Blitz) marked by crashes and tom/rim fills, and an outro that lands on a
    final chord and rings out before the results. Stages place their gameplay on those sections
    (Snap Crabs' easy patterns in the verse, harder ones in the chorus; Mango Chop's pineapples and
    Bongo Blitz's low-drum swipes both arrive with the bridge).
  - `CalibrateStage.kt`, `SnapCrabsStage.kt`, `MangoChopStage.kt`, `BongoBlitzStage.kt` — per-stage
    state machines (`recordTap`/`recordAction`, `updateMisses`, `tally`, and `perceivedBeat`, which
    the screens draw at so visuals follow the calibrated offset).
- **`composeApp/`** — the Compose UI and platform adapters.
  - `commonMain` — `App.kt` (the home screen vs. stage switch, and the `StageBackHandler` expect
    declaration for the back gesture that returns to the menu),
    `MainMenu.kt` (the phone/web home screen: pick a stage, toggle the chart, see the calibration
    offset — the mobile/web counterpart to `wearApp`'s native menu), `CalibrateScreen.kt`,
    `SnapCrabsScreen.kt`, `MangoChopScreen.kt`, `BongoBlitzScreen.kt`,
    `Hud.kt` (shared HUD text/chip styling), `StageHud.kt` (shared HUD bits: the chart toggle,
    `ReportRunning`), `Results.kt` (`StageResults`, the fullscreen wipe-and-count-up screen a run
    transitions into when it ends, playing a cheer/boo sound), `NoteHighway.kt` (the shared note
    highway), `InputHandling.kt` (keyboard tap support),
    `Calibration.kt` (the calibration offset, saved per audio output — see **Calibration** below), and
    `expect` declarations for `AudioClock`/`AudioEngine`/`HapticEngine`.
  - `wasmJsMain` — the `actual` implementations: `AudioClock` wraps `AudioContext`'s output
    timestamp, `AudioEngine` synthesizes every sound via Web Audio `js()` interop
    (oscillators/noise/filters — no audio files), `WebAudioContext.kt` holds the shared
    `AudioContext` + cached noise buffer, `main.kt` is the `CanvasBasedWindow` entry point.
    `BackNavigation.wasmJs.kt`'s `StageBackHandler` is a no-op — the browser's back gesture is
    Escape, handled directly in `App.kt` instead.
  - `androidMain` — Android actuals shared by phone and watch. `HapticEngine` uses `Vibrator`.
    `AndroidAudio.kt` owns one low-latency float `AudioTrack` fed by a render thread that mixes
    `SynthVoice.kt`'s software voices (ports of the Web Audio osc/noise/bass graphs, same
    parameters); `AudioEngine` maps `SoundId`s onto those voices and `AudioClock` reads the stream's
    `AudioTrack.getTimestamp()` output position, so both share one timebase like `WebAudioContext`
    on web. Pure Kotlin/JVM — no NDK/Oboe yet. The stream pauses (and the clock holds) while the
    app is backgrounded. `WristbeatAndroid.init(context)` supplies the `Context` and registers that
    lifecycle hook, since the `expect` classes take no constructor args.
    `BackNavigation.android.kt`'s `StageBackHandler` wraps `androidx.activity.compose.BackHandler`
    (an `androidMain`-only dependency on `activity-compose`, since `composeApp`'s `commonMain`
    doesn't have it) for the phone's system back gesture/button.
- **`androidApp/`**, **`wearApp/`** — thin `com.android.application` shells. `androidApp`'s
  `MainActivity` → `App()`. `wearApp` declares `android.hardware.type.watch` and is standalone
  (minSdk 30); its `MainActivity` → `WearApp()` (see **Watch UI** below).

Timing is judged against the audio clock (`AudioClock.now()`), not frame time — taps are converted
into the same timebase before judging, per HANDOFF's timing-accuracy requirement.

## Current status (playable today, on web)

- **Main menu** (`MainMenu.kt`) — the app opens on a menu, not straight into a stage: pick Calibrate,
  Snap Crabs, Mango Chop or Bongo Blitz, or toggle the note highway ("chart") on/off, mirroring the native menu
  `wearApp` already had on the watch. A stage is full-bleed game with no title, tap/swipe
  instructions, or top HUD chrome of its own while it's being played (a tutorial stage will cover
  that explanation later), and its song starts automatically the moment it opens — no tap needed.
  Getting back to the menu is a gesture, not a button (`App.kt`'s `StageBackHandler`): Escape on the
  browser, the system back gesture/button on Android, swipe-to-dismiss on the watch (already native
  to `wearApp`'s nav host) — and only while nothing is running, so there's nothing to accidentally
  hit mid-run. Selecting a stage also unlocks the browser's `AudioContext` synchronously inside that
  click, since the stage's own auto-start happens a frame later in a `LaunchedEffect`, too late for
  autoplay policies to allow it on its own.
- **Calibrate** — 20-beat click-track calibration (4-beat count-in, then 16 taps) with a progress
  ring and a live timing strip while a run is in progress.
- **Snap Crabs** — fully playable: a lead crab snaps a call-and-response pattern, a note highway
  shows the upcoming response targets sliding toward a hit line, and backing band (kick/rim/hat/bass/uke +
  a melody line) plays underneath. The note highway (the "visual beat indicator chart") toggles from
  the main menu, for playing by ear alone; that setting is app-wide (held in `App.kt`), so it applies
  to every stage with a highway.
- **Mango Chop** — playable at 140 BPM (faster than the other stages' 116, since it's the harder
  level), with its own song (`Charts.mangoChopBacking`) rather than Snap Crabs' band. A whistle marks each toss: mangoes land 2 beats later and limes 1 beat later, and
  both are chopped with a tap. Pineapples (introduced in the third section, tossed from the right
  with a falling double whistle) land 2 beats later and need a **swipe** to slice. The wrong action
  is a stray: it consumes nothing, and the fruit bounces off as a miss if it isn't corrected in time.
  Its note highway shows a single row of each landing (the beat to act on; pineapples as slanted
  swipe arrows), and hides with the same chart toggle.
- **Bongo Blitz** — the hardest stage, at 172 BPM (`Charts.bongoBlitzBacking`, its own lean jungle
  percussion groove). A monkey claps a call on its high (tap) or low (swipe) drum, and the player
  repeats it exactly — both rhythm and which drum — one bar later: Snap Crabs' call-and-response
  memory task, layered with Mango Chop's tap/swipe discrimination, at a faster tempo and with longer,
  more syncopated eighth-note tap runs than either other stage. The verse is tap-only and the chorus
  brings in the low drum; both it and the bridge cap every pattern at one swipe, always at least a
  full beat from the note before and after it, since a swipe (drag clear of the tap distance, then
  lift and re-touch) takes real time a tap doesn't — a rule `BongoBlitzStageTest` checks directly. The
  wrong drum is a stray, same as Mango Chop's wrong action: it consumes nothing and can be corrected
  inside the window. Its note highway shows the upcoming response beats (dots for the high drum,
  swipe arrows for the low one), and hides with the same chart toggle.
- **Results** (`Results.kt`) — Snap Crabs, Mango Chop and Bongo Blitz no longer show a live Perfect/OK/Miss tally
  or rank during play; instead, once the song ends, an accent-colored panel wipes fully across the
  screen (`StageResults`'s `wipe` `Animatable`, sliding off to reveal what's behind it), replacing
  the frozen game with an opaque fullscreen backdrop — a radial gradient plus a slow-turning
  `drawSunburst` ring — so nothing of the game shows through. A big rank/headline and an even bigger
  hero number (the score, as a percentage) bounce in (`bounce`, a spring) and count up from zero
  (`count`, a tween driving `heroValue * count.value`), with the Perfect/OK/Miss stats counting up
  alongside them; "Try again" and "Menu" fade in last. The transition plays a sound once, picked by
  `tally.rank`: a cheer (`SoundId.CHEER`) for a pass (OK or Superb), a sad-trombone boo
  (`SoundId.BOO`) for `Rank.TRY_AGAIN`. Calibrate reuses the same `StageResults` shell for its
  offset readout (counting up to the measured `ms`), but plays no sound — it isn't scored pass/fail.
  `onMenu` is only wired up on phone/web (`App.kt`); the watch leaves it null and relies on
  swipe-to-dismiss instead, so its results screen shows only "Try again".
- **Input** — pointer/touch taps everywhere, plus Space/J/F/Enter on the keyboard
  (`InputHandling.kt`'s `rememberTapKeyModifier`), matching HANDOFF's documented web input mapping.
  Taps are judged when the finger **lands** (`detectTapGestures(onPress = …)`), not on release, so
  Calibrate's offset doesn't include how long a tap is held and applies to every stage.
  Mango Chop's swipe: on touch (and mouse drag) a swipe fires when the pointer moves 24dp. To avoid
  delaying chops, a press chops immediately when the nearest open fruit wants a chop. When a
  pineapple is nearest, the press waits: it slices if it becomes a swipe, and otherwise chops on
  release, judged at the moment the finger landed. On the keyboard, D/K/arrow keys slice. Bongo
  Blitz's tap/swipe (hitting the high or low drum) follows the same rule, keyed off its own
  `expectedAction` instead of Mango Chop's nearest-open-fruit lookup.
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
  - No app-level header/footer chrome, and no per-stage chrome either: stage switching, the chart
    toggle and the calibration readout live in `MainMenu.kt`, not in the stage itself, and getting
    back to the menu is a gesture (Escape/system back/swipe-to-dismiss — see **Main menu** above),
    not a floating button.
  - Each stage screen is a single full-bleed `Canvas(Modifier.fillMaxSize())` — the only overlays
    are live feedback on the run itself (Calibrate's timing strip) and `Results.kt`'s `StageResults`
    screen once it ends, positioned with `Box` + `Alignment`, not stacked above or below the canvas
    in a `Column`.
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

- **Watch UI** (`wearApp/.../WearApp.kt`) — a round watch face can't fit the phone/web `MainMenu`
  either, so the watch has its own shell built on Wear Compose Material 1.4 (matches Compose 1.7;
  Wear Material 3 would need a newer Compose). A native menu (`ScalingLazyColumn` of `Chip`s,
  `TimeText`, and a `ToggleChip` for the chart) picks a stage, with the saved calibration offset
  for the current output under Calibrate. Each stage opens full screen via `SwipeDismissableNavHost`; swiping right returns to the
  menu, except mid-run (swipe-to-dismiss is disabled while a run is in progress, since a sloppy tap
  or a Mango Chop slice would otherwise quit) — there's no "Menu" button like phone/web, since the
  swipe already does that job. The screen stays on during a run. Stages render through `App.kt`'s
  `WatchStageScreen`, which sets `HudLayout(watch = true)`: HUD text and chips shrink, and status
  panels drop to the lines that fit on a round face (`statusBottomPadding`; `StageResults`'s own
  hero/headline sizes also shrink for the watch). There are
  no start instructions or in-run prompts on any platform any more (a tutorial stage will cover that
  later); `Results.kt`'s `StageResults` screen stays up once a run ends, and so does Calibrate's
  timing strip while a run is in progress. Stage screens report runs via `ReportRunning`.

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
  on every push to `main` and deploys it to GitHub Pages at `https://webrender.net/wristbeat/` (the account's Pages custom domain)
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
