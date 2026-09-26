package wristbeat.app

import android.content.Context

/**
 * Holds the application [Context] the Android actuals need (e.g. [HapticEngine]'s Vibrator), since
 * the common `expect` classes take no constructor arguments. The phone and Wear activities call
 * [init] before setting content.
 */
object WristbeatAndroid {
    internal var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
