package wristbeat.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

private const val PREFS = "wristbeat"
private const val KEY_PREFIX = "calibration."

/**
 * The device the audio stream is routed to, once it has started; before that, a best guess at
 * where media would play (Bluetooth over wired over the built-in speaker, like Android's routing).
 */
internal actual fun currentAudioOutput(): AudioOutput {
    val context = WristbeatAndroid.appContext ?: return AudioOutput("default", null)
    val device = AndroidAudio.routedDevice()
        ?: (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .maxByOrNull { routePriority(it.type) }
        ?: return AudioOutput("default", null)
    val kind = when {
        routePriority(device.type) == 3 -> "bt"
        routePriority(device.type) == 2 -> "wired"
        else -> "speaker"
    }
    val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
    val label = when (kind) {
        "bt" -> name ?: "Bluetooth"
        "wired" -> if (device.type == AudioDeviceInfo.TYPE_USB_HEADSET) name ?: "USB headset" else "Wired headphones"
        else -> "Speaker"
    }
    // Headsets are told apart by name; the built-in speaker's product name is just the phone model.
    return AudioOutput(if (kind == "speaker") kind else "$kind:${name ?: device.type}", label)
}

private fun routePriority(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> 3
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> 2
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
    else -> 0
}

internal fun prefs() = WristbeatAndroid.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

internal actual fun loadCalibrationMs(outputId: String): Double? {
    val p = prefs() ?: return null
    val key = KEY_PREFIX + outputId
    return if (p.contains(key)) p.getFloat(key, 0f).toDouble() else null
}

internal actual fun saveCalibrationMs(outputId: String, offsetMs: Double) {
    prefs()?.edit()?.putFloat(KEY_PREFIX + outputId, offsetMs.toFloat())?.apply()
}
