package wristbeat.app

actual class HapticEngine actual constructor() {
    actual fun pulse() {
        jsVibrate()
    }
}

private fun jsVibrate(): Unit = js(
    """{
        if (navigator.vibrate) {
            try { navigator.vibrate(18); } catch (e) {}
        }
    }"""
)
