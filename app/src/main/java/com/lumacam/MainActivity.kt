package com.lumacam

import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lumacam.ui.CameraScreen
import com.lumacam.ui.CameraViewModel
import com.lumacam.ui.LumaTheme
import com.lumacam.update.AppUpdater

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // La actividad está fija en vertical; el sensor indica cómo se sostiene el teléfono para
        // que fotos y videos queden bien orientados.
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                vm.onRotationChanged(rotation)
            }
        }
        setContent {
            LumaTheme {
                CameraScreen(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        vm.onVisible(true)
        AppUpdater.onAppVisible(this)
    }

    override fun onStop() {
        orientationListener.disable()
        vm.onVisible(false)
        super.onStop()
    }

    // Botones de volumen = disparador (más estable que tocar la pantalla).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) vm.onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
