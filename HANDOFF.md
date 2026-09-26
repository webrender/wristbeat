# Wristbeat: handoff

A rhythm game in the spirit of Rhythm Heaven: short one-button minigames, call-and-response cues you can play by ear, and original music and art. It started as a single-file web prototype (`wristbeat-prototype.html`, included). Jeremy has seen enough to commit to building it properly. He knows the prototype has issues but hasn't listed them yet, so **ask him what he noticed before fixing things.**

## Hard requirements

1. **Three targets from one project: web, Android (phone/tablet) and Wear OS.**
2. **The interface scales to each environment.** It isn't a watch UI stretched onto a phone. Round and square watches, phones in portrait and landscape, tablets, and desktop browsers should all get a layout that fits them (see "Scaling" below).
3. Timing accuracy comes first. A hit has to be judged against what the player *heard*, not against frame time.
4. All content is original. Borrow design ideas from Rhythm Heaven (call-and-response, audio cues you can play blind, remix stages), but don't use Nintendo characters, music or specific minigames.

Jeremy's background: software engineer. He has already shipped Wear OS apps, including a Micropolis/SimCity port called Watchopolis, so he's comfortable with Kotlin, Android tooling and publishing to Wear OS.

## What the prototype has (and should carry over)

- **Timing core.** The song position is `(audioOutputTime - t0) / secondsPerBeat`, and the audio output time comes from `AudioContext.getOutputTimestamp()`. Taps are converted from `event.timeStamp` into the same timebase, and the user's calibration offset is subtracted before judging.
- **Judgment.** Perfect is within ±45 ms and OK within ±120 ms. Anything past that is a miss, and the miss is detected when the song position passes target + 120 ms. Rank = (Perfect + 0.6 × OK) / total: 85% or more is Superb, 60% or more is OK, and anything lower is Try Again.
- **Look-ahead scheduler.** A timer fires every 25 ms and schedules all audio events due in the next 150 ms, placing each one exactly on the audio clock.
- **Chart model.** Each stage produces `{ events (music + cue sounds), cues (visual/haptic triggers), targets (beats the player must hit) }`.
- **Stages:**
  - *Snap Crabs:* the lead crab snaps a pattern in bar N, and the player repeats it in bar N+1. The patterns are `[0,1,2]`, `[0,.5,1]`, `[0,1,1.5,2]`, `[0,.5,1,1.5,2]` and `[.5,1.5,2]`.
  - *Mango Chop:* a whistle marks each toss. A mango lands 2 beats later (slow rising whistle) and a lime lands 1 beat later (fast, higher whistle). The player chops on landing.
  - *Calibrate:* the player taps along with a 36-beat click track, and the median error of their taps becomes the input offset.
- **Procedural content.** All music is synthesized at 116 BPM in F major, with an F–C–B♭–C progression. It has a kick, rim, hats, a saw bass, an offbeat ukulele-style strum, and a sine/triangle marimba lead. All art is flat canvas shapes on a logical 400×400 stage.
- **Haptics.** Every cue triggers a bezel pulse and, where supported, `navigator.vibrate`.

## Known problems in the prototype

These are the problems I know about. Jeremy may have seen others.

- It was only tested in headless Chromium, so the audio and feel were never checked on real hardware.
- The calibration offset is applied to input judging only. Visuals such as the fruit arcs aren't shifted to match.
- The scheduler uses `setInterval`, which browsers throttle in background tabs. There's also no pause or resume, and no handling for when the page becomes hidden.
- Stray taps cost nothing, so mashing isn't punished.
- Hold, release and rotary inputs aren't implemented yet. Tap is the only input.
- Safari/iOS: `roundRect` and `getOutputTimestamp` need to be checked, and a fallback path is needed.
- Everything lives in one file with hard-coded charts, and there's no chart format or editor.

## Architecture: decide with Jeremy first

**Constraint:** Wear OS generally doesn't ship a system WebView, so "wrap the web version" won't work for the watch. The watch build has to be native (or use an engine with a native Android export).

Options to present:

| Option | Web | Android + Wear OS | Notes |
|---|---|---|---|
| **Kotlin Multiplatform + Compose Multiplatform** (recommended starting point) | Compose for Web (Wasm) | Native Compose / Wear Compose | One language Jeremy already uses. Shared `commonMain` holds the charts, clock math, judgment and scoring. Audio and haptics sit behind `expect/actual`: Oboe via JNI on Android/Wear, Web Audio on web. Check how mature Compose Web is before committing. |
| Godot 4 | HTML5 export | Android export (verify on Wear OS: round screens, APK size, battery) | Good editor for animation. Web audio latency and the Wear OS fit need a spike first. |
| Shared C++ core + thin native shells | Emscripten | NDK + Oboe | The most control and the most work. |

Whatever is chosen, keep one **platform-independent game core**: chart parsing, the beat/clock math, judgment, scoring and the stage state machines. Keep each platform's adapters thin: audio output with timestamps, input with timestamps, haptics, rendering and storage. Write unit tests for the core, including judgment windows, miss detection and offset handling.

Suggested first milestone: a spike that plays a click track on a real watch and in a browser and logs the tap error. This proves the timing core works on each platform before any art gets built.

## Scaling across devices

- **One logical stage.** Author every scene in a square logical space (400×400 in the prototype). Scale it uniformly, and never stretch it.
- **Layout classes:**
  - *Round watch:* the stage fills the circle. The HUD is minimal and follows the rim, as the prototype's progress arc does. Keep critical art inside a safe inset of about 85% of the radius.
  - *Square watch:* same as round, with square insets.
  - *Phone portrait:* the stage sits at full width. Put the HUD (score, judgment readout, pause) in the space above and below it, and extend the background art to fill the extra area instead of letterboxing with black.
  - *Phone landscape, tablet and desktop web:* keep the stage centered at the largest size that fits. Use side areas for extended scenery plus menus and stats. The web version can show the timing panel the prototype had.
- **Scene art should bleed.** Each stage defines "extended background" drawing past the square so wide and tall screens don't show bars.
- **Text and touch targets** scale from the physical size (dp and density), not from pixels. On a watch, menus use Wear Compose patterns such as ScalingLazyColumn and rotary scrolling.
- **Input mapping per platform:**
  - *Tap:* touch anywhere on phones, tablets and watches. On the web, touch, click, Space, J or F.
  - *Hold:* the same inputs as Tap.
  - *Rotary:* the watch crown or bezel. On phones, a drag gesture. On the web, the mouse wheel or arrow keys.
  - Every source must deliver a hardware or event timestamp.
- **Haptics:** Wear OS and Android use `Vibrator` / `VibrationEffect` primitives. The web uses `navigator.vibrate` where it exists and falls back to a visual pulse.
- **Calibration per output route.** Store one offset per device *and* per audio route (built-in speaker vs. Bluetooth), because the latency differs a lot between them.

## Backlog after the spike

1. Define a chart format as JSON: BPM, tempo changes, offset, and cue events with type, beat and length. Load both prototype stages from it.
2. Build a charting tool: a small web page where you tap along to a song to place cues.
3. Add hold and rotary input, then a stage built around the crown or bezel.
4. Add a remix stage that mixes the cues of earlier stages.
5. Add pause and resume, handle app lifecycle and ambient mode on the watch, and keep the always-on display out of gameplay.
6. Save high scores and ranks locally.
7. Decide how audio is produced: keep procedural synthesis, or render stems offline for quality and battery.
