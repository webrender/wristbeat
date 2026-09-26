package wristbeat.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * Holds the application [Context] the Android actuals need (e.g. [HapticEngine]'s Vibrator), since
 * the common `expect` classes take no constructor arguments. The phone and Wear activities call
 * [init] before setting content.
 */
object WristbeatAndroid {
    internal var appContext: Context? = null
        private set

    fun init(context: Context) {
        if (appContext == null) {
            // Silence the audio stream (and hold the audio clock) while the app is in the background.
            (context.applicationContext as Application).registerActivityLifecycleCallbacks(PauseAudioWhenStopped)
        }
        appContext = context.applicationContext
    }

    private object PauseAudioWhenStopped : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = AndroidAudio.resume()
        override fun onActivityStopped(activity: Activity) = AndroidAudio.pause()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
