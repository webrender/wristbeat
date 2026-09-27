package wristbeat.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Single shared output stream behind [AudioClock] and [AudioEngine] so their timebases always agree
 * (the Android counterpart of `WebAudioContext`). A render thread mixes [SynthVoice]s into a
 * low-latency float [AudioTrack]; time is measured in stream frames, so `seconds * sampleRate` is
 * the frame a sound is rendered at, and [now] is the frame currently reaching the speaker (from
 * [AudioTrack.getTimestamp]).
 */
internal object AndroidAudio {
    val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000

    private val pending = ConcurrentLinkedQueue<SynthVoice>()

    @Volatile private var track: AudioTrack? = null
    @Volatile private var paused = false

    /** Frames handed to the track so far — the render cursor. */
    @Volatile private var framesWritten = 0L

    // Latest AudioTimestamp: stream frame [anchorFrame] reached the speaker at System.nanoTime() [anchorNanos].
    @Volatile private var anchorFrame = -1L
    @Volatile private var anchorNanos = 0L

    private var lastNow = 0.0

    /** Starts the stream on first use (and resumes it if paused). Safe to call repeatedly. */
    @Synchronized
    fun ensureStarted() {
        if (track != null) {
            resume()
            return
        }
        val framesPerBurst = WristbeatAndroid.appContext
            ?.let { (it.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }
            ?.toIntOrNull()
            ?.takeIf { it > 0 } ?: 256
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, framesPerBurst * 4 * Float.SIZE_BYTES))
            .build()
        track = created
        created.play()
        Thread({ renderLoop(created, framesPerBurst) }, "wristbeat-audio").apply { isDaemon = true }.start()
    }

    /** Stops output while the app is in the background; [now] holds still until [resume]. */
    @Synchronized
    fun pause() {
        val t = track ?: return
        if (paused) return
        now()
        paused = true
        anchorFrame = -1L
        t.pause()
    }

    @Synchronized
    fun resume() {
        val t = track ?: return
        if (!paused) return
        t.play()
        paused = false
    }

    /** The stream time, in seconds, of the sample currently reaching the speaker. Monotonic. */
    @Synchronized
    fun now(): Double {
        val t = track ?: return lastNow
        if (paused) return lastNow
        val written = framesWritten
        val frame = if (anchorFrame >= 0) {
            anchorFrame + (System.nanoTime() - anchorNanos) * sampleRate / 1e9
        } else {
            // No timestamp yet (the first ~100ms of output): assume the whole buffer is queued.
            (written - t.bufferSizeInFrames).toDouble()
        }
        val seconds = frame.coerceIn(0.0, written.toDouble()) / sampleRate
        if (seconds > lastNow) lastNow = seconds
        return lastNow
    }

    /** The output device the stream is playing through, or null before it starts. */
    fun routedDevice(): AudioDeviceInfo? = track?.routedDevice

    fun schedule(voice: SynthVoice) {
        pending += voice
    }

    fun frameAt(seconds: Double): Long = Math.round(seconds * sampleRate)

    private fun renderLoop(t: AudioTrack, framesPerBurst: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(framesPerBurst)
        val active = ArrayList<SynthVoice>()
        val ts = AudioTimestamp()
        var lastQueryNanos = 0L
        while (true) {
            val blockStart = framesWritten
            while (true) active += pending.poll() ?: break
            block.fill(0f)
            val it = active.iterator()
            while (it.hasNext()) {
                if (it.next().render(block, blockStart, block.size)) it.remove()
            }
            for (i in block.indices) block[i] = block[i].coerceIn(-1f, 1f)

            // Blocks until the track has room, which paces this loop to the output rate. A paused track
            // returns short instead, so wait out the pause rather than spinning.
            var done = 0
            while (done < block.size) {
                val n = t.write(block, done, block.size - done, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    Thread.sleep(20)
                    break
                }
                done += n
                if (done < block.size) Thread.sleep(20)
            }
            framesWritten = blockStart + done

            // The platform only refreshes timestamps every so often; polling a few times a second is plenty.
            val nowNanos = System.nanoTime()
            if (!paused && nowNanos - lastQueryNanos > 200_000_000L && t.getTimestamp(ts) && ts.framePosition > 0) {
                lastQueryNanos = nowNanos
                anchorFrame = ts.framePosition
                anchorNanos = ts.nanoTime
            }
        }
    }
}
