package wristbeat.app

import wristbeat.core.SoundId

/** Silent placeholder until the Oboe-backed synth lands; the Android build currently has no audio. */
actual class AudioEngine actual constructor() {
    actual fun play(sound: SoundId, atSeconds: Double, param: Double) {}
}
