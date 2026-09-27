package wristbeat.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import wristbeat.app.WristbeatAndroid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WristbeatAndroid.init(this)
        setContent { WearApp() }
    }
}
